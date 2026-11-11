package ph.edu.zscmst.library.patron;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PatronCategoryRepository extends JpaRepository<PatronCategory, Long> {
    Optional<PatronCategory> findByNameIgnoreCase(String name);

    List<PatronCategory> findAllByOrderByNameAsc();
}
