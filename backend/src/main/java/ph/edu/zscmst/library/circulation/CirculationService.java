package ph.edu.zscmst.library.circulation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ph.edu.zscmst.library.catalog.BibRecord;
import ph.edu.zscmst.library.catalog.BibRecordRepository;
import ph.edu.zscmst.library.catalog.Item;
import ph.edu.zscmst.library.catalog.ItemRepository;
import ph.edu.zscmst.library.catalog.ItemStatus;
import ph.edu.zscmst.library.circulation.CirculationDtos.CheckinResult;
import ph.edu.zscmst.library.circulation.CirculationDtos.CheckoutResult;
import ph.edu.zscmst.library.circulation.CirculationDtos.FineDto;
import ph.edu.zscmst.library.circulation.CirculationDtos.HoldDto;
import ph.edu.zscmst.library.circulation.CirculationDtos.LoanDto;
import ph.edu.zscmst.library.circulation.CirculationDtos.PatronSummary;
import ph.edu.zscmst.library.circulation.CirculationDtos.ReceiptDto;
import ph.edu.zscmst.library.common.ApiException;
import ph.edu.zscmst.library.common.AuditService;
import ph.edu.zscmst.library.common.LibraryClock;
import ph.edu.zscmst.library.notice.NoticeService;
import ph.edu.zscmst.library.notice.NoticeType;
import ph.edu.zscmst.library.patron.Patron;
import ph.edu.zscmst.library.patron.PatronCategory;
import ph.edu.zscmst.library.patron.PatronDtos.PatronDto;
import ph.edu.zscmst.library.patron.PatronRepository;

/** Check-out, check-in, renewals, holds and fines, enforcing per-category loan rules. */
@Service
public class CirculationService {

    private static final DateTimeFormatter RECEIPT_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final LoanRepository loans;
    private final HoldRequestRepository holds;
    private final FineRepository fines;
    private final ItemRepository items;
    private final PatronRepository patrons;
    private final BibRecordRepository records;
    private final NoticeService notices;
    private final LibraryClock clock;
    private final AuditService audit;
    private final int holdPickupDays;

    public CirculationService(LoanRepository loans, HoldRequestRepository holds, FineRepository fines,
                              ItemRepository items, PatronRepository patrons, BibRecordRepository records,
                              NoticeService notices, LibraryClock clock, AuditService audit,
                              @Value("${app.library.hold-pickup-days}") int holdPickupDays) {
        this.loans = loans;
        this.holds = holds;
        this.fines = fines;
        this.items = items;
        this.patrons = patrons;
        this.records = records;
        this.notices = notices;
        this.clock = clock;
        this.audit = audit;
        this.holdPickupDays = holdPickupDays;
    }

    // ---------------------------------------------------------------- loans

    @Transactional
    public CheckoutResult checkout(String patronCode, String itemCode, boolean override) {
        Patron p = patrons.findByIdentifier(patronCode.trim()).orElseThrow(() -> ApiException.notFound("Patron"));
        Item item = items.findByIdentifier(itemCode.trim()).orElseThrow(() -> ApiException.notFound("Item"));
        PatronCategory cat = p.getCategory();
        List<String> warnings = new ArrayList<>();

        if (p.isBlocked()) {
            throw ApiException.conflict("Patron is blocked" + (p.getBlockReason() == null ? "" : ": " + p.getBlockReason()));
        }
        LocalDate today = clock.today();
        limit(p.isExpired(today), "Library card expired on " + p.getExpiryDate(), override, warnings);
        long active = loans.countByPatronIdAndReturnedAtIsNull(p.getId());
        limit(active >= cat.getMaxLoans(), "Loan limit reached (" + cat.getMaxLoans() + " items for "
                + cat.getName() + ")", override, warnings);
        BigDecimal unpaid = fines.unpaidTotal(p.getId());
        limit(unpaid.compareTo(cat.getMaxUnpaidFines()) > 0, "Unpaid fines of PHP " + unpaid
                + " exceed the PHP " + cat.getMaxUnpaidFines() + " limit", override, warnings);

        switch (item.getStatus()) {
            case AVAILABLE -> { }
            case ON_HOLD -> {
                HoldRequest hold = holds.findByItemIdAndStatus(item.getId(), HoldStatus.READY)
                        .orElseThrow(() -> ApiException.conflict("Item is on the hold shelf"));
                if (!hold.getPatron().getId().equals(p.getId())) {
                    throw ApiException.conflict("Item is on hold for " + hold.getPatron().getFullName());
                }
                hold.setStatus(HoldStatus.FULFILLED);
            }
            case ON_LOAN -> throw ApiException.conflict("Item is already checked out");
            default -> throw ApiException.conflict("Item cannot be borrowed (status: " + item.getStatus() + ")");
        }

        Instant now = Instant.now();
        item.setStatus(ItemStatus.ON_LOAN);
        Loan loan = loans.save(new Loan(item, p, now, clock.endOfDay(today.plusDays(cat.getLoanDays())),
                AuditService.currentActor()));
        audit.record("CHECKOUT", item.getBarcode() + " -> " + p.getCardNumber()
                + (warnings.isEmpty() ? "" : " OVERRIDE: " + String.join("; ", warnings)));
        return new CheckoutResult(toDto(loan), warnings);
    }

