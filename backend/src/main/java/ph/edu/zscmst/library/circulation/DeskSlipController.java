package ph.edu.zscmst.library.circulation;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ph.edu.zscmst.library.common.ApiException;
import ph.edu.zscmst.library.common.AuditService;
import ph.edu.zscmst.library.common.LibraryClock;
import ph.edu.zscmst.library.patron.Patron;
import ph.edu.zscmst.library.patron.PatronRepository;

/** Data for slips printed at the circulation desk: library clearances and the end-of-day cash report. */
@RestController
public class DeskSlipController {

    /** Why a patron can't be cleared yet (empty = cleared). */
    public record ClearanceCheck(boolean cleared, List<String> reasons) {
    }

    public record ClearanceRequest(@Size(max = 120) String purpose) {
    }

    public record Clearance(String reference, String patronName, String cardNumber, String category, String course,
                            String purpose, Instant issuedAt, String issuedBy, boolean stillClear) {
    }

    public record CashReceipt(String receiptNumber, Instant paidAt, String patronName, String cardNumber,
                              BigDecimal amount, String cashier, int fines) {
    }

    public record CashierTotal(String cashier, int receipts, BigDecimal total) {
    }

    public record CashReport(LocalDate date, List<CashReceipt> receipts, BigDecimal total, List<CashierTotal> byCashier,
                             int waivedCount, BigDecimal waivedTotal, Instant generatedAt) {
    }

    private final JdbcTemplate jdbc;
    private final PatronRepository patrons;
    private final LibraryClock clock;
    private final AuditService audit;

    public DeskSlipController(JdbcTemplate jdbc, PatronRepository patrons, LibraryClock clock, AuditService audit) {
        this.jdbc = jdbc;
        this.patrons = patrons;
        this.clock = clock;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- clearance

    @GetMapping("/api/patrons/{id}/clearance-check")
    public ClearanceCheck check(@PathVariable Long id) {
        patrons.findById(id).orElseThrow(() -> ApiException.notFound("Patron"));
        return new ClearanceCheck(reasons(id).isEmpty(), reasons(id));
    }

    private List<String> reasons(Long patronId) {
        List<String> out = new ArrayList<>();
        Integer loans = jdbc.queryForObject("select count(*) from loan where patron_id = ? and returned_at is null",
                Integer.class, patronId);
        if (loans != null && loans > 0) out.add(loans + " item(s) still on loan");
        BigDecimal unpaid = jdbc.queryForObject("select coalesce(sum(amount), 0) from fine where patron_id = ? and status = 'UNPAID'",
                BigDecimal.class, patronId);
        if (unpaid != null && unpaid.signum() > 0) out.add("Unpaid fines of PHP " + unpaid.setScale(2));
        return out;
    }

    /** Issues a clearance with a reference number, only when nothing is on loan and no fines are unpaid. */
    @PostMapping("/api/patrons/{id}/clearance")
    @Transactional
    public Clearance issue(@PathVariable Long id, @Valid @RequestBody(required = false) ClearanceRequest req) {
        // Locked like a checkout, so a loan or fine can't slip in between the check and the clearance.
        Patron p = patrons.findByIdForUpdate(id).orElseThrow(() -> ApiException.notFound("Patron"));
        List<String> blocking = reasons(id);
        if (!blocking.isEmpty()) throw ApiException.conflict("Not cleared: " + String.join("; ", blocking));
        String purpose = req == null || req.purpose() == null || req.purpose().isBlank() ? null : req.purpose().trim();
        Long seq = jdbc.queryForObject("""
                insert into clearance (reference, patron_id, purpose, issued_by) values ('pending', ?, ?, ?) returning id""",
                Long.class, id, purpose, AuditService.currentActor());
        String reference = "CLR-" + clock.today().getYear() + "-" + String.format("%06d", seq);
        jdbc.update("update clearance set reference = ? where id = ?", reference, seq);
        audit.record("CLEARANCE_ISSUED", reference + " for " + p.getCardNumber() + (purpose == null ? "" : " (" + purpose + ")"));
        return verify(reference);
    }

    /** Checks a clearance someone presents: who it was issued to, when, and whether they are still clear. */
    @GetMapping("/api/clearances/{reference}")
    @Transactional(readOnly = true)
    public Clearance verify(@PathVariable String reference) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select c.reference, c.purpose, c.issued_at, c.issued_by, p.id as patron_id, p.first_name, p.last_name,
                       p.card_number, cat.name as category, co.code as course
                from clearance c join patron p on p.id = c.patron_id join patron_category cat on cat.id = p.category_id
                left join course co on co.id = p.course_id where upper(c.reference) = upper(?)""", reference.trim());
        if (rows.isEmpty()) throw ApiException.notFound("Clearance " + reference.trim());
        Map<String, Object> r = rows.getFirst();
        return new Clearance((String) r.get("reference"), r.get("first_name") + " " + r.get("last_name"),
                (String) r.get("card_number"), (String) r.get("category"), (String) r.get("course"), (String) r.get("purpose"),
                ((Timestamp) r.get("issued_at")).toInstant(), (String) r.get("issued_by"),
                reasons(((Number) r.get("patron_id")).longValue()).isEmpty());
    }

    // ---------------------------------------------------------------- cash report

    /** Fines collected on one day (library time), receipt by receipt, with totals per cashier. */
    @GetMapping("/api/fines/cash-report")
    @Transactional(readOnly = true)
    public CashReport cashReport(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate day = date == null ? clock.today() : date;
        Timestamp from = Timestamp.from(clock.startOfDay(day));
        Timestamp to = Timestamp.from(clock.startOfDay(day.plusDays(1)));
        List<CashReceipt> receipts = jdbc.query("""
                select f.receipt_number, min(f.settled_at), p.first_name || ' ' || p.last_name, p.card_number,
                       sum(f.amount), min(f.settled_by), count(*)
                from fine f join patron p on p.id = f.patron_id
                where f.status = 'PAID' and f.settled_at >= ? and f.settled_at < ?
                group by f.receipt_number, p.first_name, p.last_name, p.card_number order by min(f.settled_at)""",
                (rs, i) -> new CashReceipt(rs.getString(1), rs.getTimestamp(2).toInstant(), rs.getString(3), rs.getString(4),
                        rs.getBigDecimal(5), rs.getString(6), rs.getInt(7)), from, to);
        List<CashierTotal> byCashier = jdbc.query("""
                select settled_by, count(distinct receipt_number), sum(amount) from fine
                where status = 'PAID' and settled_at >= ? and settled_at < ? group by settled_by order by settled_by""",
                (rs, i) -> new CashierTotal(rs.getString(1), rs.getInt(2), rs.getBigDecimal(3)), from, to);
        Map<String, Object> waived = jdbc.queryForMap("""
                select count(*) as n, coalesce(sum(amount), 0) as total from fine
                where status = 'WAIVED' and settled_at >= ? and settled_at < ?""", from, to);
        BigDecimal total = receipts.stream().map(CashReceipt::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CashReport(day, receipts, total, byCashier, ((Number) waived.get("n")).intValue(),
                (BigDecimal) waived.get("total"), Instant.now());
    }
}
