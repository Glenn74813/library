package ph.edu.zscmst.library.gate;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface GateEventRepository extends JpaRepository<GateEvent, Long> {

    @EntityGraph(attributePaths = {"item", "item.record"})
    Page<GateEvent> findByOccurredAtBetweenOrderByOccurredAtDesc(Instant from, Instant to, Pageable pageable);

    @EntityGraph(attributePaths = {"item", "item.record"})
    Page<GateEvent> findByEventTypeAndOccurredAtBetweenOrderByOccurredAtDesc(GateEventType type, Instant from,
                                                                             Instant to, Pageable pageable);

    @EntityGraph(attributePaths = {"item", "item.record"})
    List<GateEvent> findByOccurredAtBetweenOrderByOccurredAtAsc(Instant from, Instant to);

    long countByEventTypeAndOccurredAtBetween(GateEventType type, Instant from, Instant to);

    @Query("select coalesce(sum(e.visitorCount), 0) from GateEvent e where e.eventType = ph.edu.zscmst.library.gate.GateEventType.VISITOR and e.occurredAt >= :from and e.occurredAt < :to")
    long sumVisitors(Instant from, Instant to);

    @Query("select e.gateId, max(e.occurredAt) from GateEvent e group by e.gateId order by e.gateId")
    List<Object[]> lastSeenPerGate();
}
