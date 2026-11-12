package ph.edu.zscmst.library.printlog;

import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ph.edu.zscmst.library.common.AuditService;

/** Records every printed document, numbering copies of the same document (same kind and reference). */
@Service
public class PrintLogService {

    /** What was printed. Documents with a reference (receipt, clearance, ID card) are numbered copy 1, 2, … */
    public enum Kind {
        ID_CARD("Library ID"), CARD_SLIP("Card slip"), PIN_SLIP("PIN slip"), CLEARANCE("Library clearance"),
        CHECKOUT_SLIP("Borrowing slip"), RETURN_SLIP("Return slip"), HOLD_SLIP("Hold shelf slip"),
        RECEIPT("Official receipt"), CASH_REPORT("End-of-day report");

        public final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    public record Entry(Long id, Kind kind, String label, Long patronId, String patronName, String cardNumber,
                        String reference, String details, int copy, String printedBy, Instant printedAt) {
    }

    static final String SELECT = """
            select l.id, l.kind, l.patron_id, p.first_name || ' ' || p.last_name as patron_name, p.card_number,
                   l.reference, l.details, l.copy_number, l.printed_by, l.printed_at
            from print_log l left join patron p on p.id = l.patron_id""";

    static final RowMapper<Entry> ROW = (rs, i) -> {
        Kind kind = Kind.valueOf(rs.getString("kind"));
        long patronId = rs.getLong("patron_id");
        return new Entry(rs.getLong("id"), kind, kind.label, rs.wasNull() ? null : patronId, rs.getString("patron_name"),
                rs.getString("card_number"), rs.getString("reference"), rs.getString("details"), rs.getInt("copy_number"),
                rs.getString("printed_by"), rs.getTimestamp("printed_at").toInstant());
    };

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public PrintLogService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** Logs one printed document in its own transaction, so a print is recorded even from a read-only request. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Entry record(Kind kind, Long patronId, String reference, String details) {
        return record(kind, patronId, reference, details, true);
    }

    /** As above; {@code audited = false} when the caller writes one audit entry for a whole batch. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Entry record(Kind kind, Long patronId, String reference, String details, boolean audited) {
        String ref = reference == null || reference.isBlank() ? null : reference.trim();
        String d = details == null || details.isBlank() ? null : details.length() > 300 ? details.substring(0, 300) : details;
        int copy = 1;
        if (ref != null) {
            // Serialise copies of the same document so two desks reprinting at once don't both get "copy 2".
            jdbc.query("select pg_advisory_xact_lock(hashtext(? || ':' || ?))", rs -> null, kind.name(), ref);
            Integer before = jdbc.queryForObject("select count(*) from print_log where kind = ? and reference = ?",
                    Integer.class, kind.name(), ref);
            copy = (before == null ? 0 : before) + 1;
        }
        String actor = AuditService.currentActor();
        Long id = jdbc.queryForObject("""
                insert into print_log (kind, patron_id, reference, details, copy_number, printed_by)
                values (?, ?, ?, ?, ?, ?) returning id""", Long.class, kind.name(), patronId, ref, d, copy, actor);
        if (audited) audit.record(actor, "PRINTED_" + kind.name(), kind.label + (ref == null ? "" : " " + ref)
                + (copy > 1 ? " (copy " + copy + ")" : "") + (patronId == null ? "" : " patron #" + patronId));
        return jdbc.queryForObject(SELECT + " where l.id = ?", ROW, id);
    }

    public List<Entry> forPatron(Long patronId) {
        return jdbc.query(SELECT + " where l.patron_id = ? order by l.printed_at desc limit 200", ROW, patronId);
    }

    /** Earlier prints of one document, newest first (empty if never printed). */
    public List<Entry> forDocument(Kind kind, String reference) {
        return jdbc.query(SELECT + " where l.kind = ? and l.reference = ? order by l.printed_at desc", ROW, kind.name(), reference);
    }
}
