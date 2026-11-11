package ph.edu.zscmst.library.calendar;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opening days of the library. Due dates and pickup deadlines move to the next open day, and
 * overdue fines count only days the library was open.
 */
@Service
public class LibraryCalendar {

    private final LibraryHoursRepository hours;
    private final ClosedDayRepository closed;

    public LibraryCalendar(LibraryHoursRepository hours, ClosedDayRepository closed) {
        this.hours = hours;
        this.closed = closed;
    }

    /** Snapshot of the rules for one computation (avoids a query per day). */
    public final class Snapshot {
        private final Map<Integer, LibraryHours> week = new HashMap<>();
        private final Set<LocalDate> closedDays = new HashSet<>();

        private Snapshot(LocalDate from, LocalDate to) {
            hours.findAll().forEach(h -> week.put(h.getWeekday(), h));
            closed.findByDayBetween(from, to).forEach(c -> closedDays.add(c.getDay()));
        }

        public boolean isOpen(LocalDate d) {
            LibraryHours h = week.get(d.getDayOfWeek().getValue());
            boolean anyOpenDay = week.values().stream().anyMatch(LibraryHours::isOpen);
            // With no opening hours configured at all, treat every day as open rather than looping forever.
            return !anyOpenDay || (h != null && h.isOpen() && !closedDays.contains(d));
        }

        public LocalTime openingTime(LocalDate d) {
            LibraryHours h = week.get(d.getDayOfWeek().getValue());
            return h == null ? null : h.getOpenTime();
        }

        public LocalTime closingTime(LocalDate d) {
            LibraryHours h = week.get(d.getDayOfWeek().getValue());
            return h == null ? null : h.getCloseTime();
        }

        public LocalDate nextOpenOnOrAfter(LocalDate d) {
            LocalDate x = d;
            for (int i = 0; i < 400 && !isOpen(x); i++) x = x.plusDays(1);
            return x;
        }

        public LocalDate previousOpenOnOrBefore(LocalDate d) {
            LocalDate x = d;
            for (int i = 0; i < 400 && !isOpen(x); i++) x = x.minusDays(1);
            return x;
        }

        /** Open days in (from, to], i.e. chargeable late days between a due date and a return date. */
        public long openDaysAfter(LocalDate from, LocalDate to) {
            long n = 0;
            for (LocalDate d = from.plusDays(1); !d.isAfter(to); d = d.plusDays(1)) {
                if (isOpen(d)) n++;
            }
            return n;
        }
    }

    @Transactional(readOnly = true)
    public Snapshot snapshot(LocalDate from, LocalDate to) {
        return new Snapshot(from.minusDays(1), to.plusDays(400));
    }

    @Transactional(readOnly = true)
    public boolean isOpen(LocalDate d) {
        return snapshot(d, d).isOpen(d);
    }

    @Transactional(readOnly = true)
    public LocalDate nextOpenOnOrAfter(LocalDate d) {
        return snapshot(d, d).nextOpenOnOrAfter(d);
    }
}