    private static void limit(boolean violated, String message, boolean override, List<String> warnings) {
        if (!violated) return;
        if (!override) throw ApiException.conflict(message);
        warnings.add(message);
    }

    @Transactional
    public CheckinResult checkin(String itemCode) {
        Item item = items.findByIdentifier(itemCode.trim()).orElseThrow(() -> ApiException.notFound("Item"));
        Optional<Loan> activeLoan = loans.findByItemIdAndReturnedAtIsNull(item.getId());
        if (activeLoan.isEmpty()) {
            if (item.getStatus() == ItemStatus.LOST || item.getStatus() == ItemStatus.MISSING) {
                ItemStatus was = item.getStatus();
                String holdFor = routeReturnedItem(item);
                audit.record("ITEM_FOUND", item.getBarcode() + " was " + was);
                return new CheckinResult(null, BigDecimal.ZERO, holdFor,
                        "Item was marked " + was + " and is now back in circulation");
            }
            if (item.getStatus() == ItemStatus.ON_HOLD) {
                String who = holds.findByItemIdAndStatus(item.getId(), HoldStatus.READY)
                        .map(h -> h.getPatron().getFullName()).orElse("a patron");
                return new CheckinResult(null, BigDecimal.ZERO, who, "Item belongs on the hold shelf for " + who);
            }
            throw ApiException.badRequest("Item is not checked out");
        }
        Loan loan = activeLoan.get();
        Instant now = Instant.now();
        loan.setReturnedAt(now);
        BigDecimal fine = assessOverdueFine(loan, now);
        String holdFor = routeReturnedItem(item);
        audit.record("CHECKIN", item.getBarcode() + " from " + loan.getPatron().getCardNumber()
                + (fine.signum() > 0 ? " fine=" + fine : ""));
        String message = holdFor != null ? "Place on hold shelf for " + holdFor : "Return to shelf";
        return new CheckinResult(toDto(loan), fine, holdFor, message);
    }

    @Transactional
    public LoanDto renew(Long loanId, Long requiredPatronId) {
        Loan loan = loans.findWithDetailsById(loanId).orElseThrow(() -> ApiException.notFound("Loan"));
        Patron p = loan.getPatron();
        if (requiredPatronId != null && !p.getId().equals(requiredPatronId)) {
            throw ApiException.notFound("Loan");
        }
        if (!loan.isActive()) throw ApiException.badRequest("Loan was already returned");
        if (p.isBlocked()) throw ApiException.conflict("Patron is blocked");
        if (loan.getDueAt().isBefore(Instant.now())) {
            throw ApiException.conflict("Overdue items cannot be renewed; please return the item to the library");
        }
        PatronCategory cat = p.getCategory();
        if (loan.getRenewals() >= cat.getMaxRenewals()) {
            throw ApiException.conflict("Renewal limit reached (" + cat.getMaxRenewals() + ")");
        }
        if (holds.existsByRecordIdAndStatus(loan.getItem().getRecord().getId(), HoldStatus.PENDING)) {
            throw ApiException.conflict("Another patron has reserved this title");
        }
        Instant newDue = clock.endOfDay(clock.today().plusDays(cat.getLoanDays()));
        if (newDue.isAfter(loan.getDueAt())) loan.setDueAt(newDue);
        loan.setRenewals(loan.getRenewals() + 1);
        audit.record("RENEW", loan.getItem().getBarcode() + " for " + p.getCardNumber());
        return toDto(loan);
    }

