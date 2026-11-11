package ph.edu.zscmst.library.notice;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NoticeRepository extends JpaRepository<Notice, Long> {

    boolean existsByTypeAndReference(NoticeType type, String reference);

    @EntityGraph(attributePaths = "patron")
    List<Notice> findTop200BySentAtIsNullAndAttemptsLessThanOrderByCreatedAtAsc(int attempts);

    @EntityGraph(attributePaths = "patron")
    Page<Notice> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<Notice> findTop20ByPatronIdOrderByCreatedAtDesc(Long patronId);
}
