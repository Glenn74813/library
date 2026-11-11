package ph.edu.zscmst.library.digital;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import ph.edu.zscmst.library.catalog.BibRecord;

public interface DigitalFileRepository extends JpaRepository<DigitalFile, Long> {

    Optional<DigitalFile> findByRecordId(Long recordId);

    List<DigitalFile> findByRecordIdIn(Collection<Long> recordIds);

    boolean existsByRecordId(Long recordId);

    /** Titles readable as e-books: school-owned (uploaded file) or commercial (vendor link). */
    @Query("""
            select r from BibRecord r
            where (r.externalUrl is not null or exists (select f.id from DigitalFile f where f.record = r))
              and (:q = '' or lower(r.title) like lower(concat('%', :q, '%'))
                   or lower(coalesce(r.authors, '')) like lower(concat('%', :q, '%')))
            order by r.title
            """)
    Page<BibRecord> findEbookRecords(String q, Pageable pageable);
}
