package ph.edu.zscmst.library.report;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import ph.edu.zscmst.library.catalog.ItemRepository;
import ph.edu.zscmst.library.catalog.ItemStatus;
import ph.edu.zscmst.library.catalog.LoanPolicy;
import ph.edu.zscmst.library.circulation.CirculationService;
import ph.edu.zscmst.library.circulation.RenewalRequest;
import ph.edu.zscmst.library.circulation.RenewalRequestRepository;
import ph.edu.zscmst.library.circulation.FineRepository;
import ph.edu.zscmst.library.circulation.HoldRequest;
import ph.edu.zscmst.library.circulation.HoldRequestRepository;
import ph.edu.zscmst.library.circulation.HoldStatus;
import ph.edu.zscmst.library.circulation.LoanRepository;
import ph.edu.zscmst.library.common.LibraryClock;
import ph.edu.zscmst.library.common.SettingsService;
import ph.edu.zscmst.library.gate.GateEventRepository;
import ph.edu.zscmst.library.gate.GateEventType;
import ph.edu.zscmst.library.inventory.StocktakeSessionRepository;

/**
 * Actionable tasks for the staff notification bell. Computed live from current data, so a task
 * disappears by itself once it has been dealt with (no "mark as read" bookkeeping).
 */
@RestController
public class TaskController {

    public enum Severity { DANGER, WARNING, INFO }

    public record Task(String id, Severity severity, String title, String detail, long count, String link) {
    }

    private final HoldRequestRepository holds;
    private final LoanRepository loans;
    private final FineRepository fines;
    private final ItemRepository items;
    private final CirculationService circulation;
    private final LoanPolicy policy;
    private final RenewalRequestRepository renewalRequests;
    private final GateEventRepository gateEvents;
    private final StocktakeSessionRepository stocktakes;
    private final SettingsService settings;
    private final LibraryClock clock;

    public TaskController(HoldRequestRepository holds, LoanRepository loans, FineRepository fines, ItemRepository items,
                          GateEventRepository gateEvents, StocktakeSessionRepository stocktakes,
                          SettingsService settings, LibraryClock clock, CirculationService circulation,
                          LoanPolicy policy, RenewalRequestRepository renewalRequests) {
        this.renewalRequests = renewalRequests;
        this.circulation = circulation;
        this.policy = policy;
        this.holds = holds;
        this.loans = loans;
        this.fines = fines;
        this.items = items;
        this.gateEvents = gateEvents;
        this.stocktakes = stocktakes;
        this.settings = settings;
        this.clock = clock;
    }

    private static String titles(List<HoldRequest> list) {
        List<String> names = list.stream().map(h -> h.getRecord().getTitle()).distinct().toList();
        return String.join(", ", names.subList(0, Math.min(3, names.size()))) + (names.size() > 3 ? " and more" : "");
    }

