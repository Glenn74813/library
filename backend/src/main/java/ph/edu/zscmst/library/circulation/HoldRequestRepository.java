package ph.edu.zscmst.library.circulation;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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

    long countByPatronIdAndStatusIn(Long patronId, Collection<HoldStatus> statuses);

    boolean existsByRecordIdAndPatronIdAndStatusIn(Long recordId, Long patronId, Collection<HoldStatus> statuses);

    long countByRecordIdAndStatusAndPlacedAtBefore(Long recordId, HoldStatus status, Instant placedAt);

    long countByStatus(HoldStatus status);

    long countByStatusAndExpiresAtBefore(HoldStatus status, Instant before);

    /** Waiting queue for a title: activated bookings first, then by time placed. */
    @EntityGraph(attributePaths = {"record", "patron", "item"})
    @Query("""
            select h from HoldRequest h where h.record.id = :recordId and h.status = :status
            order by case when h.pickupDate is null then 1 else 0 end, h.pickupDate, h.placedAt
            """)
    List<HoldRequest> findQueue(Long recordId, HoldStatus status, Pageable pageable);

    @EntityGraph(attributePaths = {"record", "patron"})
    List<HoldRequest> findByRecordIdAndStatusOrderByPickupDateAsc(Long recordId, HoldStatus status);

    @EntityGraph(attributePaths = {"record", "patron"})
    List<HoldRequest> findByStatusAndPickupDateLessThanEqual(HoldStatus status, LocalDate date);

    @EntityGraph(attributePaths = {"record", "patron"})
    List<HoldRequest> findByStatusOrderByPickupDateAsc(HoldStatus status);

    long countByStatusAndPickupDate(HoldStatus status, LocalDate date);

    @EntityGraph(attributePaths = {"record", "patron"})
    Optional<HoldRequest> findFirstByRecordIdAndPatronIdAndStatus(Long recordId, Long patronId, HoldStatus status);
}
