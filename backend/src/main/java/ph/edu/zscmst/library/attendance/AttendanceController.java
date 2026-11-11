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
import org.springframework.web.bind.annotation.GetMapping;
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
import ph.edu.zscmst.library.common.Csv;
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
            }
        }
        return new SyncResult(ok, rejected);
    }

    // ------------------------------------------------ staff endpoints

    @GetMapping("/api/attendance/logs")
    public PageResponse<LogDto> logs(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "50") int size) {
        LocalDate t = to == null ? clock.today() : to;
        LocalDate f = from == null ? t : from;
        return PageResponse.of(service.logs(f, t, PageRequest.of(page, Math.min(size, 200))), l -> l);
    }

    @GetMapping("/api/attendance/stats")
    public Stats stats(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate t = to == null ? clock.today() : to;
        return service.stats(from == null ? t.minusDays(29) : from, t);
    }

    @GetMapping("/api/attendance/export")
    public ResponseEntity<byte[]> export(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        List<List<?>> rows = service.allLogs(from, to).stream().<List<?>>map(l -> List.of(
                l.cardNumber(), l.patronName(), l.course() == null ? "" : l.course(), l.category(),
                l.timeIn().atZone(clock.zone()).toLocalDateTime().toString(),
                l.timeOut() == null ? "" : l.timeOut().atZone(clock.zone()).toLocalDateTime().toString(),
                l.method())).toList();
        return Csv.download("attendance-" + from + "-to-" + to + ".csv",
                List.of("Card Number", "Name", "Course", "Category", "Time In", "Time Out", "Method"), rows);
    }

    @GetMapping("/api/attendance/settings")
    public Map<String, String> getSettings() {
        return settings.getByPrefix("kiosk.");
    }

    @PutMapping("/api/attendance/settings")
    @PreAuthorize("hasAnyRole('ADMIN','LIBRARIAN')")
    public Map<String, String> updateSettings(@RequestBody Map<String, String> values) {
        values.forEach((k, v) -> {
            if (!KIOSK_KEYS.contains(k)) throw ApiException.badRequest("Unknown setting: " + k);
            if (k.equals("mode") && !v.equals("ATTENDANCE") && !v.equals("STANDBY")) {
                throw ApiException.badRequest("mode must be ATTENDANCE or STANDBY");
            }
            settings.put("kiosk." + k, v);
        });
        audit.record("KIOSK_SETTINGS", values.keySet().toString());
        return settings.getByPrefix("kiosk.");
    }
}
