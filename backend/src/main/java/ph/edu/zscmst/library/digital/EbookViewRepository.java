package ph.edu.zscmst.library.digital;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EbookViewRepository extends JpaRepository<EbookView, Long> {

    long countByRecordId(Long recordId);
}
