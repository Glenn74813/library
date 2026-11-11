package ph.edu.zscmst.library.patron;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ph.edu.zscmst.library.common.ApiException;
import ph.edu.zscmst.library.common.AuditService;
import ph.edu.zscmst.library.common.Csv;
import ph.edu.zscmst.library.patron.PatronDtos.ImportResult;
import ph.edu.zscmst.library.patron.PatronDtos.PatronRequest;

@Service
public class PatronService {

    public static final List<String> IMPORT_COLUMNS = List.of("cardNumber", "firstName", "lastName", "email", "phone",
            "category", "courseCode", "expiryDate", "rfidUid");

    private final PatronRepository patrons;
    private final PatronCategoryRepository categories;
    private final CourseRepository courses;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;

    public PatronService(PatronRepository patrons, PatronCategoryRepository categories, CourseRepository courses,
                         PasswordEncoder passwordEncoder, AuditService audit) {
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

    @Transactional(readOnly = true)
    public Patron lookup(String code) {
        return patrons.findByIdentifier(code.trim()).orElseThrow(() -> ApiException.notFound("Patron"));
    }

    @Transactional
    public Patron create(PatronRequest req) {
        Patron p = new Patron(req.cardNumber().trim());
        apply(p, req);
        Patron saved = patrons.save(p);
        audit.record("PATRON_CREATE", saved.getCardNumber() + " " + saved.getFullName());
        return saved;
    }

    @Transactional
    public Patron update(Long id, PatronRequest req) {
        Patron p = get(id);
        p.setCardNumber(req.cardNumber().trim());
        apply(p, req);
        audit.record("PATRON_UPDATE", p.getCardNumber() + " blocked=" + p.isBlocked());
        return p;
    }

    @Transactional
    public void setPin(Long id, String pin) {
        Patron p = get(id);
        p.setPinHash(passwordEncoder.encode(pin));
        p.unlockPin();
        audit.record("PATRON_PIN_SET", p.getCardNumber());
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
