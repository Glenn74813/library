package ph.edu.zscmst.library.circulation;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HoldRequestRepository extends JpaRepository<HoldRequest, Long> {

    @EntityGraph(attributePaths = {"record", "patron", "item"})
    List<HoldRequest> findByStatusInOrderByPlacedAtAsc(Collection<HoldStatus> statuses);

    @EntityGraph(attributePaths = {"record", "patron", "item"})
    List<HoldRequest> findByPatronIdOrderByPlacedAtDesc(Long patronId);

    @EntityGraph(attributePaths = {"record", "patron", "item"})
    Optional<HoldRequest> findFirstByRecordIdAndStatusOrderByPlacedAtAsc(Long recordId, HoldStatus status);

    @EntityGraph(attributePaths = {"record", "patron", "item"})
    Optional<HoldRequest> findByItemIdAndStatus(Long itemId, HoldStatus status);

    @EntityGraph(attributePaths = {"record", "patron", "item"})
    List<HoldRequest> findByStatusAndExpiresAtBefore(HoldStatus status, Instant now);

    boolean existsByRecordIdAndStatus(Long recordId, HoldStatus status);

    boolean existsByRecordIdAndPatronIdAndStatusIn(Long recordId, Long patronId, Collection<HoldStatus> statuses);

    long countByRecordIdAndStatusAndPlacedAtBefore(Long recordId, HoldStatus status, Instant placedAt);

    long countByStatus(HoldStatus status);

    long countByStatusAndExpiresAtBefore(HoldStatus status, Instant before);
}
