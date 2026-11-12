package ph.edu.zscmst.library.authority;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AuthorityRepository extends JpaRepository<Authority, Long> {

    List<Authority> findByTypeOrderByHeadingAsc(Authority.Type type);

    Optional<Authority> findFirstByTypeAndHeadingIgnoreCase(Authority.Type type, String heading);

    /** Authority whose variant list contains {@code form} (case-insensitive, whole '|' token). */
    @Query(value = """
            SELECT * FROM authority a WHERE a.type = :type
              AND strpos(lower('|' || coalesce(a.variants, '') || '|'), '|' || lower(:form) || '|') > 0 LIMIT 1
            """, nativeQuery = true)
    Optional<Authority> findByVariant(String type, String form);

    /** Headings actually used in the catalog with how many titles use each. */
    @Query(value = """
            SELECT h, count(*) FROM (
              SELECT trim(regexp_split_to_table(CASE WHEN :type = 'AUTHOR' THEN authors ELSE subjects END, ';')) AS h
              FROM bib_record) x
            WHERE h <> '' GROUP BY h ORDER BY lower(h)
            """, nativeQuery = true)
    List<Object[]> usage(String type);
}
