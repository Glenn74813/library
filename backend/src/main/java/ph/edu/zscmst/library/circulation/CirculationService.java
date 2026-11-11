package ph.edu.zscmst.library.circulation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ph.edu.zscmst.library.catalog.BibRecord;
import ph.edu.zscmst.library.catalog.BibRecordRepository;
import ph.edu.zscmst.library.catalog.Item;
import ph.edu.zscmst.library.catalog.ItemRepository;
import ph.edu.zscmst.library.catalog.ItemStatus;
import ph.edu.zscmst.library.catalog.LoanPolicy;
import ph.edu.zscmst.library.circulation.CirculationDtos.BookingCalendar;
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
import ph.edu.zscmst.library.common.SettingsService;
import ph.edu.zscmst.library.calendar.LibraryCalendar;
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
    private final LibraryCalendar calendar;
    private final SettingsService settings;
    private final LoanPolicy policy;

    public CirculationService(LoanRepository loans, HoldRequestRepository holds, FineRepository fines,
                              ItemRepository items, PatronRepository patrons, BibRecordRepository records,
                              NoticeService notices, LibraryClock clock, AuditService audit, LibraryCalendar calendar,
                              SettingsService settings, LoanPolicy policy) {
        this.policy = policy;
        this.calendar = calendar;
        this.settings = settings;
        this.loans = loans;
        this.holds = holds;
        this.fines = fines;
        this.items = items;
        this.patrons = patrons;
        this.records = records;
        this.notices = notices;
        this.clock = clock;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- loans

    @Transactional
    public CheckoutResult checkout(String patronCode, String itemCode, boolean override) {
        return checkout(patronCode, itemCode, override, null);
    }

    /**
     * Checkout. {@code offlineAt} is the original time of a checkout recorded while the desk was offline:
     * the due date is counted from it, and limits are reported as warnings since the item has already left.
     */
    @Transactional
    public CheckoutResult checkout(String patronCode, String itemCode, boolean override, Instant offlineAt) {
        Patron p = patrons.findByIdentifier(patronCode.trim()).orElseThrow(() -> ApiException.notFound("Patron"));
        Item item = items.findByIdentifier(itemCode.trim()).orElseThrow(() -> ApiException.notFound("Item"));
        PatronCategory cat = p.getCategory();
        List<String> warnings = new ArrayList<>();

        if (p.isBlocked()) {
            throw ApiException.conflict("Patron is blocked" + (p.getBlockReason() == null ? "" : ": " + p.getBlockReason()));
        }
        Instant now = offlineAt == null ? Instant.now() : offlineAt;
        LocalDate today = clock.toDate(now);
        limit(p.isExpired(today), "Library card expired on " + p.getExpiryDate(), override, warnings);
        long active = loans.countByPatronIdAndReturnedAtIsNull(p.getId());
        limit(active >= cat.getMaxLoans(), "Loan limit reached (" + cat.getMaxLoans() + " items for "
                + cat.getName() + ")", override, warnings);
        BigDecimal unpaid = fines.unpaidTotal(p.getId());
        limit(unpaid.compareTo(cat.getMaxUnpaidFines()) > 0, "Unpaid fines of PHP " + unpaid
                + " exceed the PHP " + cat.getMaxUnpaidFines() + " limit", override, warnings);

        limit(policy.isRoomUse(item), "Room use only: this copy may not leave the library", override, warnings);

        HoldRequest released = null;
        switch (item.getStatus()) {
            case AVAILABLE -> { }
            case ON_HOLD -> {
                HoldRequest hold = holds.findByItemIdAndStatus(item.getId(), HoldStatus.READY)
                        .orElseThrow(() -> ApiException.conflict("Item is on the hold shelf"));
                if (!hold.getPatron().getId().equals(p.getId())) {
                    throw ApiException.conflict("Item is on hold for " + hold.getPatron().getFullName());
                }
                hold.close(HoldStatus.FULFILLED, AuditService.currentActor(), now);
                released = hold;
            }
            case ON_LOAN -> throw ApiException.conflict("Item is already checked out");
            default -> throw ApiException.conflict("Item cannot be borrowed (status: " + item.getStatus() + ")");
        }

        int loanDays = policy.loanDays(item, cat);
        LibraryCalendar.Snapshot cal = calendar.snapshot(today, today.plusDays(loanDays));
        LocalDate dueDate = cal.nextOpenOnOrAfter(today.plusDays(loanDays));
        String notice = null;
        Optional<BookingLimit> limit = bookingLimit(item, p.getId(), dueDate);
        if (limit.isPresent() && limit.get().lastDueDate().isBefore(dueDate)) {
            LocalDate last = cal.previousOpenOnOrBefore(limit.get().lastDueDate());
            dueDate = last.isBefore(today) ? today : last;
            notice = "Due " + clock.format(clock.endOfDay(dueDate)) + ": this title is booked by another patron for "
                    + clock.format(clock.endOfDay(limit.get().bookedFor()));
        }
        // Borrowing a title you booked for later fulfils your own booking.
        holds.findFirstByRecordIdAndPatronIdAndStatus(item.getRecord().getId(), p.getId(), HoldStatus.SCHEDULED)
                .ifPresent(b -> b.close(HoldStatus.FULFILLED, AuditService.currentActor(), now));
        item.setStatus(ItemStatus.ON_LOAN);
        Loan loan = loans.save(new Loan(item, p, now, clock.endOfDay(dueDate), AuditService.currentActor()));
        audit.record("CHECKOUT", item.getBarcode() + " -> " + p.getCardNumber()
                + (warnings.isEmpty() ? "" : " OVERRIDE: " + String.join("; ", warnings))
                + (notice == null ? "" : " SHORTENED: " + notice) + offlineNote(offlineAt));
        if (released != null) {
            audit.record("HOLD_RELEASE", "\"" + item.getRecord().getTitle() + "\" copy " + item.getBarcode()
                    + " released to " + p.getCardNumber() + " " + p.getFullName() + " (hold " + released.getId() + ")");
        }
        return new CheckoutResult(toDto(loan), warnings, notice);
    }

    private static void limit(boolean violated, String message, boolean override, List<String> warnings) {
        if (!violated) return;
        if (!override) throw ApiException.conflict(message);
        warnings.add(message);
    }

    private String offlineNote(Instant offlineAt) {
        return offlineAt == null ? "" : " (offline, at " + clock.format(offlineAt) + ")";
    }

    @Transactional
    public CheckinResult checkin(String itemCode) {
        return checkin(itemCode, null);
    }

    /** Check-in; {@code offlineAt} is the original return time of a check-in recorded offline (fines stop there). */
    @Transactional
    public CheckinResult checkin(String itemCode, Instant offlineAt) {
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
        Instant now = offlineAt == null ? Instant.now() : offlineAt;
        if (now.isBefore(loan.getCheckoutAt())) now = loan.getCheckoutAt();
        loan.setReturnedAt(now);
        BigDecimal fine = assessOverdueFine(loan, now);
        String holdFor = routeReturnedItem(item);
        audit.record("CHECKIN", item.getBarcode() + " from " + loan.getPatron().getCardNumber()
                + (fine.signum() > 0 ? " fine=" + fine : "") + offlineNote(offlineAt));
        String where = item.getShelfLocation();
        String message = holdFor != null ? "Place on hold shelf for " + holdFor
                : where != null ? "Return to " + where : "Return to shelf";
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
        int loanDays = policy.loanDays(loan.getItem(), cat);
        LibraryCalendar.Snapshot cal = calendar.snapshot(clock.today(), clock.today().plusDays(loanDays));
        LocalDate wanted = cal.nextOpenOnOrAfter(clock.today().plusDays(loanDays));
        Optional<BookingLimit> limit = bookingLimit(loan.getItem(), p.getId(), wanted);
        if (limit.isPresent() && limit.get().lastDueDate().isBefore(wanted)) {
            wanted = cal.previousOpenOnOrBefore(limit.get().lastDueDate());
            if (!clock.endOfDay(wanted).isAfter(loan.getDueAt())) {
                throw ApiException.conflict("Cannot renew: this title is booked by another patron for "
                        + clock.format(clock.endOfDay(limit.get().bookedFor())) + ". Please return it by the due date.");
            }
        }
        Instant newDue = clock.endOfDay(wanted);
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
        BigDecimal amount = overdueFine(loan.getPatron().getCategory(), days);
        if (amount.signum() > 0) {
            fines.save(new Fine(loan.getPatron(), loan, amount,
                    "Overdue " + days + " day(s): " + loan.getItem().getRecord().getTitle()));
        }
        return amount;
    }

    /** Fine for {@code days} late open days: the category's grace days are free and the total is capped per item. */
    static BigDecimal overdueFine(PatronCategory cat, long days) {
        long charged = Math.max(0, days - cat.getFineGraceDays());
        BigDecimal amount = cat.getFinePerDay().multiply(BigDecimal.valueOf(charged));
        BigDecimal cap = cat.getMaxFinePerItem();
        return cap != null && cap.signum() > 0 ? amount.min(cap) : amount;
    }

    /** Late days that are charged: only days the library was open after the due date. */
    long overdueDays(Loan loan, Instant at) {
        LocalDate due = clock.toDate(loan.getDueAt());
        LocalDate until = clock.toDate(at);
        if (!until.isAfter(due)) return 0;
        return calendar.snapshot(due, until).openDaysAfter(due, until);
    }

    /** Sends a returned copy to the next waiting hold, or back to the shelf. */
    private String routeReturnedItem(Item item) {
        if (policy.isRoomUse(item)) {
            item.setStatus(ItemStatus.AVAILABLE);
            return null;
        }
        Optional<HoldRequest> next = holds.findQueue(item.getRecord().getId(), HoldStatus.PENDING, PageRequest.of(0, 1))
                .stream().findFirst();
        if (next.isEmpty()) {
            item.setStatus(ItemStatus.AVAILABLE);
            return null;
        }
        setAside(next.get(), item);
        return next.get().getPatron().getFullName();
    }

    private void setAside(HoldRequest hold, Item item) {
        item.setStatus(ItemStatus.ON_HOLD);
        int window = hold.getPatron().getCategory().getHoldPickupDays();
        hold.markReady(item, Instant.now(), clock.endOfDay(calendar.nextOpenOnOrAfter(clock.today().plusDays(window))));
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
        BigDecimal accrued = overdueFine(p.getCategory(), days);
        return new LoanDto(l.getId(), i.getId(), i.getBarcode(), r.getId(), r.getTitle(), r.getCallNumber(),
                p.getId(), p.getFullName(), p.getCardNumber(), l.getCheckoutAt(), l.getDueAt(), l.getReturnedAt(),
                l.getRenewals(), p.getCategory().getMaxRenewals(), overdue, days, accrued, l.getCheckedOutBy());
    }

    // ---------------------------------------------------------------- holds

    @Transactional
    public HoldDto placeHold(Long patronId, Long recordId) {
        return placeHold(patronId, recordId, null);
    }

    /** {@code pickupDate} null reserves the next available copy; a date books the title for that day. */
    @Transactional
    public HoldDto placeHold(Long patronId, Long recordId, LocalDate pickupDate) {
        // Lock the patron first: a double-submitted request waits here and then sees the first hold.
        Patron p = patrons.findByIdForUpdate(patronId).orElseThrow(() -> ApiException.notFound("Patron"));
        BibRecord r = records.findById(recordId).orElseThrow(() -> ApiException.notFound("Record"));
        if (p.isBlocked()) throw ApiException.conflict("Patron is blocked");
        if (holds.existsByRecordIdAndPatronIdAndStatusIn(recordId, patronId,
                EnumSet.of(HoldStatus.SCHEDULED, HoldStatus.PENDING, HoldStatus.READY))) {
            throw ApiException.conflict("Patron already has a reservation or booking for this title");
        }
        if (loans.existsByPatronIdAndItemRecordIdAndReturnedAtIsNull(patronId, recordId)) {
            throw ApiException.conflict("Patron already has this title on loan");
        }
        if (!items.existsByRecordIdAndStatusNot(recordId, ItemStatus.WITHDRAWN)) {
            throw ApiException.badRequest("The library has no copies of this title");
        }
        int maxHolds = p.getCategory().getMaxHolds();
        if (maxHolds > 0 && holds.countByPatronIdAndStatusIn(patronId,
                EnumSet.of(HoldStatus.SCHEDULED, HoldStatus.PENDING, HoldStatus.READY)) >= maxHolds) {
            throw ApiException.conflict("Reservation limit reached (" + maxHolds + " for " + p.getCategory().getName() + ")");
        }
        if (circulatingCopies(recordId).isEmpty()) {
            throw ApiException.badRequest("This title is for room use only and can't be reserved; read it in the library");
        }
        if (pickupDate != null) {
            LocalDate today = clock.today();
            if (!pickupDate.isAfter(today)) {
                throw ApiException.badRequest("Choose a pickup date from tomorrow onwards (use Reserve to get it now)");
            }
            int horizon = policy.bookingHorizonDays();
            if (pickupDate.isAfter(today.plusDays(horizon))) {
                throw ApiException.badRequest("Bookings can be made up to " + horizon + " days ahead");
            }
            records.findByIdForUpdate(recordId); // serialise bookings of one title so capacity can't be oversold
            if (unavailableDates(recordId, p).contains(pickupDate)) {
                throw ApiException.conflict("No copy is free on " + clock.format(clock.endOfDay(pickupDate))
                        + " (all copies are booked or still out on loan). Please choose another date.");
            }
            HoldRequest booking = holds.save(HoldRequest.booking(r, p, pickupDate));
            audit.record("BOOKING_PLACE", p.getCardNumber() + " -> record " + recordId + " for " + pickupDate);
            return HoldDto.of(booking, null);
        }
        HoldRequest hold = holds.save(new HoldRequest(r, p));
        freeLoanableCopy(recordId).ifPresent(item -> setAside(hold, item));
        audit.record("HOLD_PLACE", p.getCardNumber() + " -> record " + recordId);
        return HoldDto.of(hold, queuePosition(hold));
    }

    @Transactional
    public void cancelHold(Long holdId, Long requiredPatronId) {
        HoldRequest h = holds.findById(holdId).orElseThrow(() -> ApiException.notFound("Hold"));
        if (requiredPatronId != null && !h.getPatron().getId().equals(requiredPatronId)) {
            throw ApiException.notFound("Hold");
        }
        if (h.getStatus() != HoldStatus.PENDING && h.getStatus() != HoldStatus.READY
                && h.getStatus() != HoldStatus.SCHEDULED) {
            throw ApiException.badRequest("Hold is already " + h.getStatus());
        }
        HoldStatus was = h.getStatus();
        h.close(HoldStatus.CANCELLED, AuditService.currentActor(), Instant.now());
        if (was == HoldStatus.READY && h.getItem() != null) {
            routeReturnedItem(h.getItem());
        }
        audit.record("HOLD_CANCEL", "hold " + holdId);
    }

    @Transactional(readOnly = true)
    public List<HoldDto> activeHolds() {
        Set<Long> atRisk = new HashSet<>();
        bookingsAtRisk(policy.atRiskDays()).forEach(h -> atRisk.add(h.getId()));
        return holds.findByStatusInOrderByPlacedAtAsc(EnumSet.of(HoldStatus.SCHEDULED, HoldStatus.PENDING, HoldStatus.READY)).stream()
                .map(h -> HoldDto.of(h, queuePosition(h), atRisk.contains(h.getId()))).toList();
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

    // ---------------------------------------------------------------- advance bookings

    /** A loan of a copy must end by {@code lastDueDate} so the booking for {@code bookedFor} gets a copy. */
    record BookingLimit(LocalDate lastDueDate, LocalDate bookedFor) {
    }

    /** A copy on the shelf that may be lent, locked so no other hold takes it at the same time. */
    private Optional<Item> freeLoanableCopy(Long recordId) {
        return items.findByRecordIdAndStatusOrderByIdAsc(recordId, ItemStatus.AVAILABLE).stream()
                .filter(policy::isLoanable).findFirst();
    }

    /** Copies that can serve bookings (not withdrawn, lost, missing, damaged or room use only). */
    private List<Item> circulatingCopies(Long recordId) {
        return items.findByRecordIdOrderByBarcodeAsc(recordId).stream()
                .filter(i -> i.getStatus() == ItemStatus.AVAILABLE || i.getStatus() == ItemStatus.ON_LOAN
                        || i.getStatus() == ItemStatus.ON_HOLD)
                .filter(policy::isLoanable)
                .toList();
    }

    /**
     * Latest date a loan of {@code item} may run to without leaving a booking of its title (by
     * another patron, starting on or before {@code wantedDue}) without a copy. Other copies count as
     * free for a booking if they are on the shelf now or due back before the booking date.
     */
    Optional<BookingLimit> bookingLimit(Item item, Long borrowerId, LocalDate wantedDue) {
        Long recordId = item.getRecord().getId();
        List<HoldRequest> bookings = holds.findByRecordIdAndStatusOrderByPickupDateAsc(recordId, HoldStatus.SCHEDULED)
                .stream().filter(b -> !b.getPatron().getId().equals(borrowerId) && !b.getPickupDate().isAfter(wantedDue))
                .toList();
        if (bookings.isEmpty()) return Optional.empty();
        List<Item> others = circulatingCopies(recordId).stream().filter(i -> !i.getId().equals(item.getId())).toList();
        Map<Long, Instant> due = new HashMap<>();
        List<Long> onLoan = others.stream().filter(i -> i.getStatus() == ItemStatus.ON_LOAN).map(Item::getId).toList();
        if (!onLoan.isEmpty()) loans.findActiveByItemIds(onLoan).forEach(l -> due.put(l.getItem().getId(), l.getDueAt()));
        for (int k = 0; k < bookings.size(); k++) {
            LocalDate d = bookings.get(k).getPickupDate();
            long free = others.stream().filter(o -> o.getStatus() == ItemStatus.AVAILABLE
                    || (o.getStatus() == ItemStatus.ON_LOAN && due.containsKey(o.getId())
                        && clock.toDate(due.get(o.getId())).isBefore(d))).count();
            if (free < k + 1) return Optional.of(new BookingLimit(d.minusDays(1), d));
        }
        return Optional.empty();
    }

    /** How long a booking holds a copy: pickup window plus the booker's loan period. */
    private LocalDate bookingEnd(LocalDate start, Patron p) {
        return start.plusDays(p.getCategory().getHoldPickupDays() + p.getCategory().getLoanDays());
    }

    /** Dates in the booking horizon that {@code p} cannot book because all copies are booked around them. */
    List<LocalDate> unavailableDates(Long recordId, Patron p) {
        int copies = circulatingCopies(recordId).size();
        LocalDate first = clock.today().plusDays(1);
        LocalDate last = clock.today().plusDays(policy.bookingHorizonDays());
        List<HoldRequest> bookings = holds.findByRecordIdAndStatusOrderByPickupDateAsc(recordId, HoldStatus.SCHEDULED)
                .stream().filter(b -> !b.getPatron().getId().equals(p.getId())).toList();
        // A copy on loan is busy until its due date; an overdue copy is busy until it actually comes back.
        LocalDate today = clock.today();
        List<Item> lent = circulatingCopies(recordId).stream().filter(i -> i.getStatus() == ItemStatus.ON_LOAN).toList();
        List<LocalDate> lentUntil = lent.isEmpty() ? List.of() : loans.findActiveByItemIds(lent.stream().map(Item::getId).toList())
                .stream().map(l -> clock.toDate(l.getDueAt())).map(due -> due.isBefore(today) ? last.plusDays(365) : due).toList();
        LibraryCalendar.Snapshot cal = calendar.snapshot(first, last);
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
            if (copies == 0 || !cal.isOpen(d)) { out.add(d); continue; }
            LocalDate end = bookingEnd(d, p);
            int peak = 0;
            for (LocalDate x = d; !x.isAfter(end); x = x.plusDays(1)) {
                final LocalDate day = x;
                int busy = (int) bookings.stream()
                        .filter(b -> !day.isBefore(b.getPickupDate()) && !day.isAfter(bookingEnd(b.getPickupDate(), b.getPatron())))
                        .count()
                        + (day.equals(d) ? (int) lentUntil.stream().filter(due -> !due.isBefore(day)).count() : 0);
                peak = Math.max(peak, busy);
                if (peak >= copies) break;
            }
            if (peak >= copies) out.add(d);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public BookingCalendar bookingCalendar(Long recordId, Long patronId) {
        Patron p = patrons.findById(patronId).orElseThrow(() -> ApiException.notFound("Patron"));
        records.findById(recordId).orElseThrow(() -> ApiException.notFound("Record"));
        return new BookingCalendar(clock.today().plusDays(1), clock.today().plusDays(policy.bookingHorizonDays()),
                unavailableDates(recordId, p), circulatingCopies(recordId).size());
    }

    /**
     * Bookings (pickup within {@code days}) that may not get a copy: per title, the copies on the
     * shelf plus those due back before the pickup date (and not overdue) don't cover the bookings.
     * Also includes bookings whose date has come and are still waiting for a copy.
     */
    @Transactional(readOnly = true)
    public List<HoldRequest> bookingsAtRisk(int days) {
        LocalDate today = clock.today();
        List<HoldRequest> risky = new ArrayList<>(holds.findByStatusInOrderByPlacedAtAsc(EnumSet.of(HoldStatus.PENDING))
                .stream().filter(h -> h.getPickupDate() != null).toList());
        Map<Long, List<HoldRequest>> byTitle = new HashMap<>();
        holds.findByStatusOrderByPickupDateAsc(HoldStatus.SCHEDULED).stream()
                .filter(b -> !b.getPickupDate().isAfter(today.plusDays(days)))
                .forEach(b -> byTitle.computeIfAbsent(b.getRecord().getId(), k -> new ArrayList<>()).add(b));
        for (var entry : byTitle.entrySet()) {
            List<Item> copies = circulatingCopies(entry.getKey());
            Map<Long, Instant> due = new HashMap<>();
            List<Long> onLoan = copies.stream().filter(i -> i.getStatus() == ItemStatus.ON_LOAN).map(Item::getId).toList();
            if (!onLoan.isEmpty()) loans.findActiveByItemIds(onLoan).forEach(l -> due.put(l.getItem().getId(), l.getDueAt()));
            List<HoldRequest> bookings = entry.getValue();
            for (int k = 0; k < bookings.size(); k++) {
                LocalDate d = bookings.get(k).getPickupDate();
                long free = copies.stream().filter(c -> c.getStatus() == ItemStatus.AVAILABLE
                        || (c.getStatus() == ItemStatus.ON_LOAN && due.containsKey(c.getId())
                            && !clock.toDate(due.get(c.getId())).isBefore(today)        // overdue copies can't be counted on
                            && clock.toDate(due.get(c.getId())).isBefore(d))).count();
                if (free < k + 1) risky.add(bookings.get(k));
            }
        }
        return risky;
    }

    @Transactional(readOnly = true)
    public List<HoldDto> bookings() {
        return holds.findByStatusOrderByPickupDateAsc(HoldStatus.SCHEDULED).stream().map(h -> HoldDto.of(h, null)).toList();
    }

    /**
     * Each morning (and hourly as a catch-up) bookings whose date has come become real holds: a copy
     * on the shelf is set aside for pickup, otherwise the booking goes to the front of the queue.
     */
    @Scheduled(cron = "0 0 6-20 * * *", zone = "${app.library.timezone}")
    @Transactional
    public void activateBookings() {
        for (HoldRequest b : holds.findByStatusAndPickupDateLessThanEqual(HoldStatus.SCHEDULED, clock.today())) {
            Optional<Item> free = freeLoanableCopy(b.getRecord().getId());
            if (free.isPresent()) setAside(b, free.get());
            else b.setStatus(HoldStatus.PENDING);
        }
    }

    @Scheduled(cron = "0 5 * * * *")
    @Transactional
    public void expireHolds() {
        for (HoldRequest h : holds.findByStatusAndExpiresAtBefore(HoldStatus.READY, Instant.now())) {
            h.close(HoldStatus.EXPIRED, "system (pickup window ended)", Instant.now());
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
