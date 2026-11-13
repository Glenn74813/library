package ph.edu.zscmst.library.attendance;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ph.edu.zscmst.library.attendance.AttendanceService.KioskConfig;
import ph.edu.zscmst.library.attendance.AttendanceService.LogDto;
import ph.edu.zscmst.library.attendance.AttendanceService.Stats;
import ph.edu.zscmst.library.attendance.AttendanceService.TapRequest;
import ph.edu.zscmst.library.attendance.AttendanceService.TapResult;
import ph.edu.zscmst.library.common.ApiException;
import ph.edu.zscmst.library.common.AuditService;
import ph.edu.zscmst.library.common.LibraryClock;
import ph.edu.zscmst.library.common.PageResponse;
import ph.edu.zscmst.library.common.SettingsService;

@RestController
public class AttendanceController {

    public record SyncRequest(@NotNull @Size(max = 2000) List<TapRequest> taps) {
    }

    public record SyncResult(int accepted, List<String> rejected) {
    }

    private static final Set<String> KIOSK_KEYS = Set.of("mode", "singleTap", "entryMessage", "exitMessage",
            "standbyMessages", "videoUrl");

    private final AttendanceService service;
    private final SettingsService settings;
    private final LibraryClock clock;
    private final AuditService audit;

    public AttendanceController(AttendanceService service, SettingsService settings, LibraryClock clock,
                                AuditService audit) {
        this.service = service;
        this.settings = settings;
        this.clock = clock;
        this.audit = audit;
    }

    // ------------------------------------------------ kiosk device endpoints

    @GetMapping("/api/kiosk/config")
    public KioskConfig kioskConfig() {
        return service.config();
    }

    @PostMapping("/api/kiosk/attendance/tap")
    public TapResult tap(@RequestBody TapRequest req) {
        return service.tap(new TapRequest(req.code(), req.method(), null));
    }

    /** Uploads taps the kiosk captured while the server was unreachable. */
    @PostMapping("/api/kiosk/attendance/sync")
    public SyncResult sync(@Valid @RequestBody SyncRequest req) {
        int ok = 0;
        List<String> rejected = new ArrayList<>();
        for (TapRequest tap : req.taps()) {
            try {
                service.tap(tap);
                ok++;
            } catch (ApiException e) {
                rejected.add(tap.code() + ": " + e.getMessage());
            } catch (RuntimeException e) {
                // One bad tap must not abort the rest (the kiosk would resend, and re-apply, those already done).
                rejected.add(tap.code() + ": could not be recorded");
            }
        }
        return new SyncResult(ok, rejected);
    }

    // ------------------------------------------------ staff endpoints

    @GetMapping("/api/attendance/logs")
    public PageResponse<LogDto> logs(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                     @RequestParam(defaultValue = "") String q,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "50") int size) {
        LocalDate t = to == null ? clock.today() : to;
        LocalDate f = from == null ? t : from;
        return PageResponse.of(service.logs(f, t, q, PageRequest.of(page, Math.min(size, 200))), l -> l);
    }

    @GetMapping("/api/attendance/patrons/{patronId}")
    public AttendanceService.PatronAttendance patron(@PathVariable Long patronId) {
        return service.forPatron(patronId);
    }

    @GetMapping("/api/attendance/stats")
    public Stats stats(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate t = to == null ? clock.today() : to;
        return service.stats(from == null ? t.minusDays(29) : from, t);
    }

    @GetMapping("/api/attendance/export")
    public ResponseEntity<byte[]> export(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         @RequestParam(defaultValue = "csv") String format,
                                         @RequestParam(required = false) Long categoryId, @RequestParam(required = false) Long courseId) {
        return table(from, to, categoryId, courseId).download(format);
    }

    @GetMapping("/api/attendance/export/preview")
    public ph.edu.zscmst.library.common.ReportTable.Preview exportPreview(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long categoryId, @RequestParam(required = false) Long courseId) {
        return table(from, to, categoryId, courseId).preview();
    }

    /** Visits in the period, optionally only patrons of one category and/or course. */
    private ph.edu.zscmst.library.common.ReportTable table(LocalDate from, LocalDate to, Long categoryId, Long courseId) {
        List<AttendanceService.LogDto> logs = service.allLogs(from, to, categoryId, courseId);
        List<List<?>> rows = logs.stream().<List<?>>map(l -> List.of(
                l.cardNumber(), l.patronName(), l.course() == null ? "" : l.course(), l.category(),
                stamp(l.timeIn()), l.timeOut() == null ? "" : stamp(l.timeOut()),
                l.method())).toList();
        long people = logs.stream().map(AttendanceService.LogDto::patronId).distinct().count();
        return new ph.edu.zscmst.library.common.ReportTable("attendance-" + from + "-to-" + to, "Attendance Log",
                ph.edu.zscmst.library.common.ReportTable.day(from) + " to " + ph.edu.zscmst.library.common.ReportTable.day(to)
                        + " · " + rows.size() + " visit(s) by " + people + " patron(s)",
                List.of("ID Number", "Name", "Course", "Category", "Time In", "Time Out", "Method"), rows);
    }

    private String stamp(java.time.Instant i) {
        return i.atZone(clock.zone()).toLocalDateTime().withNano(0).toString().replace('T', ' ');
    }

    @GetMapping("/api/attendance/settings")
    public Map<String, String> getSettings() {
        return kioskOnly(settings.getByPrefix("kiosk."));
    }

    /** Only this page's settings; other kiosk.* keys (OPAC reset, duplicate taps) belong to System settings. */
    private static Map<String, String> kioskOnly(Map<String, String> all) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        all.forEach((k, v) -> { if (KIOSK_KEYS.contains(k)) out.put(k, v); });
        return out;
    }

    @PutMapping("/api/attendance/settings")
    @PreAuthorize("hasAnyRole('ADMIN','LIBRARIAN')")
    @Transactional
    public Map<String, String> updateSettings(@RequestBody Map<String, String> values) {
        // Check everything before saving anything, so a bad value can't leave half the settings saved.
        values.forEach((k, v) -> {
            if (!KIOSK_KEYS.contains(k)) throw ApiException.badRequest("Unknown setting: " + k);
            if (k.equals("mode") && !"ATTENDANCE".equals(v) && !"STANDBY".equals(v)) {
                throw ApiException.badRequest("mode must be ATTENDANCE or STANDBY");
            }
        });
        values.forEach((k, v) -> settings.put("kiosk." + k, v == null ? "" : v));
        audit.record("KIOSK_SETTINGS", values.keySet().toString());
        return kioskOnly(settings.getByPrefix("kiosk."));
    }
}
