package ph.edu.zscmst.library.printlog;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ph.edu.zscmst.library.common.ApiException;
import ph.edu.zscmst.library.common.LibraryClock;
import ph.edu.zscmst.library.common.PageResponse;
import ph.edu.zscmst.library.patron.Patron;
import ph.edu.zscmst.library.patron.PatronRepository;
import ph.edu.zscmst.library.printlog.PrintLogService.Entry;
import ph.edu.zscmst.library.printlog.PrintLogService.Kind;

/** Print log: the desk and kiosks report each slip they print; staff see it per patron and for the whole library. */
@RestController
public class PrintLogController {

    public record PrintRequest(@NotNull Kind kind, Long patronId, @Size(max = 40) String cardNumber,
                               @Size(max = 60) String reference, @Size(max = 300) String details) {
    }

    private final PrintLogService log;
    private final PatronRepository patrons;
    private final JdbcTemplate jdbc;
    private final LibraryClock clock;

    public PrintLogController(PrintLogService log, PatronRepository patrons, JdbcTemplate jdbc, LibraryClock clock) {
        this.log = log;
        this.patrons = patrons;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @PostMapping("/api/print-log")
    public Entry record(@Valid @RequestBody PrintRequest req) {
        return log.record(req.kind(), patronId(req), req.reference(), req.details());
    }

    /** Self-checkout kiosks may only log the borrowing slips they print. */
    @PostMapping("/api/kiosk/print-log")
    public Entry recordFromKiosk(@Valid @RequestBody PrintRequest req) {
        if (req.kind() != Kind.CHECKOUT_SLIP) throw ApiException.badRequest("Kiosks only print borrowing slips");
        return log.record(req.kind(), patronId(req), req.reference(), req.details());
    }

    @GetMapping("/api/patrons/{id}/prints")
    public List<Entry> forPatron(@PathVariable Long id) {
        return log.forPatron(id);
    }

    /** Earlier copies of one document, e.g. before reprinting an official receipt. */
    @GetMapping("/api/print-log/document")
    public List<Entry> forDocument(@RequestParam Kind kind, @RequestParam String reference) {
        return log.forDocument(kind, reference.trim());
    }

    @GetMapping("/api/print-log")
    public PageResponse<Entry> list(@RequestParam(defaultValue = "") String q,
                                    @RequestParam(required = false) Kind kind,
                                    @RequestParam(defaultValue = "false") boolean reprintsOnly,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "50") int size) {
        StringBuilder where = new StringBuilder(" where true");
        List<Object> args = new ArrayList<>();
        if (!q.isBlank()) {
            where.append(" and (l.reference ilike ? or l.details ilike ? or l.printed_by ilike ? or p.card_number ilike ?"
                    + " or (p.first_name || ' ' || p.last_name) ilike ?)");
            String like = "%" + q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            for (int i = 0; i < 5; i++) args.add(like);
        }
        if (kind != null) {
            where.append(" and l.kind = ?");
            args.add(kind.name());
        }
        if (reprintsOnly) where.append(" and l.copy_number > 1");
        if (from != null) {
            where.append(" and l.printed_at >= ?");
            args.add(Timestamp.from(clock.startOfDay(from)));
        }
        if (to != null) {
            where.append(" and l.printed_at < ?");
            args.add(Timestamp.from(clock.startOfDay(to.plusDays(1))));
        }
        int pageSize = Math.clamp(size, 1, 200);
        int pageNo = Math.max(page, 0);
        Long total = jdbc.queryForObject("select count(*) from print_log l left join patron p on p.id = l.patron_id" + where,
                Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageSize);
        pageArgs.add((long) pageNo * pageSize);
        List<Entry> rows = jdbc.query(PrintLogService.SELECT + where + " order by l.printed_at desc, l.id desc limit ? offset ?",
                PrintLogService.ROW, pageArgs.toArray());
        long n = total == null ? 0 : total;
        return new PageResponse<>(rows, pageNo, pageSize, n, (int) ((n + pageSize - 1) / pageSize));
    }

    private Long patronId(PrintRequest req) {
        if (req.patronId() != null) {
            patrons.findById(req.patronId()).orElseThrow(() -> ApiException.notFound("Patron"));
            return req.patronId();
        }
        if (req.cardNumber() == null || req.cardNumber().isBlank()) return null;
        return patrons.findByIdentifier(req.cardNumber().trim()).map(Patron::getId).orElse(null);
    }
}
