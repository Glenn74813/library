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
import ph.edu.zscmst.library.common.ReportTable;
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

        /** Material type and section match the borrowed copy, so they leave out fines not tied to a loan. */
        boolean fine(Fine f) {
            return patron(f.getPatron()) && (fineStatus == null || f.getStatus() == fineStatus)
                    && ((type == null && section == null) || (f.getLoan() != null && copy(f.getLoan().getItem())));
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
     * narrow each report (see {@link Filter}); the period and filters used are named under the export's title.
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
        return table(type, from, to, new Filter(categoryId, courseId, materialType, blankToNull(section), itemStatus, fineStatus))
                .download(format);
    }

    /** The first rows of an export, as shown on the Reports page before downloading. */
    @GetMapping("/export/{type}/preview")
    @Transactional(readOnly = true)
    public ReportTable.Preview preview(@PathVariable String type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long categoryId, @RequestParam(required = false) Long courseId,
            @RequestParam(required = false) MaterialType materialType, @RequestParam(required = false) String section,
            @RequestParam(required = false) ItemStatus itemStatus, @RequestParam(required = false) FineStatus fineStatus) {
        return table(type, from, to, new Filter(categoryId, courseId, materialType, blankToNull(section), itemStatus, fineStatus))
                .preview();
    }

    private ReportTable table(String type, LocalDate from, LocalDate to, Filter flt) {
        String note = filterNote(flt);
        LocalDate t = to == null ? clock.today() : to;
        LocalDate f = from == null ? t.minusDays(29) : from;
        String period = dayLabel(f) + " to " + dayLabel(t);
        List<String> loanHeader = List.of("Checkout", "Due", "Returned", "Barcode", "Title", "ID Number", "Patron", "Renewals");
        return switch (type) {
            case "loans" -> {
                List<List<?>> rows = loans.findByCheckoutAtBetweenOrderByCheckoutAtAsc(clock.startOfDay(f), lastInstantBefore(clock.startOfDay(t.plusDays(1))))
                        .stream().filter(flt::loan).map(this::loanRow).toList();
                yield new ReportTable("loans-" + f + "-to-" + t, "Loans", "Borrowed " + period + " · " + rows.size() + " loan(s)" + note,
                        loanHeader, rows);
            }
            case "overdue" -> {
                List<List<?>> rows = loans.findByReturnedAtIsNullAndDueAtBefore(Instant.now()).stream().filter(flt::loan).map(this::loanRow).toList();
                yield new ReportTable("overdue-" + clock.today(), "Overdue Loans",
                        "Not returned and past due as of " + dayLabel(clock.today()) + " · " + rows.size() + " loan(s)" + note, loanHeader, rows);
            }
            case "fines" -> {
                // Without a period (older links) every fine is listed; the Reports page always sends one.
                Instant start = from == null ? Instant.EPOCH : clock.startOfDay(f);
                Instant end = to == null ? Instant.MAX : clock.startOfDay(t.plusDays(1));
                List<Fine> list = fines.findAll().stream().filter(flt::fine)
                        .filter(x -> !x.getCreatedAt().isBefore(start) && x.getCreatedAt().isBefore(end))
                        .sorted(java.util.Comparator.comparing(Fine::getCreatedAt)).toList();
                BigDecimal charged = list.stream().map(Fine::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal paid = list.stream().filter(x -> x.getStatus() == FineStatus.PAID).map(Fine::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal unpaid = list.stream().filter(x -> x.getStatus() == FineStatus.UNPAID).map(Fine::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
                yield new ReportTable("fines-" + (from == null && to == null ? clock.today().toString() : f + "-to-" + t), "Fines & Payments",
                        (from == null && to == null ? "All dates" : "Charged " + period) + " · " + list.size() + " fine(s), "
                                + money(charged) + " charged, " + money(paid) + " paid, " + money(unpaid) + " unpaid" + note,
                        List.of("Date", "ID Number", "Patron", "Amount", "Reason", "Status", "Receipt", "Settled By"),
                        list.stream().map(this::fineRow).toList());
            }
            case "collection" -> {
                List<List<?>> rows = items.findAll().stream().filter(flt::item)
                        .sorted(java.util.Comparator.comparing((Item i) -> i.getRecord().getTitle(), String.CASE_INSENSITIVE_ORDER)
                                .thenComparing(Item::getBarcode))
                        .map(this::itemRow).toList();
                yield new ReportTable("collection-" + clock.today(), "Collection / Shelf List",
                        rows.size() + " cop" + (rows.size() == 1 ? "y" : "ies") + " as of " + dayLabel(clock.today()) + note,
                        List.of("Barcode", "RFID EPC", "Title", "Authors", "ISBN", "Call Number", "Type", "Section",
                                "Aisle", "Shelf", "Status", "Acquired", "Price"), rows);
            }
            case "tagging" -> tagging(flt, note);
            case "patrons" -> {
                List<List<?>> rows = patrons.findAll().stream().filter(p -> p.getAnonymizedAt() == null).filter(flt::patron)
                        .filter(p -> !p.getCardNumber().startsWith("__"))
                        .sorted(java.util.Comparator.comparing(Patron::getLastName, String.CASE_INSENSITIVE_ORDER)
                                .thenComparing(Patron::getFirstName, String.CASE_INSENSITIVE_ORDER))
                        .map(this::patronRow).toList();
                yield new ReportTable("patrons-" + clock.today(), "Patron List", rows.size() + " patron(s) as of " + dayLabel(clock.today()) + note,
                        List.of("ID Number", "Last Name", "First Name", "Category", "Course", "Email", "Phone", "Expiry", "Blocked"), rows);
            }
            default -> throw ApiException.notFound("Report type '" + type + "'");
        };
    }

    private static String dayLabel(LocalDate d) {
        return ReportTable.day(d);
    }

    private static String money(BigDecimal v) {
        return "PHP " + String.format(java.util.Locale.ENGLISH, "%,.2f", v);
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
    private ReportTable tagging(Filter flt, String note) {
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
                + (list.size() - tagged) + " not tagged yet · as of " + dayLabel(clock.today()) + note;
        return new ReportTable("tagging-" + clock.today(), "RFID Tagging Report", summary,
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
        if (f.type() != null) parts.add("Material type: " + ReportTable.label(f.type().name()));
        if (f.section() != null) parts.add("Section: " + f.section());
        if (f.itemStatus() != null) parts.add("Copy status: " + ReportTable.label(f.itemStatus().name()));
        if (f.fineStatus() != null) parts.add("Fine status: " + ReportTable.label(f.fineStatus().name()));
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
