package ph.edu.zscmst.library.inventory;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StocktakeSessionRepository extends JpaRepository<StocktakeSession, Long> {
    List<StocktakeSession> findAllByOrderByStartedAtDesc();

    long countByClosedAtIsNull();
}
