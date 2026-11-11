package ph.edu.zscmst.library.calendar;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LibraryHoursRepository extends JpaRepository<LibraryHours, Integer> {
    List<LibraryHours> findAllByOrderByWeekdayAsc();
}
