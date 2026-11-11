package ph.edu.zscmst.library.report;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ph.edu.zscmst.library.common.AuditLog;
import ph.edu.zscmst.library.common.AuditLogRepository;
import ph.edu.zscmst.library.common.LibraryClock;
import ph.edu.zscmst.library.common.PageResponse;
import ph.edu.zscmst.library.common.Pdf;

/** Audit trail (ADMIN): search by text, action, user and period, and export. */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    public record AuditDto(Long id, String actor, String action, String details, Instant createdAt) {
        static AuditDto of(AuditLog a) {
            return new AuditDto(a.getId(), a.getActor(), a.getAction(), a.getDetails(), a.getCreatedAt());
        }
    }

    private static final int EXPORT_LIMIT = 10_000;

    private final AuditLogRepository repository;
    private final LibraryClock clock;

    public AuditController(AuditLogRepository repository, LibraryClock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @GetMapping
    public PageResponse<AuditDto> list(@RequestParam(defaultValue = "") String q,
                                       @RequestParam(defaultValue = "") String action,
                                       @RequestParam(defaultValue = "") String actor,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "50") int size) {
        return PageResponse.of(repository.search(q.trim(), action.trim(), actor.trim(), start(from), end(to),
                PageRequest.of(page, Math.min(size, 200))), AuditDto::of);
    }

    @GetMapping("/actions")
    public List<String> actions() {
        return repository.actions();
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(defaultValue = "") String q,
                                         @RequestParam(defaultValue = "") String action,
                                         @RequestParam(defaultValue = "") String actor,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         @RequestParam(defaultValue = "csv") String format) {
        List<List<?>> rows = repository.search(q.trim(), action.trim(), actor.trim(), start(from), end(to),
                        PageRequest.of(0, EXPORT_LIMIT)).stream()
                .<List<?>>map(a -> List.of(a.getCreatedAt().atZone(clock.zone()).toLocalDateTime().withNano(0).toString()
                        .replace('T', ' '), a.getActor(), a.getAction(), a.getDetails() == null ? "" : a.getDetails()))
                .toList();
        String period = (from == null ? "start" : from.toString()) + " to " + (to == null ? clock.today().toString() : to.toString());
        return Pdf.export(format, "audit-trail", "Audit Trail", period, List.of("Time", "User", "Action", "Details"), rows);
    }

    private Instant start(LocalDate from) {
        return from == null ? Instant.EPOCH : clock.startOfDay(from);
    }

    private Instant end(LocalDate to) {
        return to == null ? Instant.now().plusSeconds(60) : clock.startOfDay(to.plusDays(1));
    }
}
