package ph.edu.zscmst.library.common;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    Page<AuditLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** Blank text filters match everything; the period is [from, to). */
    @Query("""
            select a from AuditLog a
            where (:action = '' or a.action = :action)
              and (:actor = '' or lower(a.actor) like lower(concat('%', :actor, '%')))
              and (:q = '' or lower(coalesce(a.details, '')) like lower(concat('%', :q, '%')))
              and a.createdAt >= :from and a.createdAt < :to
            order by a.createdAt desc, a.id desc
            """)
    Page<AuditLog> search(String q, String action, String actor, Instant from, Instant to, Pageable pageable);

    @Query("select distinct a.action from AuditLog a order by a.action")
    List<String> actions();
}
