package ph.edu.zscmst.library.circulation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FineRepository extends JpaRepository<Fine, Long> {

    @Query("select coalesce(sum(f.amount), 0) from Fine f where f.patron.id = :patronId and f.status = ph.edu.zscmst.library.circulation.FineStatus.UNPAID")
    BigDecimal unpaidTotal(Long patronId);

    @EntityGraph(attributePaths = {"patron", "loan", "loan.item", "loan.item.record"})
    List<Fine> findByPatronIdOrderByCreatedAtDesc(Long patronId);

    Optional<Fine> findFirstByLoanIdAndStatusOrderByIdDesc(Long loanId, FineStatus status);

    List<Fine> findByLoanIdAndStatusOrderByIdAsc(Long loanId, FineStatus status);

    /** Overdue fines already charged for a loan (paid, unpaid or waived), so late days are charged once. */
    @Query("select coalesce(sum(f.amount), 0) from Fine f where f.loan.id = :loanId and f.reason like 'Overdue%'")
    BigDecimal overdueChargedForLoan(@Param("loanId") Long loanId);

    /** Fines of patrons whose name or ID number matches (for the cashier), newest first. */
    @EntityGraph(attributePaths = {"patron"})
    @Query("""
            select f from Fine f where (:status is null or f.status = :status)
              and (lower(f.patron.cardNumber) like :q or lower(concat(f.patron.firstName, ' ', f.patron.lastName)) like :q
                   or lower(concat(f.patron.lastName, ', ', f.patron.firstName)) like :q)
            order by f.createdAt desc""")
    Page<Fine> searchByPatron(@Param("status") FineStatus status, @Param("q") String q, Pageable pageable);

    @EntityGraph(attributePaths = {"patron"})
    Page<Fine> findByStatusOrderByCreatedAtDesc(FineStatus status, Pageable pageable);

    @EntityGraph(attributePaths = {"patron"})
    Page<Fine> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @EntityGraph(attributePaths = {"patron"})
    List<Fine> findByIdIn(Collection<Long> ids);

    /** Row-locks the fines (in id order, so two payments can't deadlock) while they are being paid. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from Fine f where f.id in :ids order by f.id")
    List<Fine> lockByIds(Collection<Long> ids);

    @EntityGraph(attributePaths = {"patron"})
    List<Fine> findByReceiptNumberOrderByIdAsc(String receiptNumber);

    @Query("select coalesce(sum(f.amount), 0) from Fine f where f.status = :status and f.settledAt >= :from and f.settledAt < :to")
    BigDecimal sumSettled(FineStatus status, Instant from, Instant to);

    @Query("select coalesce(sum(f.amount), 0) from Fine f where f.status = ph.edu.zscmst.library.circulation.FineStatus.UNPAID")
    BigDecimal totalUnpaid();
}
