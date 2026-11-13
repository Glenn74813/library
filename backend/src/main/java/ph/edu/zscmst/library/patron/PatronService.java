package ph.edu.zscmst.library.patron;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ph.edu.zscmst.library.common.ApiException;
import ph.edu.zscmst.library.common.AuditService;
import ph.edu.zscmst.library.common.Csv;
import ph.edu.zscmst.library.common.LibraryClock;
import ph.edu.zscmst.library.patron.PatronDtos.ImportResult;
import ph.edu.zscmst.library.patron.PatronDtos.PatronRequest;
import ph.edu.zscmst.library.privacy.PrivacyService;
import ph.edu.zscmst.library.security.TokenService;
import ph.edu.zscmst.library.transfer.ImportRunLog;

@Service
public class PatronService {

    public static final List<String> IMPORT_COLUMNS = List.of("cardNumber", "firstName", "lastName", "email", "phone",
            "category", "courseCode", "expiryDate", "rfidUid");

    private final PatronRepository patrons;
    private final PatronCategoryRepository categories;
    private final CourseRepository courses;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;
    private final TokenService tokens;
    private final JdbcTemplate jdbc;
    private final LibraryClock clock;
    private final ImportRunLog runs;

    public PatronService(PatronRepository patrons, PatronCategoryRepository categories, CourseRepository courses,
                         PasswordEncoder passwordEncoder, AuditService audit, TokenService tokens,
                         JdbcTemplate jdbc, LibraryClock clock, ImportRunLog runs) {
        this.runs = runs;
        this.jdbc = jdbc;
        this.clock = clock;
        this.tokens = tokens;
        this.patrons = patrons;
        this.categories = categories;
        this.courses = courses;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Page<Patron> search(String q, Pageable pageable) {
        return patrons.search(q == null ? "" : q.trim(), pageable);
    }

    @Transactional(readOnly = true)
    public Patron get(Long id) {
        return patrons.findById(id).orElseThrow(() -> ApiException.notFound("Patron"));
    }

    /** Anonymized patrons (and the system record holding anonymized history) can't be edited back. */
    private Patron editable(Long id) {
        Patron p = get(id);
        if (p.getAnonymizedAt() != null || PrivacyService.ANONYMIZED_CARD.equals(p.getCardNumber())) {
            throw ApiException.conflict("This record was anonymized under the Data Privacy Act and can't be changed");
        }
        return p;
    }

    @Transactional(readOnly = true)
    public Patron lookup(String code) {
        return patrons.findByIdentifier(code.trim()).orElseThrow(() -> ApiException.notFound("Patron"));
    }

    @Transactional
    public Patron create(PatronRequest req) {
        String card = req.cardNumber() == null || req.cardNumber().isBlank() ? nextCardNumber() : req.cardNumber().trim();
        Patron p = new Patron(card);
        apply(p, req);
        checkCodesFree(p);
        Patron saved = patrons.save(p);
        audit.record("PATRON_CREATE", saved.getCardNumber() + " " + saved.getFullName());
        return saved;
    }

    @Transactional
    public Patron update(Long id, PatronRequest req) {
        Patron p = editable(id);
        // The ID number is printed on the patron's library ID: it only changes when a new one is given.
        if (req.cardNumber() != null && !req.cardNumber().isBlank()) p.setCardNumber(req.cardNumber().trim());
        apply(p, req);
        checkCodesFree(p);
        audit.record("PATRON_UPDATE", p.getCardNumber() + " blocked=" + p.isBlocked());
        return p;
    }

    @Transactional
    public void setPin(Long id, String pin) {
        Patron p = editable(id);
        p.setPinHash(passwordEncoder.encode(pin));
        p.unlockPin();
        // A new PIN from the desk (e.g. the old one was shared) ends the patron's existing sessions.
        tokens.revokeAll(TokenService.SubjectType.PATRON, p.getId());
        audit.record("PATRON_PIN_SET", p.getCardNumber());
    }

    /**
     * The next library ID number for a patron registered without one: this year and the next free number
     * (2026-0001, 2026-0002, ...), following the numbers already used this year. Two desks registering at
     * the same moment take turns, so they never get the same number.
     */
    private String nextCardNumber() {
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtext('patron-card-number'))::text", String.class);
        String year = String.valueOf(clock.today().getYear());
        Integer max = jdbc.queryForObject("""
                select coalesce(max(substring(card_number from 6)::int), 0) from patron
                where card_number ~ ('^' || ? || '-[0-9]{1,8}$')""", Integer.class, year);
        for (int n = (max == null ? 0 : max) + 1; ; n++) {
            String card = year + "-" + String.format("%04d", n);
            if (patrons.idsUsingCode(card).isEmpty()) return card;
        }
    }

