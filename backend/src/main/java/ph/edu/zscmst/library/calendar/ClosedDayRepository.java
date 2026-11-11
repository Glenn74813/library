package ph.edu.zscmst.library.calendar;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ClosedDayRepository extends JpaRepository<ClosedDay, LocalDate> {
    List<ClosedDay> findByDayGreaterThanEqualOrderByDayAsc(LocalDate from);

    List<ClosedDay> findByDayBetween(LocalDate from, LocalDate to);
}
