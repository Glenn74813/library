package ph.edu.zscmst.library.attendance;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import ph.edu.zscmst.library.calendar.LibraryCalendar;
import ph.edu.zscmst.library.common.LibraryClock;

/**
 * Times out visitors who forgot to tap out: once the library has closed, every open visit of that
 * day gets the closing time as its time-out (or its own time-in if it started after closing).
 */
@Component
public class AttendanceAutoTimeout {

    private static final Logger log = LoggerFactory.getLogger(AttendanceAutoTimeout.class);

    private final AttendanceLogRepository logs;
    private final LibraryCalendar calendar;
    private final LibraryClock clock;

    public AttendanceAutoTimeout(AttendanceLogRepository logs, LibraryCalendar calendar, LibraryClock clock) {
        this.logs = logs;
        this.calendar = calendar;
        this.clock = clock;
    }

    @Scheduled(cron = "0 */10 * * * *")
    @Transactional
    public void closeOpenVisits() {
        Instant now = Instant.now();
        LocalDate today = clock.today();
        LibraryCalendar.Snapshot cal = calendar.snapshot(today.minusDays(60), today);
        int closed = 0;
        for (AttendanceLog a : logs.findByTimeOutIsNullAndTimeInBefore(now)) {
            LocalDate day = clock.toDate(a.getTimeIn());
            LocalTime closing = cal.isOpen(day) ? cal.closingTime(day) : null;
            Instant closeAt = closing != null ? day.atTime(closing).atZone(clock.zone()).toInstant() : clock.endOfDay(day);
            if (now.isBefore(closeAt)) continue;          // library still open for this visit
            a.setTimeOut(closeAt.isBefore(a.getTimeIn()) ? a.getTimeIn() : closeAt);
            closed++;
        }
        if (closed > 0) log.info("Auto timed-out {} visit(s) at closing time", closed);
    }
}
