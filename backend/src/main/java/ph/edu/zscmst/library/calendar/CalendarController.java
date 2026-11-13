package ph.edu.zscmst.library.calendar;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import ph.edu.zscmst.library.common.ApiException;
import ph.edu.zscmst.library.common.AuditService;
import ph.edu.zscmst.library.common.LibraryClock;

@RestController
public class CalendarController {

    public record DayHours(@Min(1) @Max(7) int weekday, LocalTime openTime, LocalTime closeTime) {
    }

    public record ClosedDayDto(@NotNull LocalDate day, @NotBlank @Size(max = 120) String reason) {
    }

    /** A Philippine holiday, and whether it is already a closed day here. */
    public record HolidayDto(LocalDate day, String name, PhilippineHolidays.Kind kind, boolean added) {
    }

    /** Public view (OPAC, kiosks): opening hours and upcoming closures. */
    public record PublicCalendar(List<DayHours> hours, List<ClosedDayDto> upcomingClosures, boolean openToday) {
    }

    private final LibraryHoursRepository hours;
    private final ClosedDayRepository closed;
    private final LibraryCalendar calendar;
    private final LibraryClock clock;
    private final AuditService audit;
    private final PhilippineHolidays holidays;

    public CalendarController(LibraryHoursRepository hours, ClosedDayRepository closed, LibraryCalendar calendar,
                              LibraryClock clock, AuditService audit, PhilippineHolidays holidays) {
        this.holidays = holidays;
        this.hours = hours;
        this.closed = closed;
        this.calendar = calendar;
        this.clock = clock;
        this.audit = audit;
    }

    @GetMapping("/api/opac/calendar")
    @Transactional(readOnly = true)
    public PublicCalendar publicCalendar() {
        return new PublicCalendar(week(), closed.findByDayGreaterThanEqualOrderByDayAsc(clock.today()).stream()
                .limit(20).map(c -> new ClosedDayDto(c.getDay(), c.getReason())).toList(), calendar.isOpen(clock.today()));
    }

    private List<DayHours> week() {
        return hours.findAllByOrderByWeekdayAsc().stream()
                .map(h -> new DayHours(h.getWeekday(), h.getOpenTime(), h.getCloseTime())).toList();
    }

    @PutMapping("/api/calendar/hours")
    @PreAuthorize("hasAnyRole('ADMIN','LIBRARIAN')")
    @Transactional
    public List<DayHours> updateHours(@RequestBody List<@Valid DayHours> week) {
        for (DayHours d : week) {
            boolean open = d.openTime() != null && d.closeTime() != null;
            if (open && !d.closeTime().isAfter(d.openTime())) {
                throw ApiException.badRequest("Closing time must be after opening time");
            }
            LibraryHours h = hours.findById(d.weekday()).orElseGet(() -> new LibraryHours(d.weekday(), null, null));
            h.set(open ? d.openTime() : null, open ? d.closeTime() : null);
            hours.save(h);
        }
        audit.record("CALENDAR_HOURS", "weekly opening hours updated");
        return week();
    }

    @GetMapping("/api/calendar/closed-days")
    public List<ClosedDayDto> closedDays() {
        return closed.findByDayGreaterThanEqualOrderByDayAsc(clock.today().minusDays(30)).stream()
                .map(c -> new ClosedDayDto(c.getDay(), c.getReason())).toList();
    }

    @PostMapping("/api/calendar/closed-days")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','LIBRARIAN')")
    @Transactional
    public ClosedDayDto addClosedDay(@Valid @RequestBody ClosedDayDto req) {
        closed.save(new ClosedDay(req.day(), req.reason().trim()));
        audit.record("CALENDAR_CLOSED_DAY", req.day() + " " + req.reason());
        return req;
    }

    @DeleteMapping("/api/calendar/closed-days/{day}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','LIBRARIAN')")
    @Transactional
    public void removeClosedDay(@PathVariable LocalDate day) {
        closed.deleteById(day);
    }

    /** The year's Philippine holidays, to pick from and add as closed days. */
    @GetMapping("/api/calendar/ph-holidays")
    @Transactional(readOnly = true)
    public List<HolidayDto> philippineHolidays(@org.springframework.web.bind.annotation.RequestParam(required = false) Integer year) {
        int y = year == null ? clock.today().getYear() : year;
        if (y < 2000 || y > 2100) throw ApiException.badRequest("Year must be between 2000 and 2100");
        java.util.Set<LocalDate> already = new java.util.HashSet<>();
        closed.findByDayBetween(LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31)).forEach(c -> already.add(c.getDay()));
        return holidays.forYear(y).stream().map(h -> new HolidayDto(h.day(), h.name(), h.kind(), already.contains(h.day()))).toList();
    }

    /** Adds the chosen holidays as closed days; days already closed keep their own reason. */
    @PostMapping("/api/calendar/ph-holidays")
    @PreAuthorize("hasAnyRole('ADMIN','LIBRARIAN')")
    @Transactional
    public java.util.Map<String, Integer> addPhilippineHolidays(@RequestBody List<@Valid ClosedDayDto> days) {
        int added = 0;
        for (ClosedDayDto d : days) {
            if (closed.existsById(d.day())) continue;
            closed.save(new ClosedDay(d.day(), d.reason().trim()));
            added++;
        }
        if (added > 0) audit.record("CALENDAR_CLOSED_DAY", added + " Philippine holiday(s) added");
        return java.util.Map.of("added", added, "skipped", days.size() - added);
    }
}
