package ph.edu.zscmst.library.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import ph.edu.zscmst.library.calendar.ClosedDay;
import ph.edu.zscmst.library.calendar.ClosedDayRepository;
import ph.edu.zscmst.library.calendar.LibraryCalendar;
import ph.edu.zscmst.library.catalog.BibRecord;
import ph.edu.zscmst.library.catalog.BibRecordRepository;
import ph.edu.zscmst.library.catalog.Item;
import ph.edu.zscmst.library.catalog.ItemRepository;
import ph.edu.zscmst.library.catalog.ItemStatus;
import ph.edu.zscmst.library.catalog.MaterialType;
import ph.edu.zscmst.library.circulation.CirculationDtos.CheckinResult;
import ph.edu.zscmst.library.circulation.CirculationDtos.CheckoutResult;
import ph.edu.zscmst.library.circulation.CirculationDtos.HoldDto;
import ph.edu.zscmst.library.circulation.CirculationDtos.Settle;
import ph.edu.zscmst.library.circulation.CirculationService;
import ph.edu.zscmst.library.circulation.HoldStatus;
import ph.edu.zscmst.library.common.ApiException;
import ph.edu.zscmst.library.common.LibraryClock;
import ph.edu.zscmst.library.patron.Patron;
import ph.edu.zscmst.library.patron.PatronCategory;
import ph.edu.zscmst.library.patron.PatronCategoryRepository;
import ph.edu.zscmst.library.patron.PatronRepository;

/** Loan rules end to end through the circulation service; each test rolls back. */
@Transactional
class CirculationRulesTest extends IntegrationTest {

    @Autowired CirculationService circulation;
    @Autowired PatronCategoryRepository categories;
    @Autowired PatronRepository patrons;
    @Autowired BibRecordRepository records;
    @Autowired ItemRepository items;
    @Autowired LibraryCalendar calendar;
    @Autowired LibraryClock clock;
    @Autowired ClosedDayRepository closedDays;

    PatronCategory category;

    @BeforeEach
    void category() {
        category = new PatronCategory("Test " + UUID.randomUUID());
        category.setLoanDays(7);
        category.setMaxLoans(2);
        category.setMaxRenewals(1);
        category.setFinePerDay(new BigDecimal("5.00"));
        category.setMaxUnpaidFines(new BigDecimal("100.00"));
        category.setHoldPickupDays(3);
        category = categories.save(category);
    }

    Patron patron() {
        Patron p = new Patron("T-" + UUID.randomUUID().toString().substring(0, 8));
        p.setFirstName("Test");
        p.setLastName("Patron");
        p.setCategory(category);
        return patrons.save(p);
    }

    Item copy(BibRecord r) {
        return items.save(new Item(r, "TB-" + UUID.randomUUID().toString().substring(0, 10)));
    }

    BibRecord title(MaterialType type) {
        BibRecord r = new BibRecord();
        r.setTitle("Test title " + UUID.randomUUID());
        r.setMaterialType(type);
        return records.save(r);
    }

    @Test
    void dueDateIsAnOpenDayAtLeastTheLoanPeriodAway() {
        Patron p = patron();
        Item i = copy(title(MaterialType.BOOK));
        CheckoutResult r = circulation.checkout(p.getCardNumber(), i.getBarcode(), false);
        LocalDate due = clock.toDate(r.loan().dueAt());
        assertTrue(calendar.isOpen(due), "due date must be an open day");
        assertTrue(!due.isBefore(clock.today().plusDays(7)), "due no earlier than the loan period");
        assertEquals(ItemStatus.ON_LOAN, items.findById(i.getId()).orElseThrow().getStatus());
    }

    @Test
    void loanLimitNeedsAnOverride() {
        Patron p = patron();
        BibRecord r = title(MaterialType.BOOK);
        circulation.checkout(p.getCardNumber(), copy(r).getBarcode(), false);
        circulation.checkout(p.getCardNumber(), copy(title(MaterialType.BOOK)).getBarcode(), false);
        String third = copy(title(MaterialType.BOOK)).getBarcode();
        ApiException e = assertThrows(ApiException.class, () -> circulation.checkout(p.getCardNumber(), third, false));
        assertTrue(e.getMessage().contains("Loan limit"));
        CheckoutResult overridden = circulation.checkout(p.getCardNumber(), third, true);
        assertEquals(1, overridden.warnings().size());
    }

    @Test
    void lateReturnIsFinedForOpenDaysOnly() {
        Patron p = patron();
        Item i = copy(title(MaterialType.BOOK));
        Instant borrowed = Instant.now().minus(Duration.ofDays(20));
        CheckoutResult out = circulation.checkout(p.getCardNumber(), i.getBarcode(), false, borrowed);
        CheckinResult in = circulation.checkin(i.getBarcode());
        LocalDate due = clock.toDate(out.loan().dueAt());
        long openDaysLate = calendar.snapshot(due, clock.today()).openDaysAfter(due, clock.today());
        assertTrue(openDaysLate > 0);
        assertEquals(0, new BigDecimal("5.00").multiply(BigDecimal.valueOf(openDaysLate)).compareTo(in.fineAmount()));
    }