    @GetMapping("/api/tasks")
    @Transactional(readOnly = true)
    public List<Task> tasks() {
        List<Task> tasks = new ArrayList<>();
        Instant now = Instant.now();
        LocalDate today = clock.today();
        Instant startOfToday = clock.startOfDay(today);
        Instant endOfToday = clock.endOfDay(today);

        // Security first
        if (settings.getBoolean("gate.emergencyMode")) {
            tasks.add(new Task("gate-emergency", Severity.DANGER, "Emergency mode is ON",
                    "Gate alarms are off. Turn it off once the emergency is over.", 1, "/staff/gate"));
        }
        long alarms = gateEvents.countByEventTypeAndOccurredAtBetween(GateEventType.ALARM, startOfToday, endOfToday);
        if (alarms > 0) {
            tasks.add(new Task("gate-alarms", Severity.DANGER, alarms + " security gate alarm(s) today",
                    "Unborrowed items passed the gate. Review the event log.", alarms, "/staff/gate"));
        }
        long tamper = gateEvents.countByEventTypeAndOccurredAtBetween(GateEventType.TAMPER, startOfToday, endOfToday)
                + gateEvents.countByEventTypeAndOccurredAtBetween(GateEventType.POWER_LOSS, startOfToday, endOfToday);
        if (tamper > 0) {
            tasks.add(new Task("gate-tamper", Severity.WARNING, tamper + " gate tamper / power-loss event(s) today",
                    "Check the gate hardware.", tamper, "/staff/gate"));
        }
        Instant offlineCutoff = now.minusSeconds(300);
        long offline = gateEvents.lastSeenPerGate().stream()
                .filter(r -> !"system".equals(r[0]) && ((Instant) r[1]).isBefore(offlineCutoff)).count();
        if (offline > 0) {
            tasks.add(new Task("gate-offline", Severity.WARNING, offline + " gate(s) offline",
                    "No signal from the gate in the last 5 minutes.", offline, "/staff/gate"));
        }

        // Circulation
        long overdue = loans.countByReturnedAtIsNullAndDueAtBefore(now);
        if (overdue > 0) {
            tasks.add(new Task("loans-overdue", Severity.DANGER, overdue + " overdue item(s)",
                    "Follow up with the borrowers.", overdue, "/staff/loans"));
        }
        long expiring = holds.countByStatusAndExpiresAtBefore(HoldStatus.READY, endOfToday);
        if (expiring > 0) {
            tasks.add(new Task("holds-expiring", Severity.WARNING, expiring + " reserved item(s) expire today",
                    "Pickup period ends today; remind the patrons or release the copies.", expiring, "/staff/holds"));
        }
        long ready = holds.countByStatus(HoldStatus.READY);
        if (ready > 0) {
            tasks.add(new Task("holds-ready", Severity.INFO, ready + " reserved item(s) on the hold shelf",
                    "Waiting for the patrons to pick them up.", ready, "/staff/holds"));
        }
        long renewalsWaiting = renewalRequests.countByStatus(RenewalRequest.Status.PENDING);
        if (renewalsWaiting > 0) {
            tasks.add(new Task("renewals-pending", Severity.WARNING, renewalsWaiting + " renewal request(s) to approve",
                    "Patrons are waiting to hear whether they may keep the items longer.", renewalsWaiting, "/staff/renewals"));
        }
        List<HoldRequest> risky = circulation.bookingsAtRisk(policy.atRiskDays());
        List<HoldRequest> waiting = risky.stream().filter(h -> h.getStatus() == HoldStatus.PENDING).toList();
        List<HoldRequest> upcoming = risky.stream().filter(h -> h.getStatus() == HoldStatus.SCHEDULED).toList();
        if (!waiting.isEmpty()) {
            tasks.add(new Task("bookings-waiting", Severity.DANGER, waiting.size() + " booked patron(s) still waiting for a copy",
                    "Pickup date has come but no copy is back: " + titles(waiting)
                            + ". Contact the borrowers holding these titles.", waiting.size(), "/staff/holds"));
        }
        if (!upcoming.isEmpty()) {
            tasks.add(new Task("bookings-at-risk", Severity.WARNING, upcoming.size() + " booking(s) at risk",
                    "No copy on the shelf or due back in time for: " + titles(upcoming)
                            + ". Remind the borrowers to return them.", upcoming.size(), "/staff/holds"));
        }
        long bookingsTomorrow = holds.countByStatusAndPickupDate(HoldStatus.SCHEDULED, today.plusDays(1));
        if (bookingsTomorrow > 0) {
            tasks.add(new Task("bookings-tomorrow", Severity.INFO, bookingsTomorrow + " booking(s) for pickup tomorrow",
                    "Copies are set aside automatically in the morning.", bookingsTomorrow, "/staff/holds"));
        }
        long dueToday = loans.countByReturnedAtIsNullAndDueAtBetween(now, endOfToday);
        if (dueToday > 0) {
            tasks.add(new Task("loans-due-today", Severity.INFO, dueToday + " item(s) due today",
                    "Expect these returns at the desk.", dueToday, "/staff/loans"));
        }
        long expiredCards = loans.countPatronsWithExpiredCardAndLoans(today);
        if (expiredCards > 0) {
            tasks.add(new Task("patrons-expired", Severity.WARNING, expiredCards + " patron(s) with expired cards still borrowing",
                    "Renew their cards or collect the items.", expiredCards, "/staff/patrons"));
        }
        BigDecimal unpaid = fines.totalUnpaid();
        if (unpaid.signum() > 0) {
            tasks.add(new Task("fines-unpaid", Severity.INFO, "PHP " + unpaid + " in unpaid fines",
                    "Collect at the desk when patrons visit.", 1, "/staff/fines"));
        }

        // Collection
        long missing = items.countByStatus(ItemStatus.MISSING);
        if (missing > 0) {
            tasks.add(new Task("items-missing", Severity.WARNING, missing + " item(s) marked missing",
                    "Search the shelves; scan found items at check-in.", missing, "/staff/inventory"));
        }
        long openCounts = stocktakes.countByClosedAtIsNull();
        if (openCounts > 0) {
            tasks.add(new Task("stocktake-open", Severity.INFO, openCounts + " stocktake(s) still open",
                    "Finish scanning and close them.", openCounts, "/staff/inventory"));
        }
        return tasks;
    }
}
