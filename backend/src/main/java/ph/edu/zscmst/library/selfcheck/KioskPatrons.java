package ph.edu.zscmst.library.selfcheck;

import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import ph.edu.zscmst.library.common.ApiException;
import ph.edu.zscmst.library.common.AuditService;
import ph.edu.zscmst.library.common.SettingsService;
import ph.edu.zscmst.library.patron.Patron;
import ph.edu.zscmst.library.patron.PatronRepository;
import ph.edu.zscmst.library.security.LoginThrottle;
import ph.edu.zscmst.library.security.LoginThrottle.Account;

/**
 * Identifies a patron at a kiosk (self-checkout, catalog) by card barcode or RFID, plus their PIN when the
 * library requires one (System settings → kiosks). Wrong PINs count towards the same lockout as My Library.
 */
@Component
public class KioskPatrons {

    private final PatronRepository patrons;
    private final PasswordEncoder passwords;
    private final SettingsService settings;
    private final AuditService audit;
    private final LoginThrottle throttle;

    public KioskPatrons(PatronRepository patrons, PasswordEncoder passwords, SettingsService settings, AuditService audit,
                        LoginThrottle throttle) {
        this.patrons = patrons;
        this.passwords = passwords;
        this.settings = settings;
        this.audit = audit;
        this.throttle = throttle;
    }

    public boolean requirePin() {
        return !"false".equals(settings.get("selfcheck.requirePin", "true"));
    }

    /** Runs outside a transaction so a wrong PIN is counted even though the request then fails. */
    public Patron verify(String card, String pin, String where) {
        Patron p = patrons.findByIdentifier(card.trim()).filter(x -> x.getAnonymizedAt() == null)
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
            if (pin == null || !passwords.matches(pin, p.getPinHash())) {
                audit.record(p.getCardNumber(), "SELFCHECK_PIN_FAILED", where);
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