    @Test
    void graceDaysAndCapLimitTheFine() {
        category.setFineGraceDays(2);
        category.setMaxFinePerItem(new BigDecimal("12.00"));
        Patron p = patron();
        Item i = copy(title(MaterialType.BOOK));
        circulation.checkout(p.getCardNumber(), i.getBarcode(), false, Instant.now().minus(Duration.ofDays(30)));
        CheckinResult in = circulation.checkin(i.getBarcode());
        assertEquals(0, new BigDecimal("12.00").compareTo(in.fineAmount()));
    }

    @Test
    void reservedCopyGoesToTheHoldShelfForThatPatronOnly() {
        Patron borrower = patron();
        Patron waiting = patron();
        Patron other = patron();
        BibRecord r = title(MaterialType.BOOK);
        Item only = copy(r);
        circulation.checkout(borrower.getCardNumber(), only.getBarcode(), false);
        HoldDto hold = circulation.placeHold(waiting.getId(), r.getId());
        assertEquals(HoldStatus.PENDING, hold.status());

        CheckinResult back = circulation.checkin(only.getBarcode());
        assertNotNull(back.holdForPatron(), "returned copy is routed to the hold shelf");
        assertEquals(ItemStatus.ON_HOLD, items.findById(only.getId()).orElseThrow().getStatus());

        assertThrows(ApiException.class, () -> circulation.checkout(other.getCardNumber(), only.getBarcode(), false));
        circulation.checkout(waiting.getCardNumber(), only.getBarcode(), false);
        assertTrue(circulation.holdsOf(waiting.getId()).stream()
                .anyMatch(h -> h.id().equals(hold.id()) && h.status() == HoldStatus.FULFILLED));
    }

    @Test
    void reservationLimitPerCategory() {
        category.setMaxHolds(1);
        Patron p = patron();
        Patron borrower = patron();
        BibRecord a = title(MaterialType.BOOK);
        BibRecord b = title(MaterialType.BOOK);
        circulation.checkout(borrower.getCardNumber(), copy(a).getBarcode(), false);
        circulation.checkout(borrower.getCardNumber(), copy(b).getBarcode(), false);
        circulation.placeHold(p.getId(), a.getId());
        ApiException e = assertThrows(ApiException.class, () -> circulation.placeHold(p.getId(), b.getId()));
        assertTrue(e.getMessage().contains("Reservation limit"));
    }

    @Test
    void roomUseOnlyMaterialCannotLeave() {
        Patron p = patron();
        Item ref = copy(title(MaterialType.REFERENCE));
        ref.setRoomUseOnly(true);
        ApiException e = assertThrows(ApiException.class, () -> circulation.checkout(p.getCardNumber(), ref.getBarcode(), false));
        assertTrue(e.getMessage().contains("Room use only"));
    }

    @Test
    void offlineReturnFromBeforeTheCurrentLoanIsNotApplied() {
        Patron p = patron();
        Item i = copy(title(MaterialType.BOOK));
        circulation.checkout(p.getCardNumber(), i.getBarcode(), false);
        Instant yesterday = Instant.now().minus(Duration.ofDays(1));
        ApiException e = assertThrows(ApiException.class, () -> circulation.checkin(i.getBarcode(), yesterday));
        assertTrue(e.getMessage().contains("lent again"));
    }

    @Test
    void returnWithFinesNeedsPayOrWaiveAndPayingIssuesOneReceipt() {
        Patron p = patron();
        Item i = copy(title(MaterialType.BOOK));
        circulation.checkout(p.getCardNumber(), i.getBarcode(), false, Instant.now().minus(Duration.ofDays(20)));
        assertThrows(ApiException.class, () -> circulation.checkinSettled(i.getBarcode(), null, null));
        assertThrows(ApiException.class, () -> circulation.checkinSettled(i.getBarcode(), Settle.WAIVE, " "));
        CheckinResult paid = circulation.checkinSettled(i.getBarcode(), Settle.PAY, null);
        assertNotNull(paid.receipt(), "paying with the return issues a receipt");
        assertEquals(ItemStatus.AVAILABLE, items.findById(i.getId()).orElseThrow().getStatus());
    }

    @Test
    void aDueDateLaterDeclaredClosedIsNotFined() {
        Patron p = patron();
        Item i = copy(title(MaterialType.BOOK));
        CheckoutResult out = circulation.checkout(p.getCardNumber(), i.getBarcode(), false, Instant.now().minus(Duration.ofDays(20)));
        LocalDate due = clock.toDate(out.loan().dueAt());
        closedDays.save(new ClosedDay(due, "Declared after the loan"));
        CheckinResult in = circulation.checkin(i.getBarcode());
        LibraryCalendar.Snapshot cal = calendar.snapshot(due, clock.today());
        long charged = cal.openDaysAfter(cal.nextOpenOnOrAfter(due), clock.today());
        assertEquals(0, new BigDecimal("5.00").multiply(BigDecimal.valueOf(charged)).compareTo(in.fineAmount()),
                "late days count from the next open day after the closed due date");
    }
}
