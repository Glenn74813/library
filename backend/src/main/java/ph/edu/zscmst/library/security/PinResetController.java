package ph.edu.zscmst.library.security;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import ph.edu.zscmst.library.common.ApiException;
import ph.edu.zscmst.library.common.AuditService;
import ph.edu.zscmst.library.common.SystemSettings;
import ph.edu.zscmst.library.notice.SettingsNoticeSender;
import ph.edu.zscmst.library.patron.Patron;
import ph.edu.zscmst.library.patron.PatronRepository;

/**
 * "Forgot PIN": a patron gives their card number and the e-mail on record, receives a 6-digit
 * code valid for 15 minutes, and sets a new PIN with it. Answers never reveal whether a card or
 * e-mail exists.
 */
@RestController
public class PinResetController {

    private static final Logger log = LoggerFactory.getLogger(PinResetController.class);
    private static final Duration VALID_FOR = Duration.ofMinutes(15);
    private static final int MAX_CODES_PER_HOUR = 3;
    private static final int MAX_ATTEMPTS = 5;
    /** Advisory-lock key space for PIN reset requests (patron id is added). */
    private static final long LOCK_SPACE = 0x50494E0000000000L;
    private static final SecureRandom RANDOM = new SecureRandom();

    public record ForgotRequest(@NotBlank @Size(max = 40) String card, @NotBlank @Size(max = 150) String email) {
    }

    public record ResetRequest(@NotBlank @Size(max = 40) String card,
                               @NotBlank @Pattern(regexp = "\\d{6}", message = "must be the 6-digit code") String code,
                               @NotBlank @Pattern(regexp = "\\d{4,8}", message = "must be 4 to 8 digits") String newPin) {
    }

    private final PatronRepository patrons;
    private final PasswordEncoder passwords;
    private final JdbcTemplate jdbc;
    private final SettingsNoticeSender mail;
    private final SystemSettings system;
    private final TokenService tokens;
    private final AuditService audit;

    public PinResetController(PatronRepository patrons, PasswordEncoder passwords, JdbcTemplate jdbc,
                              SettingsNoticeSender mail, SystemSettings system, TokenService tokens, AuditService audit) {
        this.patrons = patrons;
        this.passwords = passwords;
        this.jdbc = jdbc;
        this.mail = mail;
        this.system = system;
        this.tokens = tokens;
        this.audit = audit;
    }

    /**
     * Always gives the same answer, whether or not the card and e-mail match, the hourly limit is reached or
     * e-mail fails, so it can't be used to test card/e-mail pairs. The e-mail is sent after the reply.
     */
    @PostMapping("/api/auth/pin-reset/request")
    @Transactional
    public Map<String, String> request(@Valid @RequestBody ForgotRequest req) {
        Map<String, String> answer = Map.of("message", "If the ID number and e-mail match our records, a 6-digit code "
                + "was sent to that e-mail. It is valid for 15 minutes.");
        Patron p = patrons.findByCardNumberIgnoreCase(req.card().trim()).orElse(null);
        if (p == null || p.getEmail() == null || !p.getEmail().trim().equalsIgnoreCase(req.email().trim())
                || p.getAnonymizedAt() != null) {
            audit.record(req.card().trim(), "PIN_RESET_NO_MATCH", null);
            return answer;
        }
        // One request per patron at a time, so parallel requests can't get past the hourly limit.
        jdbc.query("select pg_advisory_xact_lock(?)", rs -> null, LOCK_SPACE + p.getId());
        Integer recent = jdbc.queryForObject("select count(*) from pin_reset where patron_id = ? and created_at > ?",
                Integer.class, p.getId(), Timestamp.from(Instant.now().minus(Duration.ofHours(1))));
        if (recent != null && recent >= MAX_CODES_PER_HOUR) {
            audit.record(p.getCardNumber(), "PIN_RESET_LIMIT", "hourly code limit reached");
            return answer;
        }
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        jdbc.update("insert into pin_reset (patron_id, code_hash, expires_at) values (?, ?, ?)",
                p.getId(), passwords.encode(code), Timestamp.from(Instant.now().plus(VALID_FOR)));
        audit.record(p.getCardNumber(), "PIN_RESET_REQUESTED", "code e-mailed");
        String email = p.getEmail();
        String card = p.getCardNumber();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                CompletableFuture.runAsync(() -> {
                    try {
                        mail.sendEmail(email, mail.subjectPrefix() + "PIN reset code",
                                "Your code to set a new library PIN is " + code + ".\n\nIt is valid for 15 minutes. "
                                        + "If you did not ask for this, ignore this e-mail; your PIN has not changed.\n\n"
                                        + system.profile().name());
                    } catch (RuntimeException e) {
                        log.warn("PIN reset e-mail to patron {} failed: {}", card, e.getMessage());
                    }
                });
            }
        });
        return answer;
    }

    @PostMapping("/api/auth/pin-reset/confirm")
    @Transactional(noRollbackFor = ApiException.class)
    public Map<String, String> confirm(@Valid @RequestBody ResetRequest req) {
        ApiException invalid = ApiException.badRequest("The code is wrong or has expired. Request a new one.");
        Patron p = patrons.findByCardNumberIgnoreCase(req.card().trim()).orElseThrow(() -> invalid);
        List<Map<String, Object>> codes = jdbc.queryForList("""
                select id, code_hash, attempts from pin_reset
                where patron_id = ? and used_at is null and expires_at > now() order by created_at desc limit 1""",
                p.getId());
        if (codes.isEmpty()) throw invalid;
        Map<String, Object> c = codes.getFirst();
        long id = ((Number) c.get("id")).longValue();
        // Claim the attempt before checking, so parallel guesses can't exceed the limit.
        if (jdbc.update("update pin_reset set attempts = attempts + 1 where id = ? and attempts < ?", id, MAX_ATTEMPTS) == 0) {
            throw invalid;
        }
        if (!passwords.matches(req.code(), (String) c.get("code_hash"))) throw invalid;
        jdbc.update("update pin_reset set used_at = now() where patron_id = ? and used_at is null", p.getId());
        p.setPinHash(passwords.encode(req.newPin()));
        p.unlockPin();
        patrons.save(p);
        tokens.revokeAll(TokenService.SubjectType.PATRON, p.getId());
        audit.record(p.getCardNumber(), "PIN_RESET", "new PIN set with e-mailed code");
        return Map.of("message", "Your new PIN is set. You can sign in now.");
    }
}