    @Transactional
    public LoanDto markLost(Long loanId, BigDecimal charge) {
        Loan loan = loans.findWithDetailsById(loanId).orElseThrow(() -> ApiException.notFound("Loan"));
        if (!loan.isActive()) throw ApiException.badRequest("Loan was already returned");
        Instant now = Instant.now();
        loan.setReturnedAt(now);
        assessOverdueFine(loan, now);
        loan.getItem().setStatus(ItemStatus.LOST);
        if (charge.signum() > 0) {
            fines.save(new Fine(loan.getPatron(), loan, charge,
                    "Lost item replacement: " + loan.getItem().getRecord().getTitle()));
        }
        audit.record("ITEM_LOST", loan.getItem().getBarcode() + " charged " + charge);
        return toDto(loan);
    }

    private BigDecimal assessOverdueFine(Loan loan, Instant returnedAt) {
        long days = overdueDays(loan, returnedAt);
        BigDecimal amount = loan.getPatron().getCategory().getFinePerDay().multiply(BigDecimal.valueOf(days));
        if (amount.signum() > 0) {
            fines.save(new Fine(loan.getPatron(), loan, amount,
                    "Overdue " + days + " day(s): " + loan.getItem().getRecord().getTitle()));
        }
        return amount;
    }

    long overdueDays(Loan loan, Instant at) {
        return Math.max(0, ChronoUnit.DAYS.between(clock.toDate(loan.getDueAt()), clock.toDate(at)));
    }

    /** Sends a returned copy to the next waiting hold, or back to the shelf. */
    private String routeReturnedItem(Item item) {
        Optional<HoldRequest> next = holds.findFirstByRecordIdAndStatusOrderByPlacedAtAsc(
                item.getRecord().getId(), HoldStatus.PENDING);
        if (next.isEmpty()) {
            item.setStatus(ItemStatus.AVAILABLE);
            return null;
        }
        setAside(next.get(), item);
        return next.get().getPatron().getFullName();
    }

    private void setAside(HoldRequest hold, Item item) {
        item.setStatus(ItemStatus.ON_HOLD);
        hold.markReady(item, Instant.now(), clock.endOfDay(clock.today().plusDays(holdPickupDays)));
        notices.queue(hold.getPatron(), NoticeType.HOLD_READY, hold.getRecord().getTitle(), hold.getExpiresAt(),
                "hold:" + hold.getId());
    }

    @Transactional(readOnly = true)
    public Page<LoanDto> activeLoans(boolean overdueOnly, Pageable pageable) {
        Page<Loan> page = overdueOnly
                ? loans.findByReturnedAtIsNullAndDueAtBeforeOrderByDueAtAsc(Instant.now(), pageable)
                : loans.findByReturnedAtIsNullOrderByDueAtAsc(pageable);
        return page.map(this::toDto);
    }

    @Transactional(readOnly = true)
    public Page<LoanDto> history(Long patronId, Pageable pageable) {
        return loans.findByPatronIdOrderByCheckoutAtDesc(patronId, pageable).map(this::toDto);
    }

