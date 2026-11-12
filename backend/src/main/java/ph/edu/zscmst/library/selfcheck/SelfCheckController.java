package ph.edu.zscmst.library.selfcheck;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import ph.edu.zscmst.library.circulation.CirculationDtos.CheckinResult;
import ph.edu.zscmst.library.circulation.CirculationDtos.CheckoutResult;
import ph.edu.zscmst.library.circulation.CirculationDtos.LoanDto;
import ph.edu.zscmst.library.circulation.CirculationDtos.PatronSummary;
import ph.edu.zscmst.library.circulation.CirculationService;
import ph.edu.zscmst.library.circulation.RenewalRequest;
import ph.edu.zscmst.library.circulation.RenewalService;
import ph.edu.zscmst.library.common.ApiException;
import ph.edu.zscmst.library.common.AuditService;
import ph.edu.zscmst.library.common.SettingsService;
import ph.edu.zscmst.library.common.SystemSettings;
import ph.edu.zscmst.library.patron.Patron;
import ph.edu.zscmst.library.patron.PatronRepository;
import ph.edu.zscmst.library.security.LoginThrottle;
import ph.edu.zscmst.library.security.LoginThrottle.Account;

/**
 * Self-checkout kiosk (KIOSK accounts): patrons identify with their card (barcode or RFID) and PIN,
 * then borrow, renew and return items under the same loan rules as the desk.
 */
@RestController
public class SelfCheckController {

    public record Identify(@NotBlank @Size(max = 64) String card, @Size(max = 20) String pin) {
    }

    public record ItemRequest(@NotNull @Valid Identify patron, @NotBlank @Size(max = 64) String item) {
    }

    public record RenewRequest(@NotNull @Valid Identify patron, @NotNull Long loanId) {
    }

    public record ReturnRequest(@NotBlank @Size(max = 64) String item) {
    }

    /** What the patron sees: no contact details, only their own loans and status. */
    public record PatronView(String firstName, String fullName, String cardNumber, List<LoanDto> loans, int maxLoans,
                             BigDecimal unpaidTotal, List<String> blocks) {
    }

    public record Config(boolean requirePin, boolean allowReturns, int idleSeconds) {
    }

    public record Checkout(String title, String barcode, Instant dueAt, String notice, PatronView patron) {
    }

    /** {@code pending}: the renewal waits for staff approval. */
    public record Renewed(boolean pending, String message, PatronView patron) {
    }

    public record Returned(String title, Instant dueAt, BigDecimal fine, String message) {
    }

    private final PatronRepository patrons;
    private final PasswordEncoder passwords;
    private final CirculationService circulation;
    private final SettingsService settings;
    private final SystemSettings system;
    private final AuditService audit;
    private final RenewalService renewals;
    private final LoginThrottle throttle;

    public SelfCheckController(PatronRepository patrons, PasswordEncoder passwords, CirculationService circulation,
                               SettingsService settings, SystemSettings system, AuditService audit,
                               RenewalService renewals, LoginThrottle throttle) {
        this.renewals = renewals;
        this.throttle = throttle;
        this.patrons = patrons;
        this.passwords = passwords;
        this.circulation = circulation;
        this.settings = settings;
        this.system = system;
        this.audit = audit;
    }

    @GetMapping("/api/kiosk/selfcheck/config")
    public Config config() {
        return new Config(requirePin(), !"false".equals(settings.get("selfcheck.allowReturns", "true")),
                system.opacResetSeconds());
    }

    private boolean requirePin() {
        return !"false".equals(settings.get("selfcheck.requirePin", "true"));
    }

    @PostMapping("/api/kiosk/selfcheck/identify")
    public PatronView identify(@Valid @RequestBody Identify req) {
        return view(verify(req));
    }

    @PostMapping("/api/kiosk/selfcheck/checkout")
    public Checkout checkout(@Valid @RequestBody ItemRequest req) {
        Patron p = verify(req.patron());
        // No overrides at the kiosk: anything the rules refuse is sent to the desk.
        CheckoutResult r = circulation.checkout(p.getCardNumber(), req.item(), false);
        return new Checkout(r.loan().title(), r.loan().barcode(), r.loan().dueAt(), r.notice(), view(p));
    }

    @PostMapping("/api/kiosk/selfcheck/renew")
    public Renewed renew(@Valid @RequestBody RenewRequest req) {
        Patron p = verify(req.patron());
        RenewalService.Outcome o = renewals.request(req.loanId(), p.getId(), RenewalRequest.Channel.KIOSK);
        return new Renewed(o.pending(), o.pending() ? "Renewal requested — the library will approve or reject it"
                : "Renewed", view(p));
    }

    @PostMapping("/api/kiosk/selfcheck/return")
    public Returned giveBack(@Valid @RequestBody ReturnRequest req) {
        if (!config().allowReturns()) throw ApiException.badRequest("Please return items at the circulation desk");
        CheckinResult r = circulation.checkin(req.item());
        // Patrons put the item on the return cart; staff route it to the hold shelf or the shelves.
        String message = r.loan() == null ? r.message() : "Thank you! Please place the item on the return cart.";
        return new Returned(r.loan() == null ? null : r.loan().title(), r.loan() == null ? null : r.loan().dueAt(),
                r.fineAmount(), message);
    }

    private PatronView view(Patron p) {
        PatronSummary s = circulation.summary(p.getId());
        return new PatronView(p.getFirstName(), p.getFullName(), p.getCardNumber(), s.loans(), s.maxLoans(),
                s.unpaidTotal(), s.blocks());
    }

    /** Runs outside a transaction so a wrong PIN is counted even though the request then fails. */
    private Patron verify(Identify req) {
        Patron p = patrons.findByIdentifier(req.card().trim()).filter(x -> x.getAnonymizedAt() == null)
                .orElseThrow(() -> ApiException.notFound("Library card"));
        Instant now = Instant.now();
        if (p.isPinLocked(now)) {
            throw new ApiException(HttpStatus.LOCKED, "Too many wrong PINs. Please ask at the circulation desk.");
        }
        if (requirePin()) {
            if (p.getPinHash() == null) {
                throw ApiException.badRequest("You don't have a PIN yet. Please ask at the circulation desk.");
            }
            LoginThrottle.Claim claim = throttle.claim(Account.PATRON, p.getId(),
                    integer("security.maxAttempts", 5), integer("security.lockMinutes", 15), now);
            if (claim.locked() != null) {
                throw new ApiException(HttpStatus.LOCKED, "Too many wrong PINs. Please ask at the circulation desk.");
            }
            if (req.pin() == null || !passwords.matches(req.pin(), p.getPinHash())) {
                audit.record(p.getCardNumber(), "SELFCHECK_PIN_FAILED", "Self-checkout kiosk");
                // 403, not 401: the kiosk would treat 401 as an expired session and resend the request.
                throw new ApiException(HttpStatus.FORBIDDEN, "Wrong PIN");
            }
            throttle.succeeded(Account.PATRON, p.getId());
        }
        if (p.isBlocked()) throw ApiException.conflict("Your account is blocked. Please see the circulation desk.");
        return p;
    }

    private int integer(String key, int fallback) {
        try {
            return Math.max(1, Integer.parseInt(settings.get(key, String.valueOf(fallback)).trim()));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
