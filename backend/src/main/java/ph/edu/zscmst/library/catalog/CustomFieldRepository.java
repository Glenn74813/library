package ph.edu.zscmst.library.catalog;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomFieldRepository extends JpaRepository<CustomField, Long> {
    List<CustomField> findAllByOrderBySortOrderAscLabelAsc();

    boolean existsByKey(String key);
}
