package ph.edu.zscmst.library.security;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import ph.edu.zscmst.library.common.ApiException;
import ph.edu.zscmst.library.common.AuditService;
import ph.edu.zscmst.library.patron.Patron;
import ph.edu.zscmst.library.patron.PatronRepository;
import ph.edu.zscmst.library.user.AppUser;
import ph.edu.zscmst.library.user.AppUserRepository;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record PatronLoginRequest(@NotBlank String cardNumber, @NotBlank String pin) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    public record ChangePasswordRequest(@NotBlank String currentPassword,
                                        @NotBlank @Size(min = 8, max = 100) String newPassword) {
    }

    public record AuthResponse(String accessToken, Instant accessTokenExpiresAt, String refreshToken,
                               Instant refreshTokenExpiresAt, TokenService.PrincipalInfo user) {
        static AuthResponse of(TokenService.TokenPair pair) {
            return new AuthResponse(pair.access().value(), pair.access().expiresAt(), pair.refresh().value(),
                    pair.refresh().expiresAt(), pair.principal());
        }
    }

    private final AppUserRepository users;
    private final PatronRepository patrons;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokens;
    private final AuditService audit;
    /** Compared against for unknown accounts so they take as long to reject as wrong passwords. */
    private final String dummyHash;

    public AuthController(AppUserRepository users, PatronRepository patrons, PasswordEncoder passwordEncoder,
                          TokenService tokens, AuditService audit) {
        this.users = users;
        this.patrons = patrons;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.audit = audit;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest req) {
        AppUser user = users.findByUsernameIgnoreCase(req.username().trim()).orElse(null);
        String hash = user != null ? user.getPasswordHash() : dummyHash;
        if (!passwordEncoder.matches(req.password(), hash) || user == null || !user.isEnabled()) {
            audit.record(req.username(), "LOGIN_FAILED", "Staff login");
            throw new BadCredentialsException("Invalid username or password");
        }
        audit.record(user.getUsername(), "LOGIN", "Staff login");
        return AuthResponse.of(tokens.issue(user));
    }

    @PostMapping("/patron-login")
    public AuthResponse patronLogin(@Valid @RequestBody PatronLoginRequest req) {
        Patron patron = patrons.findByCardNumberIgnoreCase(req.cardNumber().trim()).orElse(null);
        String hash = patron != null && patron.getPinHash() != null ? patron.getPinHash() : dummyHash;
        if (!passwordEncoder.matches(req.pin(), hash) || patron == null || patron.getPinHash() == null) {
            audit.record(req.cardNumber(), "LOGIN_FAILED", "Patron login");
            throw new BadCredentialsException("Invalid card number or PIN");
        }
        return AuthResponse.of(tokens.issue(patron));
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest req) {
        return AuthResponse.of(tokens.refresh(req.refreshToken()));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshRequest req) {
        tokens.revoke(req.refreshToken());
    }

    @GetMapping("/me")
    public TokenService.PrincipalInfo me(@AuthenticationPrincipal Jwt jwt) {
        Number patronId = jwt.getClaim("patronId");
        return new TokenService.PrincipalInfo(jwt.getSubject(), jwt.getClaimAsString("username"),
                jwt.getClaimAsString("name"), jwt.getClaimAsStringList("roles") == null ? List.of()
                : jwt.getClaimAsStringList("roles"), patronId == null ? null : patronId.longValue());
    }

    @PostMapping("/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void changePassword(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChangePasswordRequest req) {
        String subject = jwt.getSubject();
        if (!subject.startsWith("user:")) {
            throw ApiException.badRequest("Patrons change their PIN from the patron portal");
        }
        AppUser user = users.findById(Long.parseLong(subject.substring(5)))
                .orElseThrow(() -> ApiException.notFound("User"));
        if (!passwordEncoder.matches(req.currentPassword(), user.getPasswordHash())) {
            throw ApiException.badRequest("Current password is incorrect");
        }
        user.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        tokens.revokeAll(TokenService.SubjectType.USER, user.getId());
        audit.record(user.getUsername(), "PASSWORD_CHANGE", null);
    }
}
