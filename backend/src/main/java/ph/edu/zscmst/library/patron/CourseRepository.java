package ph.edu.zscmst.library.patron;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CourseRepository extends JpaRepository<Course, Long> {
    Optional<Course> findByCodeIgnoreCase(String code);

    List<Course> findAllByOrderByCodeAsc();
}
