package ph.edu.zscmst.library.report;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ph.edu.zscmst.library.attendance.AttendanceLogRepository;
import ph.edu.zscmst.library.catalog.BibRecordRepository;
import ph.edu.zscmst.library.catalog.Item;
import ph.edu.zscmst.library.catalog.ItemRepository;
import ph.edu.zscmst.library.catalog.ItemStatus;
import ph.edu.zscmst.library.catalog.MaterialType;
import ph.edu.zscmst.library.circulation.Fine;
import ph.edu.zscmst.library.circulation.FineRepository;
import ph.edu.zscmst.library.circulation.FineStatus;
import ph.edu.zscmst.library.circulation.HoldRequestRepository;
import ph.edu.zscmst.library.circulation.HoldStatus;
import ph.edu.zscmst.library.circulation.Loan;
import ph.edu.zscmst.library.circulation.LoanRepository;
import ph.edu.zscmst.library.common.ApiException;
import ph.edu.zscmst.library.common.Pdf;
import ph.edu.zscmst.library.common.LibraryClock;
import ph.edu.zscmst.library.patron.CourseRepository;
import ph.edu.zscmst.library.patron.Patron;
import ph.edu.zscmst.library.patron.PatronCategoryRepository;
import ph.edu.zscmst.library.patron.PatronRepository;

