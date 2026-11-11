package ph.edu.zscmst.library.common;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Library-local calendar: due dates fall at closing of a local day, fines count local days. */
@Component
public class LibraryClock {

    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("MMM d, yyyy");

    /** Second precision: PostgreSQL keeps microseconds, so LocalTime.MAX would round up to midnight. */
    private static final LocalTime LAST_SECOND = LocalTime.of(23, 59, 59);

    private final ZoneId zone;

    public LibraryClock(@Value("${app.library.timezone}") String timezone) {
        this.zone = ZoneId.of(timezone);
        Pdf.useZone(zone);
    }

    public ZoneId zone() {
        return zone;
    }

    public LocalDate today() {
        return LocalDate.now(zone);
    }

    public LocalDate toDate(Instant instant) {
        return instant.atZone(zone).toLocalDate();
    }

    /** Last instant of the given local day (due dates are inclusive of the whole day). */
    public Instant endOfDay(LocalDate date) {
        return date.atTime(LAST_SECOND).atZone(zone).toInstant();
    }

    public Instant startOfDay(LocalDate date) {
        return date.atStartOfDay(zone).toInstant();
    }

    public String format(Instant instant) {
        return instant == null ? "" : DISPLAY.format(instant.atZone(zone));
    }
}