    @Transactional(readOnly = true)
    public List<LoanDto> activeLoansOf(Long patronId) {
        return loans.findByPatronIdAndReturnedAtIsNullOrderByDueAtAsc(patronId).stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public PatronSummary summary(Long patronId) {
        Patron p = patrons.findById(patronId).orElseThrow(() -> ApiException.notFound("Patron"));
        List<FineDto> unpaid = fines.findByPatronIdOrderByCreatedAtDesc(patronId).stream()
                .filter(f -> f.getStatus() == FineStatus.UNPAID).map(FineDto::of).toList();
        BigDecimal unpaidTotal = fines.unpaidTotal(patronId);
        List<LoanDto> active = activeLoansOf(patronId);
        List<String> blocks = new ArrayList<>();
        PatronCategory cat = p.getCategory();
        if (p.isBlocked()) blocks.add("Blocked" + (p.getBlockReason() == null ? "" : ": " + p.getBlockReason()));
        if (p.isExpired(clock.today())) blocks.add("Card expired on " + p.getExpiryDate());
        if (active.size() >= cat.getMaxLoans()) blocks.add("Loan limit reached");
        if (unpaidTotal.compareTo(cat.getMaxUnpaidFines()) > 0) blocks.add("Unpaid fines over limit");
        if (active.stream().anyMatch(LoanDto::overdue)) blocks.add("Has overdue items");
        return new PatronSummary(PatronDto.of(p), active, holdsOf(patronId), unpaid, unpaidTotal, cat.getMaxLoans(),
                blocks);
    }

    LoanDto toDto(Loan l) {
        Item i = l.getItem();
        BibRecord r = i.getRecord();
        Patron p = l.getPatron();
        Instant now = Instant.now();
        boolean overdue = l.isActive() && l.getDueAt().isBefore(now);
        long days = l.isActive() ? overdueDays(l, now) : 0;
        BigDecimal accrued = p.getCategory().getFinePerDay().multiply(BigDecimal.valueOf(days));
        return new LoanDto(l.getId(), i.getId(), i.getBarcode(), r.getId(), r.getTitle(), r.getCallNumber(),
                p.getId(), p.getFullName(), p.getCardNumber(), l.getCheckoutAt(), l.getDueAt(), l.getReturnedAt(),
                l.getRenewals(), p.getCategory().getMaxRenewals(), overdue, days, accrued);
    }

    // ---------------------------------------------------------------- holds

    @Transactional
    public HoldDto placeHold(Long patronId, Long recordId) {
        // Lock the patron first: a double-submitted request waits here and then sees the first hold.
        Patron p = patrons.findByIdForUpdate(patronId).orElseThrow(() -> ApiException.notFound("Patron"));
        BibRecord r = records.findById(recordId).orElseThrow(() -> ApiException.notFound("Record"));
        if (p.isBlocked()) throw ApiException.conflict("Patron is blocked");
        if (holds.existsByRecordIdAndPatronIdAndStatusIn(recordId, patronId,
                EnumSet.of(HoldStatus.PENDING, HoldStatus.READY))) {
            throw ApiException.conflict("Patron already has a hold on this title");
        }
        if (loans.existsByPatronIdAndItemRecordIdAndReturnedAtIsNull(patronId, recordId)) {
            throw ApiException.conflict("Patron already has this title on loan");
        }
        if (!items.existsByRecordIdAndStatusNot(recordId, ItemStatus.WITHDRAWN)) {
            throw ApiException.badRequest("The library has no copies of this title");
        }
        HoldRequest hold = holds.save(new HoldRequest(r, p));
        items.findFirstByRecordIdAndStatusOrderByIdAsc(recordId, ItemStatus.AVAILABLE)
                .ifPresent(item -> setAside(hold, item));
        audit.record("HOLD_PLACE", p.getCardNumber() + " -> record " + recordId);
        return HoldDto.of(hold, queuePosition(hold));
    }

    @Transactional
    public void cancelHold(Long holdId, Long requiredPatronId) {
        HoldRequest h = holds.findById(holdId).orElseThrow(() -> ApiException.notFound("Hold"));
        if (requiredPatronId != null && !h.getPatron().getId().equals(requiredPatronId)) {
            throw ApiException.notFound("Hold");
        }
        if (h.getStatus() != HoldStatus.PENDING && h.getStatus() != HoldStatus.READY) {
            throw ApiException.badRequest("Hold is already " + h.getStatus());
        }
        HoldStatus was = h.getStatus();
        h.setStatus(HoldStatus.CANCELLED);
        if (was == HoldStatus.READY && h.getItem() != null) {
            routeReturnedItem(h.getItem());
        }
        audit.record("HOLD_CANCEL", "hold " + holdId);
    }

    @Transactional(readOnly = true)
    public List<HoldDto> activeHolds() {
        return holds.findByStatusInOrderByPlacedAtAsc(EnumSet.of(HoldStatus.PENDING, HoldStatus.READY)).stream()
                .map(h -> HoldDto.of(h, queuePosition(h))).toList();
    }

    @Transactional(readOnly = true)
    public List<HoldDto> holdsOf(Long patronId) {
        return holds.findByPatronIdOrderByPlacedAtDesc(patronId).stream()
                .map(h -> HoldDto.of(h, queuePosition(h))).toList();
    }

    private Integer queuePosition(HoldRequest h) {
        if (h.getStatus() != HoldStatus.PENDING) return null;
        return (int) holds.countByRecordIdAndStatusAndPlacedAtBefore(h.getRecord().getId(), HoldStatus.PENDING,
                h.getPlacedAt()) + 1;
    }

    @Scheduled(cron = "0 5 * * * *")
    @Transactional
    public void expireHolds() {
        for (HoldRequest h : holds.findByStatusAndExpiresAtBefore(HoldStatus.READY, Instant.now())) {
            h.setStatus(HoldStatus.EXPIRED);
            if (h.getItem() != null) routeReturnedItem(h.getItem());
        }
    }

    // ---------------------------------------------------------------- fines

    @Transactional(readOnly = true)
    public Page<FineDto> listFines(FineStatus status, Pageable pageable) {
        Page<Fine> page = status == null ? fines.findAllByOrderByCreatedAtDesc(pageable)
                : fines.findByStatusOrderByCreatedAtDesc(status, pageable);
        return page.map(FineDto::of);
    }

    @Transactional(readOnly = true)
    public List<FineDto> finesOf(Long patronId) {
        return fines.findByPatronIdOrderByCreatedAtDesc(patronId).stream().map(FineDto::of).toList();
    }

    @Transactional
    public ReceiptDto pay(List<Long> fineIds) {
        List<Fine> list = fines.findByIdIn(fineIds);
        if (list.size() != fineIds.stream().distinct().count()) throw ApiException.notFound("Fine");
        if (list.stream().anyMatch(f -> f.getStatus() != FineStatus.UNPAID)) {
            throw ApiException.conflict("Some fines are already settled");
        }
        if (list.stream().map(f -> f.getPatron().getId()).distinct().count() > 1) {
            throw ApiException.badRequest("Pay fines of one patron per receipt");
        }
        Instant now = Instant.now();
        String cashier = AuditService.currentActor();
        long first = list.stream().mapToLong(Fine::getId).min().orElseThrow();
        String receipt = "OR-" + RECEIPT_DATE.format(clock.today()) + "-" + String.format("%06d", first);
        list.forEach(f -> f.settle(FineStatus.PAID, receipt, cashier, now));
        audit.record("FINE_PAY", receipt + " ids=" + fineIds);
        return receipt(receipt);
    }

    @Transactional(readOnly = true)
    public ReceiptDto receipt(String receiptNumber) {
        List<Fine> list = fines.findByReceiptNumberOrderByIdAsc(receiptNumber);
        if (list.isEmpty()) throw ApiException.notFound("Receipt");
        Fine f0 = list.getFirst();
        BigDecimal total = list.stream().map(Fine::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new ReceiptDto(receiptNumber, f0.getPatron().getFullName(), f0.getPatron().getCardNumber(),
                list.stream().map(FineDto::of).toList(), total, f0.getSettledAt(), f0.getSettledBy());
    }

    @Transactional
    public FineDto waive(Long fineId, String reason) {
        Fine f = fines.findById(fineId).orElseThrow(() -> ApiException.notFound("Fine"));
        if (f.getStatus() != FineStatus.UNPAID) throw ApiException.conflict("Fine is already settled");
        f.settle(FineStatus.WAIVED, null, AuditService.currentActor(), Instant.now());
        audit.record("FINE_WAIVE", "fine " + fineId + " PHP " + f.getAmount() + ": " + reason);
        return FineDto.of(f);
    }

    @Transactional
    public FineDto addManualFine(Long patronId, BigDecimal amount, String reason) {
        Patron p = patrons.findById(patronId).orElseThrow(() -> ApiException.notFound("Patron"));
        Fine f = fines.save(new Fine(p, null, amount, reason));
        audit.record("FINE_ADD", p.getCardNumber() + " PHP " + amount + ": " + reason);
        return FineDto.of(f);
    }
}