/** Dashboard, statistics and Excel-compatible CSV exports. */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    public record Dashboard(long records, long items, long patrons, long activeLoans, long overdueLoans,
                            long holdsPending, long holdsReady, BigDecimal unpaidFines, long checkoutsToday,
                            long returnsToday, long visitsToday, long insideNow, BigDecimal finesCollectedToday,
                            long itemsMissing, long itemsLost) {
    }

    public record Ranked(Long id, String code, String label, long count) {
    }

    public record CirculationStats(LocalDate from, LocalDate to, long checkouts, long returns,
                                   BigDecimal finesCollected, List<Ranked> topBorrowers, List<Ranked> topTitles) {
    }

    /**
     * Report filters, all optional: patron category and course (who), material type and section (what),
     * copy status (collection and tagging reports) and fine status (fines report). Each report applies the
     * ones that fit it, and exports name the filters used under the title.
     */
    public record Filter(Long categoryId, Long courseId, MaterialType type, String section, ItemStatus itemStatus,
                         FineStatus fineStatus) {
        boolean any() {
            return categoryId != null || courseId != null || type != null || section != null || itemStatus != null
                    || fineStatus != null;
        }

        boolean patron(Patron p) {
            return (categoryId == null || p.getCategory().getId().equals(categoryId))
                    && (courseId == null || (p.getCourse() != null && p.getCourse().getId().equals(courseId)));
        }

        boolean copy(Item i) {
            return (type == null || i.getRecord().getMaterialType() == type)
                    && (section == null || section.equalsIgnoreCase(i.getLocation() == null ? "" : i.getLocation()));
        }

        boolean loan(Loan l) {
            return patron(l.getPatron()) && copy(l.getItem());
        }

        boolean item(Item i) {
            return copy(i) && (itemStatus == null || i.getStatus() == itemStatus);
        }

        boolean fine(Fine f) {
            return patron(f.getPatron()) && (fineStatus == null || f.getStatus() == fineStatus);
        }
    }

    private final BibRecordRepository records;
    private final ItemRepository items;
    private final PatronRepository patrons;
    private final LoanRepository loans;
    private final HoldRequestRepository holds;
    private final FineRepository fines;
    private final AttendanceLogRepository attendance;
    private final LibraryClock clock;
    private final PatronCategoryRepository categoryRepo;
    private final CourseRepository courseRepo;

    public ReportController(BibRecordRepository records, ItemRepository items, PatronRepository patrons,
                            LoanRepository loans, HoldRequestRepository holds, FineRepository fines,
                            AttendanceLogRepository attendance, LibraryClock clock,
                            PatronCategoryRepository categoryRepo, CourseRepository courseRepo) {
        this.categoryRepo = categoryRepo;
        this.courseRepo = courseRepo;
        this.records = records;
        this.items = items;
        this.patrons = patrons;
        this.loans = loans;
        this.holds = holds;
        this.fines = fines;
        this.attendance = attendance;
        this.clock = clock;
    }

    @GetMapping("/dashboard")
    public Dashboard dashboard() {
        Instant start = clock.startOfDay(clock.today());
        Instant end = clock.startOfDay(clock.today().plusDays(1));
        Instant last = lastInstantBefore(end);
        Instant now = Instant.now();
        return new Dashboard(records.count(), items.count(), patrons.countByAnonymizedAtIsNull(), loans.countByReturnedAtIsNull(),
                loans.countByReturnedAtIsNullAndDueAtBefore(now), holds.countByStatus(HoldStatus.PENDING),
                holds.countByStatus(HoldStatus.READY), fines.totalUnpaid(), loans.countByCheckoutAtBetween(start, last),
                loans.countByReturnedAtBetween(start, last), attendance.countByTimeInBetween(start, last),
                attendance.countByTimeInAfterAndTimeOutIsNull(start),
                fines.sumSettled(FineStatus.PAID, start, end), items.countByStatus(ItemStatus.MISSING),
                items.countByStatus(ItemStatus.LOST));
    }

    @GetMapping("/circulation")
    @Transactional(readOnly = true)
    public CirculationStats circulation(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "10") int top,
            @RequestParam(required = false) Long categoryId, @RequestParam(required = false) Long courseId,
            @RequestParam(required = false) MaterialType materialType, @RequestParam(required = false) String section) {
        Filter filter = new Filter(categoryId, courseId, materialType, blankToNull(section), null, null);
        if (filter.any()) return filteredCirculation(from, to, top, filter);
        LocalDate t = to == null ? clock.today() : to;
        LocalDate f = from == null ? t.minusDays(29) : from;
        Instant start = clock.startOfDay(f);
        Instant end = clock.startOfDay(t.plusDays(1));
        Instant last = lastInstantBefore(end);
        PageRequest limit = PageRequest.of(0, Math.clamp(top, 1, 50));
        List<Ranked> borrowers = loans.topBorrowers(start, end, limit).stream()
                .map(r -> new Ranked((Long) r[0], (String) r[1], (String) r[2], ((Number) r[3]).longValue())).toList();
        List<Ranked> titles = loans.topTitles(start, end, limit).stream()
                .map(r -> new Ranked((Long) r[0], null, (String) r[1], ((Number) r[2]).longValue())).toList();
        return new CirculationStats(f, t, loans.countByCheckoutAtBetween(start, last),
                loans.countByReturnedAtBetween(start, last), fines.sumSettled(FineStatus.PAID, start, end),
                borrowers, titles);
    }

    /** Circulation figures for the period counting only the loans and fines that match the filters. */
    private CirculationStats filteredCirculation(LocalDate from, LocalDate to, int top, Filter filter) {
        LocalDate t = to == null ? clock.today() : to;
        LocalDate f = from == null ? t.minusDays(29) : from;
        Instant start = clock.startOfDay(f);
        Instant last = lastInstantBefore(clock.startOfDay(t.plusDays(1)));
        List<Loan> out = loans.findByCheckoutAtBetweenOrderByCheckoutAtAsc(start, last).stream().filter(filter::loan).toList();
        long returns = loans.findByReturnedAtBetween(start, last).stream().filter(filter::loan).count();
        BigDecimal collected = fines.findAll().stream()
                .filter(x -> x.getStatus() == FineStatus.PAID && x.getSettledAt() != null
                        && !x.getSettledAt().isBefore(start) && !x.getSettledAt().isAfter(last))
                .filter(x -> filter.patron(x.getPatron()) && (x.getLoan() == null || filter.copy(x.getLoan().getItem())))
                .map(Fine::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        int n = Math.clamp(top, 1, 50);
        List<Ranked> borrowers = out.stream().collect(java.util.stream.Collectors.groupingBy(Loan::getPatron,
                        java.util.stream.Collectors.counting())).entrySet().stream()
                .sorted(java.util.Map.Entry.<Patron, Long>comparingByValue().reversed()).limit(n)
                .map(e -> new Ranked(e.getKey().getId(), e.getKey().getCardNumber(), e.getKey().getFullName(), e.getValue())).toList();
        List<Ranked> titles = out.stream().collect(java.util.stream.Collectors.groupingBy(l -> l.getItem().getRecord(),
                        java.util.stream.Collectors.counting())).entrySet().stream()
                .sorted(java.util.Map.Entry.<ph.edu.zscmst.library.catalog.BibRecord, Long>comparingByValue().reversed()).limit(n)
                .map(e -> new Ranked(e.getKey().getId(), null, e.getKey().getTitle(), e.getValue())).toList();
        return new CirculationStats(f, t, out.size(), returns, collected, borrowers, titles);
    }

    /**
     * Excel, CSV or PDF exports: loans, overdue, fines, collection, tagging, patrons. Optional filters
     * narrow each report (see {@link Filter}); the filters used are named under the export's title.
     */
    @GetMapping("/export/{type}")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> export(@PathVariable String type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "csv") String format,
            @RequestParam(required = false) Long categoryId, @RequestParam(required = false) Long courseId,
            @RequestParam(required = false) MaterialType materialType, @RequestParam(required = false) String section,
            @RequestParam(required = false) ItemStatus itemStatus, @RequestParam(required = false) FineStatus fineStatus) {
        Filter flt = new Filter(categoryId, courseId, materialType, blankToNull(section), itemStatus, fineStatus);
        String note = filterNote(flt);
        LocalDate t = to == null ? clock.today() : to;
        LocalDate f = from == null ? t.minusDays(29) : from;
        return switch (type) {
            case "loans" -> Pdf.export(format, "loans-" + f + "-to-" + t, "Loans", f + " to " + t + note,
                    List.of("Checkout", "Due", "Returned", "Barcode", "Title", "ID Number", "Patron", "Renewals"),
                    loans.findByCheckoutAtBetweenOrderByCheckoutAtAsc(clock.startOfDay(f), lastInstantBefore(clock.startOfDay(t.plusDays(1))))
                            .stream().filter(flt::loan).map(this::loanRow).toList());
            case "overdue" -> Pdf.export(format, "overdue-" + clock.today(), "Overdue Loans", "As of " + clock.today() + note,
                    List.of("Checkout", "Due", "Returned", "Barcode", "Title", "ID Number", "Patron", "Renewals"),
                    loans.findByReturnedAtIsNullAndDueAtBefore(Instant.now()).stream().filter(flt::loan).map(this::loanRow).toList());
            case "fines" -> Pdf.export(format, "fines-" + clock.today(), "Fines", note.isEmpty() ? null : note.substring(3),
                    List.of("Date", "ID Number", "Patron", "Amount", "Reason", "Status", "Receipt", "Settled By"),
                    fines.findAll().stream().filter(flt::fine).map(this::fineRow).toList());
            case "collection" -> Pdf.export(format, "collection-" + clock.today(), "Collection", note.isEmpty() ? null : note.substring(3),
                    List.of("Barcode", "RFID EPC", "Title", "Authors", "ISBN", "Call Number", "Type", "Location",
                            "Aisle", "Shelf", "Status", "Acquired", "Price"),
                    items.findAll().stream().filter(flt::item).map(this::itemRow).toList());
            case "tagging" -> tagging(format, flt, note);
            case "patrons" -> Pdf.export(format, "patrons-" + clock.today(), "Patrons", note.isEmpty() ? null : note.substring(3),
                    List.of("ID Number", "Last Name", "First Name", "Category", "Course", "Email", "Phone",
                            "Expiry", "Blocked"),
                    patrons.findAll().stream().filter(p -> p.getAnonymizedAt() == null).filter(flt::patron).map(this::patronRow).toList());
            default -> throw ApiException.notFound("Report type '" + type + "'");
        };
    }

    private String date(Instant i) {
        return i == null ? "" : i.atZone(clock.zone()).toLocalDateTime().withNano(0).toString().replace('T', ' ');
    }

    private List<?> loanRow(Loan l) {
        return Arrays.asList(date(l.getCheckoutAt()), date(l.getDueAt()), date(l.getReturnedAt()),
                l.getItem().getBarcode(), l.getItem().getRecord().getTitle(), l.getPatron().getCardNumber(),
                l.getPatron().getFullName(), l.getRenewals());
    }

    private List<?> fineRow(Fine x) {
        return Arrays.asList(date(x.getCreatedAt()), x.getPatron().getCardNumber(), x.getPatron().getFullName(),
                x.getAmount(), x.getReason(), x.getStatus(), x.getReceiptNumber(), x.getSettledBy());
    }

    /**
     * Tagging report: every copy (withdrawn ones left out) in shelf order, whether it has an RFID tag yet,
     * with the tagged / untagged counts in the subtitle, so tagging can be planned aisle by aisle.
     */
    private ResponseEntity<byte[]> tagging(String format, Filter flt, String note) {
        List<Item> list = items.findAll().stream().filter(i -> i.getStatus() != ItemStatus.WITHDRAWN).filter(flt::item)
                .sorted(java.util.Comparator.comparing((Item i) -> i.getLocation() == null ? "~" : i.getLocation())
                        .thenComparing(i -> i.getAisle() == null ? "" : i.getAisle())
                        .thenComparing(i -> i.getShelf() == null ? "" : i.getShelf())
                        .thenComparing(i -> i.getRecord().getCallNumber() == null ? "" : i.getRecord().getCallNumber())
                        .thenComparing(Item::getBarcode))
                .toList();
        long tagged = list.stream().filter(i -> i.getRfidEpc() != null && !i.getRfidEpc().isBlank()).count();
        String summary = tagged + " of " + list.size() + " copies tagged"
                + (list.isEmpty() ? "" : " (" + Math.round(100.0 * tagged / list.size()) + "%)") + ", "
                + (list.size() - tagged) + " not tagged yet · as of " + clock.today() + note;
        return Pdf.export(format, "tagging-" + clock.today(), "RFID Tagging Report", summary,
                List.of("Section", "Aisle", "Shelf", "Call Number", "Title", "Barcode", "RFID EPC", "Tagged", "Status"),
                list.stream().<List<?>>map(i -> Arrays.<Object>asList(i.getLocation(), i.getAisle(), i.getShelf(), i.getRecord().getCallNumber(),
                        i.getRecord().getTitle(), i.getBarcode(), i.getRfidEpc(),
                        i.getRfidEpc() != null && !i.getRfidEpc().isBlank() ? "Yes" : "No", i.getStatus())).toList());
    }

    private List<?> itemRow(Item i) {
        var r = i.getRecord();
        return Arrays.asList(i.getBarcode(), i.getRfidEpc(), r.getTitle(), r.getAuthors(), r.getIsbn(),
                r.getCallNumber(), r.getMaterialType(), i.getLocation(), i.getAisle(), i.getShelf(), i.getStatus(),
                i.getAcquiredDate(),
                i.getPrice());
    }

    private List<?> patronRow(Patron p) {
        return Arrays.asList(p.getCardNumber(), p.getLastName(), p.getFirstName(), p.getCategory().getName(),
                p.getCourse() == null ? null : p.getCourse().getCode(), p.getEmail(), p.getPhone(),
                p.getExpiryDate(), p.isBlocked());
    }

    /** " · Category: Student · Section: Fiction Section" for the filters used, or "" when none. */
    private String filterNote(Filter f) {
        List<String> parts = new java.util.ArrayList<>();
        if (f.categoryId() != null) parts.add("Category: " + categoryRepo.findById(f.categoryId()).map(c -> c.getName()).orElse("#" + f.categoryId()));
        if (f.courseId() != null) parts.add("Course: " + courseRepo.findById(f.courseId()).map(c -> c.getCode()).orElse("#" + f.courseId()));
        if (f.type() != null) parts.add("Type: " + f.type());
        if (f.section() != null) parts.add("Section: " + f.section());
        if (f.itemStatus() != null) parts.add("Copy status: " + f.itemStatus());
        if (f.fineStatus() != null) parts.add("Fine status: " + f.fineStatus());
        return parts.isEmpty() ? "" : " · " + String.join(" · ", parts);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Spring Data's "Between" includes both ends: stop just before the next midnight so days don't overlap. */
    private static Instant lastInstantBefore(Instant end) {
        return end.minusNanos(1000);
    }
}
