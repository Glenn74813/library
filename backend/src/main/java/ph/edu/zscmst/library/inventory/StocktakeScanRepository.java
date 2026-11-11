package ph.edu.zscmst.library.inventory;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StocktakeScanRepository extends JpaRepository<StocktakeScan, Long> {

    boolean existsBySessionIdAndCode(Long sessionId, String code);

    boolean existsBySessionIdAndItemId(Long sessionId, Long itemId);

    @EntityGraph(attributePaths = {"item", "item.record"})
    List<StocktakeScan> findBySessionIdOrderByScannedAtAsc(Long sessionId);

    long countBySessionId(Long sessionId);
}
