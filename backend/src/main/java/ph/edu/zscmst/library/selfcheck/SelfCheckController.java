package ph.edu.zscmst.library.selfcheck;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import ph.edu.zscmst.library.catalog.ItemRepository;
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

    public record ScanRequest(@NotBlank @Size(max = 64) String code) {
    }

    /** What a code scanned on the start screen is: a library item (returned without a PIN) or a card. */
    public record ScanKind(boolean item) {
    }

    public record Returned(String title, String barcode, Instant dueAt, Instant returnedAt, BigDecimal fine, String message) {
    }

    private final CirculationService circulation;
    private final SettingsService settings;
    private final SystemSettings system;
    private final AuditService audit;
    private final RenewalService renewals;
    private final KioskPatrons kioskPatrons;
    private final ItemRepository items;
    private final PatronRepository patrons;

    public SelfCheckController(CirculationService circulation,
                               SettingsService settings, SystemSettings system, AuditService audit,
                               RenewalService renewals, KioskPatrons kioskPatrons, ItemRepository items,
                               PatronRepository patrons) {
        this.items = items;
        this.patrons = patrons;
        this.renewals = renewals;
        this.kioskPatrons = kioskPatrons;
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
        return kioskPatrons.requirePin();
    }

    /**
     * Patrons often scan a book on the start screen to return it. A code that is a library item (and not
     * also a card) is reported as one, so the kiosk returns it instead of asking for a PIN.
     */
    @PostMapping("/api/kiosk/selfcheck/scan")
    public ScanKind scan(@Valid @RequestBody ScanRequest req) {
        String code = req.code().trim();
        return new ScanKind(config().allowReturns() && patrons.findByIdentifier(code).isEmpty()
                && items.findByIdentifier(code).isPresent());
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
        // Fines tied to this borrowing (overdue, damage...) are paid at the desk before the return is completed.
        if (circulation.previewCheckin(req.item()).total().signum() > 0) {
            throw ApiException.conflict("This item has fines to settle. Please return it at the circulation desk.");
        }
        CheckinResult r = circulation.checkin(req.item());
        // Patrons put the item on the return cart; staff route it to the hold shelf or the shelves.
        String message = r.loan() == null ? r.message() : "Thank you! Please place the item on the return cart.";
        LoanDto l = r.loan();
        return new Returned(l == null ? null : l.title(), l == null ? null : l.barcode(), l == null ? null : l.dueAt(),
                l == null ? null : l.returnedAt(), r.fineAmount(), message);
    }

    private PatronView view(Patron p) {
        PatronSummary s = circulation.summary(p.getId());
        return new PatronView(p.getFirstName(), p.getFullName(), p.getCardNumber(), s.loans(), s.maxLoans(),
                s.unpaidTotal(), s.blocks());
    }

    private Patron verify(Identify req) {
        return kioskPatrons.verify(req.card(), req.pin(), "Self-checkout kiosk");
    }
}
