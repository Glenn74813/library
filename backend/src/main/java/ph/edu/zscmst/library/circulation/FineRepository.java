package ph.edu.zscmst.library.circulation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface FineRepository extends JpaRepository<Fine, Long> {

    @Query("select coalesce(sum(f.amount), 0) from Fine f where f.patron.id = :patronId and f.status = ph.edu.zscmst.library.circulation.FineStatus.UNPAID")
    BigDecimal unpaidTotal(Long patronId);

    @EntityGraph(attributePaths = {"patron", "loan", "loan.item", "loan.item.record"})
    List<Fine> findByPatronIdOrderByCreatedAtDesc(Long patronId);

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
