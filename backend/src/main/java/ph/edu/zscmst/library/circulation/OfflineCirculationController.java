package ph.edu.zscmst.library.circulation;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import ph.edu.zscmst.library.circulation.CirculationDtos.CheckinResult;
import ph.edu.zscmst.library.circulation.CirculationDtos.CheckoutResult;
import ph.edu.zscmst.library.common.ApiException;

/**
 * Uploads checkouts and check-ins recorded at the desk while the network or server was down. They are
 * applied in the order they happened, with their original times; each succeeds or fails on its own.
 */
@RestController
public class OfflineCirculationController {

    /** Offline transactions older than this are refused (the queue is meant to bridge an outage). */
    private static final Duration MAX_AGE = Duration.ofDays(30);
    /** Allowed clock difference between the desk PC and the server. */
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(10);

    public record OfflineTransaction(@NotBlank @Size(max = 64) String id,
                                     @NotNull @Pattern(regexp = "CHECKOUT|CHECKIN") String type,
                                     @Size(max = 64) String patron,
                                     @NotBlank @Size(max = 64) String item,
                                     @NotNull Instant at) {
    }

    public record SyncRequest(@NotNull @Size(max = 500) List<@Valid OfflineTransaction> transactions) {
    }

    /** {@code ok=false} means the transaction was not applied; {@code message} says why, or what to do next. */
    public record SyncResult(String id, String type, String item, boolean ok, String message, List<String> warnings) {
    }

    private final CirculationService service;

    public OfflineCirculationController(CirculationService service) {
        this.service = service;
    }

    @PostMapping("/api/circulation/offline-sync")
    public List<SyncResult> sync(@Valid @RequestBody SyncRequest req) {
        Instant now = Instant.now();
        List<SyncResult> results = new ArrayList<>();
        req.transactions().stream().sorted(Comparator.comparing(OfflineTransaction::at)).forEach(t -> {
            try {
                if (t.at().isAfter(now.plus(CLOCK_SKEW))) throw ApiException.badRequest("Time is in the future; check the desk PC's clock");
                if (t.at().isBefore(now.minus(MAX_AGE))) throw ApiException.badRequest("Older than 30 days; enter it manually");
                Instant at = t.at().isAfter(now) ? now : t.at();
                if (t.type().equals("CHECKOUT")) {
                    if (t.patron() == null || t.patron().isBlank()) throw ApiException.badRequest("Patron is missing");
                    // The item already left the library, so loan rules become warnings for staff to review.
                    CheckoutResult r = service.checkout(t.patron(), t.item(), true, at);
                    results.add(new SyncResult(t.id(), t.type(), t.item(), true,
                            r.loan().title() + " → " + r.loan().patronName() + (r.notice() == null ? "" : ". " + r.notice()),
                            r.warnings()));
                } else {
                    CheckinResult r = service.checkin(t.item(), at);
                    String fine = r.fineAmount().signum() > 0 ? " Fine PHP " + r.fineAmount() + "." : "";
                    results.add(new SyncResult(t.id(), t.type(), t.item(), true, r.message() + "." + fine, List.of()));
                }
            } catch (ApiException e) {
                results.add(new SyncResult(t.id(), t.type(), t.item(), false, e.getMessage(), List.of()));
            } catch (RuntimeException e) {
                results.add(new SyncResult(t.id(), t.type(), t.item(), false, "Could not be applied: " + e.getMessage(), List.of()));
            }
        });
        return results;
    }
}
