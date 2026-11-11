package ph.edu.zscmst.library.catalog;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface BibRecordRepository extends JpaRepository<BibRecord, Long> {

    String SEARCH_WHERE = """
             WHERE (:q = ''
                OR (:field = 'title' AND (r.title ILIKE '%' || :q || '%' OR r.subtitle ILIKE '%' || :q || '%'))
                OR (:field = 'author' AND r.authors ILIKE '%' || :q || '%')
                OR (:field = 'subject' AND r.subjects ILIKE '%' || :q || '%')
                OR (:field = 'isbn' AND replace(r.isbn, '-', '') = replace(:q, '-', ''))
                OR (:field = 'callno' AND r.call_number ILIKE :q || '%')
                OR (:field = 'any' AND (r.search_vector @@ websearch_to_tsquery('simple', :q)
                                        OR r.title ILIKE '%' || :q || '%'
                                        OR r.authors ILIKE '%' || :q || '%'
                                        OR replace(r.isbn, '-', '') = replace(:q, '-', '')
                                        OR r.call_number ILIKE :q || '%')))
               AND (:type = '' OR r.material_type = :type)
            """;

    /**
     * Full-text search with relevance ranking. {@code field} is one of any/title/author/subject/isbn/callno;
     * empty {@code q} or {@code type} disables that filter.
     */
    @Query(value = "SELECT r.* FROM bib_record r" + SEARCH_WHERE + """
             ORDER BY CASE WHEN :q = '' THEN 0
                           ELSE ts_rank(r.search_vector, websearch_to_tsquery('simple', :q))
                                + CASE WHEN lower(r.title) = lower(:q) THEN 1 ELSE 0 END END DESC,
                      r.title, r.id
            """,
            countQuery = "SELECT count(*) FROM bib_record r" + SEARCH_WHERE,
            nativeQuery = true)
    Page<BibRecord> search(String q, String field, String type, Pageable pageable);

    List<BibRecord> findTop12ByOrderByCreatedAtDesc();
}