    /** A card number or RFID UID may not match (in any case) another patron's card or RFID: scans must find one patron. */
    private void checkCodesFree(Patron p) {
        for (String code : new String[] {p.getCardNumber(), p.getRfidUid()}) {
            if (code != null && patrons.idsUsingCode(code).stream().anyMatch(id -> !id.equals(p.getId()))) {
                throw ApiException.conflict("Another patron already uses the code " + code + " (as ID number or RFID)");
            }
        }
    }

    private void apply(Patron p, PatronRequest req) {
        p.setRfidUid(blankToNull(req.rfidUid()));
        p.setFirstName(req.firstName().trim());
        p.setLastName(req.lastName().trim());
        p.setEmail(blankToNull(req.email()));
        p.setPhone(blankToNull(req.phone()));
        p.setCategory(categories.findById(req.categoryId()).orElseThrow(() -> ApiException.notFound("Category")));
        p.setCourse(req.courseId() == null ? null
                : courses.findById(req.courseId()).orElseThrow(() -> ApiException.notFound("Course")));
        p.setPhotoUrl(blankToNull(req.photoUrl()));
        p.setExpiryDate(req.expiryDate());
        p.setBlocked(req.blocked());
        p.setBlockReason(req.blocked() ? blankToNull(req.blockReason()) : null);
    }

    /**
     * Bulk import/upsert keyed on card number. Expected header:
     * cardNumber,firstName,lastName,email,phone,category,courseCode,expiryDate,rfidUid
     */
    @Transactional
    public ImportResult importCsv(String text) {
        return importCsv(null, text);
    }

    @Transactional
    public ImportResult importCsv(String filename, String text) {
        List<List<String>> rows = Csv.parse(text);
        int created = 0;
        int updated = 0;
        List<String> errors = new ArrayList<>();
        if (rows.isEmpty()) {
            return new ImportResult(0, 0, List.of("File is empty"));
        }
        List<String> header = rows.getFirst().stream().map(String::trim).toList();
        for (String col : List.of("cardNumber", "firstName", "lastName", "category")) {
            if (!header.contains(col)) {
                return new ImportResult(0, 0, List.of("Missing required column: " + col));
            }
        }
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            int line = i + 1;
            try {
                String card = col(header, row, "cardNumber");
                if (card == null) throw new IllegalArgumentException("cardNumber is required");
                String categoryName = col(header, row, "category");
                PatronCategory category = categories.findByNameIgnoreCase(categoryName == null ? "" : categoryName)
                        .orElseThrow(() -> new IllegalArgumentException("unknown category '" + categoryName + "'"));
                String courseCode = col(header, row, "courseCode");
                Course course = null;
                if (courseCode != null) {
                    course = courses.findByCodeIgnoreCase(courseCode)
                            .orElseGet(() -> courses.save(new Course(courseCode.toUpperCase(), courseCode.toUpperCase())));
                }
                Patron p = patrons.findByCardNumberIgnoreCase(card).orElse(null);
                boolean isNew = p == null;
                if (isNew) p = new Patron(card);
                String first = col(header, row, "firstName");
                String last = col(header, row, "lastName");
                if (first == null || last == null) throw new IllegalArgumentException("firstName and lastName are required");
                p.setFirstName(first);
                p.setLastName(last);
                p.setEmail(col(header, row, "email"));
                p.setPhone(col(header, row, "phone"));
                p.setCategory(category);
                p.setCourse(course);
                String expiry = col(header, row, "expiryDate");
                p.setExpiryDate(expiry == null ? null : LocalDate.parse(expiry));
                String rfid = col(header, row, "rfidUid");
                if (rfid != null) p.setRfidUid(rfid);
                patrons.saveAndFlush(p);
                if (isNew) created++;
                else updated++;
            } catch (RuntimeException e) {
                errors.add("Line " + line + ": " + e.getMessage());
                if (errors.size() > 200) break;
            }
        }
        if (!errors.isEmpty()) {
            // All-or-nothing keeps the patron table consistent; the caller fixes the file and retries.
            throw ApiException.badRequest(
                    "Import rejected, nothing was saved. " + String.join(" | ", errors.stream().limit(20).toList()));
        }
        audit.record("PATRON_IMPORT", "created=" + created + " updated=" + updated);
        runs.record("PATRONS", filename, rows.size() - 1, created, updated, 0, 0, List.of());
        return new ImportResult(created, updated, errors);
    }

    private static String col(List<String> header, List<String> row, String name) {
        int idx = header.indexOf(name);
        if (idx < 0 || idx >= row.size()) return null;
        return blankToNull(row.get(idx));
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
