package ph.edu.zscmst.library.privacy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ph.edu.zscmst.library.common.AuditService;
import ph.edu.zscmst.library.common.ApiException;
import ph.edu.zscmst.library.common.LibraryClock;
import ph.edu.zscmst.library.common.SettingsService;
import ph.edu.zscmst.library.common.SystemSettings;
import ph.edu.zscmst.library.digital.FileStorage;
import ph.edu.zscmst.library.patron.Patron;
import ph.edu.zscmst.library.patron.PatronRepository;

/**
 * Data Privacy Act (RA 10173) support: a patron's copy of their data, erasure on request, and
 * retention limits that anonymize or delete old records automatically.
 */
@Service
public class PrivacyService {

    /** Card number of the system record that anonymized history is re-pointed to. */
    public static final String ANONYMIZED_CARD = "__ANONYMIZED__";

    private static final Logger log = LoggerFactory.getLogger(PrivacyService.class);

    public record Retention(int retainLoanYears, int retainAttendanceYears, int retainGateDays, int retainNoticeDays,
                            int retainAuditYears, int anonymizeExpiredYears) {

        static Retention load(SettingsService settings) {
            return new Retention(num(settings, "retainLoanYears"), num(settings, "retainAttendanceYears"),
                    num(settings, "retainGateDays"), num(settings, "retainNoticeDays"),
                    num(settings, "retainAuditYears"), num(settings, "anonymizeExpiredYears"));
        }

        private static int num(SettingsService settings, String key) {
            try {
                return Math.max(0, Integer.parseInt(settings.get("privacy." + key, "0").trim()));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }

    /** Rows affected (or, on a dry run, that would be affected) per kind of record. */
    public record RetentionResult(boolean dryRun, Map<String, Integer> counts) {
    }

    private final JdbcTemplate jdbc;
    private final PatronRepository patrons;
    private final SettingsService settings;
    private final FileStorage storage;
    private final AuditService audit;
    private final LibraryClock clock;
    private final SystemSettings system;

    public PrivacyService(JdbcTemplate jdbc, PatronRepository patrons, SettingsService settings, FileStorage storage,
                          AuditService audit, LibraryClock clock, SystemSettings system) {
        this.system = system;
        this.jdbc = jdbc;
        this.patrons = patrons;
        this.settings = settings;
        this.storage = storage;
        this.audit = audit;
        this.clock = clock;
    }

    public Retention retention() {
        return Retention.load(settings);
    }

    /** Everything the library holds about one patron, as a downloadable JSON document. */
    @Transactional(readOnly = true)
    public Map<String, Object> export(Long patronId) {
        Patron p = patrons.findById(patronId).orElseThrow(() -> ApiException.notFound("Patron"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generatedAt", Instant.now().toString());
        out.put("library", system.profile().name());
        out.put("dataProtectionOfficer", settings.get("privacy.officer", "") + " <" + settings.get("privacy.contact", "") + ">");
        out.put("profile", one("""
                select p.card_number, p.rfid_uid, p.first_name, p.last_name, p.email, p.phone, c.name as category,
                       co.code as course, p.expiry_date, p.blocked, p.block_reason, p.photo_key is not null as has_photo,
                       p.pin_hash is not null as has_pin, p.created_at, p.anonymized_at
                from patron p join patron_category c on c.id = p.category_id left join course co on co.id = p.course_id
                where p.id = ?""", patronId));
        out.put("loans", list("""
                select l.checkout_at, l.due_at, l.returned_at, l.renewals, i.barcode, r.title
                from loan l join item i on i.id = l.item_id join bib_record r on r.id = i.record_id
                where l.patron_id = ? order by l.checkout_at""", patronId));
        out.put("fines", list("""
                select created_at, amount, reason, status, settled_at, receipt_number
                from fine where patron_id = ? order by created_at""", patronId));
        out.put("reservations", list("""
                select h.placed_at, r.title, h.status, h.pickup_date, h.ready_at, h.expires_at, h.closed_at
                from hold_request h join bib_record r on r.id = h.record_id
                where h.patron_id = ? order by h.placed_at""", patronId));
        out.put("libraryVisits", list("""
                select time_in, time_out, method from attendance_log where patron_id = ? order by time_in""", patronId));
        out.put("notices", list("""
                select created_at, type, message, channels, sent_at from notice where patron_id = ? order by created_at""",
                patronId));
        out.put("ebooksOpened", list("""
                select v.viewed_at, r.title from ebook_view v join bib_record r on r.id = v.record_id
                where v.patron_id = ? order by v.viewed_at""", patronId));
        audit.record("PRIVACY_EXPORT", p.getCardNumber());
        return out;
    }

    private Map<String, Object> one(String sql, Object... args) {
        return jdbc.queryForMap(sql, args);
    }

    private List<Map<String, Object>> list(String sql, Object... args) {
        return jdbc.queryForList(sql, args);
    }

    /**
     * Erases a patron's identity (right to erasure / closing an account): personal details, card, RFID,
     * photo and PIN are removed, pending reservations cancelled and notices deleted. Loans and fines stay,
     * no longer identifying anyone, so collection statistics remain correct.
     */
    @Transactional
    public Patron anonymize(Long patronId, String reason) {
        Patron p = patrons.findById(patronId).orElseThrow(() -> ApiException.notFound("Patron"));
        if (ANONYMIZED_CARD.equals(p.getCardNumber())) throw ApiException.badRequest("This is a system record");
        if (p.getAnonymizedAt() != null) throw ApiException.conflict("This patron is already anonymized");
        Integer onLoan = jdbc.queryForObject("select count(*) from loan where patron_id = ? and returned_at is null",
                Integer.class, patronId);
        if (onLoan != null && onLoan > 0) {
            throw ApiException.conflict("The patron still has " + onLoan + " item(s) on loan; check them in first");
        }
        Integer unpaid = jdbc.queryForObject("select count(*) from fine where patron_id = ? and status = 'UNPAID'",
                Integer.class, patronId);
        if (unpaid != null && unpaid > 0) {
            throw ApiException.conflict("The patron has unpaid fines; settle or waive them first");
        }
        String card = p.getCardNumber();
        scrub(p);
        audit.record("PRIVACY_ANONYMIZE", card + " -> " + p.getCardNumber()
                + (reason == null || reason.isBlank() ? "" : " (" + reason.trim() + ")"));
        return p;
    }

    private void scrub(Patron p) {
        if (p.getPhotoKey() != null) storage.delete(p.getPhotoKey());
        p.setPhotoKey(null);
        p.setPhotoUrl(null);
        p.setCardNumber("ANON-" + p.getId());
        p.setRfidUid(null);
        p.setFirstName("Anonymized");
        p.setLastName("Patron #" + p.getId());
        p.setEmail(null);
        p.setPhone(null);
        p.setPinHash(null);
        p.setBlocked(true);
        p.setBlockReason("Anonymized under the Data Privacy Act");
        p.setAnonymizedAt(Instant.now());
        patrons.save(p);
        jdbc.update("""
                update hold_request set status = 'CANCELLED', closed_at = now(), closed_by = 'privacy'
                where patron_id = ? and status in ('SCHEDULED', 'PENDING', 'READY')""", p.getId());
        jdbc.update("delete from notice where patron_id = ?", p.getId());
        jdbc.update("update ebook_view set patron_id = null where patron_id = ?", p.getId());
        // End any My Library session: the portal must not keep showing the erased patron's history.
        jdbc.update("update refresh_token set revoked = true where subject_type = 'PATRON' and subject_id = ? and not revoked", p.getId());
    }

    /** Applies the retention limits (0 = keep forever). A dry run only counts what would change. */
    @Transactional
    public RetentionResult applyRetention(boolean dryRun, String actor) {
        Retention r = retention();
        Long anon = jdbc.queryForObject("select id from patron where card_number = ?", Long.class, ANONYMIZED_CARD);
        Map<String, Integer> counts = new LinkedHashMap<>();
        Instant now = Instant.now();
        if (r.retainLoanYears() > 0) {
            Instant cutoff = yearsAgo(r.retainLoanYears());
            // Settled history only: loans returned before the cutoff with nothing left unpaid on them.
            String loanCond = """
                    returned_at < ? and patron_id <> ?
                    and not exists (select 1 from fine f where f.loan_id = loan.id and f.status = 'UNPAID')""";
            counts.put("loansAnonymized", run(dryRun, "loan", loanCond, "update loan set patron_id = " + anon,
                    cutoff, anon));
            counts.put("finesAnonymized", run(dryRun, "fine", "status <> 'UNPAID' and created_at < ? and patron_id <> ?",
                    "update fine set patron_id = " + anon, cutoff, anon));
            counts.put("reservationsAnonymized", run(dryRun, "hold_request",
                    "status in ('FULFILLED', 'CANCELLED', 'EXPIRED') and placed_at < ? and patron_id <> ?",
                    "update hold_request set patron_id = " + anon, cutoff, anon));
            counts.put("ebookViewsAnonymized", run(dryRun, "ebook_view", "viewed_at < ? and patron_id is not null",
                    "update ebook_view set patron_id = null", cutoff));
        }
        if (r.retainAttendanceYears() > 0) {
            counts.put("visitsAnonymized", run(dryRun, "attendance_log",
                    "time_in < ? and time_out is not null and patron_id <> ?",
                    "update attendance_log set patron_id = " + anon, yearsAgo(r.retainAttendanceYears()), anon));
        }
        if (r.retainGateDays() > 0) {
            counts.put("gateEventsDeleted", run(dryRun, "gate_event", "occurred_at < ?", "delete from gate_event",
                    now.minus(r.retainGateDays(), ChronoUnit.DAYS)));
        }
        if (r.retainNoticeDays() > 0) {
            counts.put("noticesDeleted", run(dryRun, "notice", "created_at < ? and sent_at is not null",
                    "delete from notice", now.minus(r.retainNoticeDays(), ChronoUnit.DAYS)));
        }
        if (r.retainAuditYears() > 0) {
            counts.put("auditEntriesDeleted", run(dryRun, "audit_log", "created_at < ?", "delete from audit_log",
                    yearsAgo(r.retainAuditYears())));
        }
        if (r.anonymizeExpiredYears() > 0) {
            LocalDate cutoff = clock.today().minusYears(r.anonymizeExpiredYears());
            List<Long> ids = jdbc.queryForList("""
                    select p.id from patron p
                    where p.anonymized_at is null and p.expiry_date < ?
                      and not exists (select 1 from loan l where l.patron_id = p.id and l.returned_at is null)
                      and not exists (select 1 from fine f where f.patron_id = p.id and f.status = 'UNPAID')""",
                    Long.class, cutoff);
            if (!dryRun) ids.forEach(id -> scrub(patrons.findById(id).orElseThrow()));
            counts.put("expiredPatronsAnonymized", ids.size());
        }
        if (!dryRun) {
            int total = counts.values().stream().mapToInt(Integer::intValue).sum();
            if (total > 0) audit.record(actor, "PRIVACY_RETENTION", counts.toString());
            settings.put("privacy.lastRun", now.toString());
        }
        return new RetentionResult(dryRun, counts);
    }

    private int run(boolean dryRun, String table, String condition, String action, Object... args) {
        // The PostgreSQL driver binds OffsetDateTime but not Instant.
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof Instant t) args[i] = t.atOffset(java.time.ZoneOffset.UTC);
        }
        if (dryRun) {
            Integer n = jdbc.queryForObject("select count(*) from " + table + " where " + condition, Integer.class, args);
            return n == null ? 0 : n;
        }
        return jdbc.update(action + " where " + condition, args);
    }

    private Instant yearsAgo(int years) {
        return clock.today().minusYears(years).atStartOfDay(clock.zone()).toInstant();
    }

    /** Nightly at 02:30, library time. Nothing happens while every limit is 0 (keep forever). */
    @Scheduled(cron = "0 30 2 * * *", zone = "${app.library.timezone}")
    @Transactional
    public void nightly() {
        RetentionResult result = applyRetention(false, "system");
        if (result.counts().values().stream().anyMatch(n -> n > 0)) log.info("Privacy retention: {}", result.counts());
    }
}
