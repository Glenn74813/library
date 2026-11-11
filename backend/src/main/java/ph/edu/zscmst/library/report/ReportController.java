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
import ph.edu.zscmst.library.circulation.Fine;
import ph.edu.zscmst.library.circulation.FineRepository;
import ph.edu.zscmst.library.circulation.FineStatus;
import ph.edu.zscmst.library.circulation.HoldRequestRepository;
import ph.edu.zscmst.library.circulation.HoldStatus;
import ph.edu.zscmst.library.circulation.Loan;
import ph.edu.zscmst.library.circulation.LoanRepository;
import ph.edu.zscmst.library.common.ApiException;
import ph.edu.zscmst.library.common.Csv;
import ph.edu.zscmst.library.common.LibraryClock;
import ph.edu.zscmst.library.patron.Patron;
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

    private final BibRecordRepository records;
    private final ItemRepository items;
    private final PatronRepository patrons;
    private final LoanRepository loans;
    private final HoldRequestRepository holds;
    private final FineRepository fines;
    private final AttendanceLogRepository attendance;
    private final LibraryClock clock;

    public ReportController(BibRecordRepository records, ItemRepository items, PatronRepository patrons,
                            LoanRepository loans, HoldRequestRepository holds, FineRepository fines,
                            AttendanceLogRepository attendance, LibraryClock clock) {
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
        Instant now = Instant.now();
        return new Dashboard(records.count(), items.count(), patrons.count(), loans.countByReturnedAtIsNull(),
                loans.countByReturnedAtIsNullAndDueAtBefore(now), holds.countByStatus(HoldStatus.PENDING),
                holds.countByStatus(HoldStatus.READY), fines.totalUnpaid(), loans.countByCheckoutAtBetween(start, end),
                loans.countByReturnedAtBetween(start, end), attendance.countByTimeInBetween(start, end),
                attendance.countByTimeInAfterAndTimeOutIsNull(start),
                fines.sumSettled(FineStatus.PAID, start, end), items.countByStatus(ItemStatus.MISSING),
                items.countByStatus(ItemStatus.LOST));
    }

    @GetMapping("/circulation")
    public CirculationStats circulation(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "10") int top) {
        LocalDate t = to == null ? clock.today() : to;
        LocalDate f = from == null ? t.minusDays(29) : from;
        Instant start = clock.startOfDay(f);
        Instant end = clock.startOfDay(t.plusDays(1));
        PageRequest limit = PageRequest.of(0, Math.min(top, 50));
        List<Ranked> borrowers = loans.topBorrowers(start, end, limit).stream()
                .map(r -> new Ranked((Long) r[0], (String) r[1], (String) r[2], ((Number) r[3]).longValue())).toList();
        List<Ranked> titles = loans.topTitles(start, end, limit).stream()
                .map(r -> new Ranked((Long) r[0], null, (String) r[1], ((Number) r[2]).longValue())).toList();
        return new CirculationStats(f, t, loans.countByCheckoutAtBetween(start, end),
                loans.countByReturnedAtBetween(start, end), fines.sumSettled(FineStatus.PAID, start, end),
                borrowers, titles);
    }

    /** CSV exports: loans, overdue, fines, collection, patrons. Opens directly in Excel. */
    @GetMapping("/export/{type}")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> export(@PathVariable String type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate t = to == null ? clock.today() : to;
        LocalDate f = from == null ? t.minusDays(29) : from;
        return switch (type) {
            case "loans" -> Csv.download("loans-" + f + "-to-" + t + ".csv",
                    List.of("Checkout", "Due", "Returned", "Barcode", "Title", "Card Number", "Patron", "Renewals"),
                    loans.findByCheckoutAtBetweenOrderByCheckoutAtAsc(clock.startOfDay(f), clock.startOfDay(t.plusDays(1)))
                            .stream().map(this::loanRow).toList());
            case "overdue" -> Csv.download("overdue-" + clock.today() + ".csv",
                    List.of("Checkout", "Due", "Returned", "Barcode", "Title", "Card Number", "Patron", "Renewals"),
                    loans.findByReturnedAtIsNullAndDueAtBefore(Instant.now()).stream().map(this::loanRow).toList());
            case "fines" -> Csv.download("fines-" + clock.today() + ".csv",
                    List.of("Date", "Card Number", "Patron", "Amount", "Reason", "Status", "Receipt", "Settled By"),
                    fines.findAll().stream().map(this::fineRow).toList());
            case "collection" -> Csv.download("collection-" + clock.today() + ".csv",
                    List.of("Barcode", "RFID EPC", "Title", "Authors", "ISBN", "Call Number", "Type", "Location",
                            "Status", "Acquired", "Price"),
                    items.findAll().stream().map(this::itemRow).toList());
            case "patrons" -> Csv.download("patrons-" + clock.today() + ".csv",
                    List.of("Card Number", "Last Name", "First Name", "Category", "Course", "Email", "Phone",
                            "Expiry", "Blocked"),
                    patrons.findAll().stream().map(this::patronRow).toList());
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

    private List<?> itemRow(Item i) {
        var r = i.getRecord();
        return Arrays.asList(i.getBarcode(), i.getRfidEpc(), r.getTitle(), r.getAuthors(), r.getIsbn(),
                r.getCallNumber(), r.getMaterialType(), i.getLocation(), i.getStatus(), i.getAcquiredDate(),
                i.getPrice());
    }

    private List<?> patronRow(Patron p) {
        return Arrays.asList(p.getCardNumber(), p.getLastName(), p.getFirstName(), p.getCategory().getName(),
                p.getCourse() == null ? null : p.getCourse().getCode(), p.getEmail(), p.getPhone(),
                p.getExpiryDate(), p.isBlocked());
    }
}
