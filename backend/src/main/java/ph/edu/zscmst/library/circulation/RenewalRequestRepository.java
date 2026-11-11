package ph.edu.zscmst.library.circulation;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface RenewalRequestRepository extends JpaRepository<RenewalRequest, Long> {

    @EntityGraph(attributePaths = {"loan", "loan.item", "loan.item.record", "patron"})
    List<RenewalRequest> findByStatusOrderByRequestedAtAsc(RenewalRequest.Status status);

    @EntityGraph(attributePaths = {"loan", "loan.item", "loan.item.record", "patron"})
    Page<RenewalRequest> findByStatusInOrderByDecidedAtDesc(Collection<RenewalRequest.Status> statuses, Pageable pageable);

    @EntityGraph(attributePaths = {"loan", "loan.item", "loan.item.record", "patron"})
    List<RenewalRequest> findTop20ByPatronIdOrderByRequestedAtDesc(Long patronId);

    @EntityGraph(attributePaths = {"loan", "loan.item", "loan.item.record", "patron"})
    Optional<RenewalRequest> findWithDetailsById(Long id);

    boolean existsByLoanIdAndStatus(Long loanId, RenewalRequest.Status status);

    long countByStatus(RenewalRequest.Status status);

    /** A returned (or lost) loan no longer needs a decision. */
    @Modifying
    @Query("""
            update RenewalRequest r set r.status = ph.edu.zscmst.library.circulation.RenewalRequest.Status.CANCELLED,
                   r.decidedAt = CURRENT_TIMESTAMP, r.decidedBy = 'system', r.reason = 'Item was returned'
            where r.loan.id = :loanId and r.status = ph.edu.zscmst.library.circulation.RenewalRequest.Status.PENDING
            """)
    int cancelPendingForLoan(Long loanId);
}
