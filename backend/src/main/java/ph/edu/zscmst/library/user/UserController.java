package ph.edu.zscmst.library.user;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import ph.edu.zscmst.library.common.ApiException;
import ph.edu.zscmst.library.common.AuditService;
import ph.edu.zscmst.library.common.SettingsService;
import ph.edu.zscmst.library.security.TokenService;

/** Staff and device account management (ADMIN only). */
@RestController
@RequestMapping("/api/users")
public class UserController {

    public record UserDto(Long id, String username, String fullName, Role role, boolean enabled, Instant createdAt,
                          Instant lockedUntil) {
        static UserDto of(AppUser u) {
            return new UserDto(u.getId(), u.getUsername(), u.getFullName(), u.getRole(), u.isEnabled(), u.getCreatedAt(),
                    u.isLocked(Instant.now()) ? u.getLockedUntil() : null);
        }
    }

    public record SecuritySettings(@Min(3) @Max(20) int maxAttempts,
                                   @Min(1) @Max(1440) int lockMinutes) {
    }

    public record CreateUserRequest(@NotBlank @Size(max = 60) String username,
                                    @NotBlank @Size(min = 8, max = 100) String password,
                                    @NotBlank @Size(max = 150) String fullName,
                                    @NotNull Role role) {
    }

    public record UpdateUserRequest(@NotBlank @Size(max = 150) String fullName, @NotNull Role role, boolean enabled,
                                    @Size(min = 8, max = 100) String password) {
    }

    public record DeviceTokenResponse(String accessToken, Instant expiresAt) {
    }

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;
    private final TokenService tokenService;
    private final SettingsService settings;

    public UserController(AppUserRepository users, PasswordEncoder passwordEncoder, AuditService audit,
                          TokenService tokenService, SettingsService settings) {
        this.settings = settings;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.tokenService = tokenService;
    }

    @GetMapping
    public List<UserDto> list() {
        return users.findAllByOrderByUsernameAsc().stream().map(UserDto::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public UserDto create(@Valid @RequestBody CreateUserRequest req) {
        if (users.existsByUsernameIgnoreCase(req.username())) {
            throw ApiException.conflict("Username already exists");
        }
        AppUser user = new AppUser(req.username().trim(), passwordEncoder.encode(req.password()),
                req.fullName().trim(), req.role());
        user.setMustChangePassword(user.isPerson());
        user = users.save(user);
        audit.record("USER_CREATE", user.getUsername() + " (" + user.getRole() + ")");
        return UserDto.of(user);
    }

    @PutMapping("/{id}")
    @Transactional
    public UserDto update(@PathVariable Long id, @Valid @RequestBody UpdateUserRequest req) {
        AppUser user = users.findById(id).orElseThrow(() -> ApiException.notFound("User"));
        user.setFullName(req.fullName().trim());
        user.setRole(req.role());
        user.setEnabled(req.enabled());
        if (req.password() != null && !req.password().isBlank()) {
            user.setPasswordHash(passwordEncoder.encode(req.password()));
            // A password set by an administrator is temporary for people; devices keep theirs.
            user.setMustChangePassword(user.isPerson());
            user.unlock();
            tokenService.revokeAll(TokenService.SubjectType.USER, user.getId());
        }
        if (!req.enabled()) {
            tokenService.revokeAll(TokenService.SubjectType.USER, user.getId());
        }
        audit.record("USER_UPDATE", user.getUsername() + " role=" + user.getRole() + " enabled=" + user.isEnabled());
        return UserDto.of(user);
    }

    @PostMapping("/{id}/unlock")
    @Transactional
    public UserDto unlock(@PathVariable Long id) {
        AppUser user = users.findById(id).orElseThrow(() -> ApiException.notFound("User"));
        user.unlock();
        audit.record("USER_UNLOCK", user.getUsername());
        return UserDto.of(user);
    }

    @GetMapping("/security-settings")
    public SecuritySettings securitySettings() {
        return new SecuritySettings(Integer.parseInt(settings.get("security.maxAttempts", "5")),
                Integer.parseInt(settings.get("security.lockMinutes", "15")));
    }

    @PutMapping("/security-settings")
    public SecuritySettings updateSecuritySettings(@Valid @RequestBody SecuritySettings req) {
        settings.put("security.maxAttempts", String.valueOf(req.maxAttempts()));
        settings.put("security.lockMinutes", String.valueOf(req.lockMinutes()));
        audit.record("SECURITY_SETTINGS", "lock after " + req.maxAttempts() + " failures for " + req.lockMinutes() + " min");
        return securitySettings();
    }

    /**
     * Issues a long-lived access token for a KIOSK or GATE device account, so unattended hardware
     * does not need to store a password.
     */
    @PostMapping("/{id}/device-token")
    public DeviceTokenResponse deviceToken(@PathVariable Long id) {
        AppUser user = users.findById(id).orElseThrow(() -> ApiException.notFound("User"));
        if (user.getRole() != Role.KIOSK && user.getRole() != Role.GATE) {
            throw ApiException.badRequest("Device tokens can only be issued to KIOSK or GATE accounts");
        }
        if (!user.isEnabled()) {
            throw ApiException.badRequest("Account is disabled");
        }
        TokenService.IssuedToken token = tokenService.issueDeviceToken(user);
        audit.record("DEVICE_TOKEN_ISSUED", user.getUsername());
        return new DeviceTokenResponse(token.value(), token.expiresAt());
    }

    /** Invalidates every device token issued to this account (e.g. a gate PC was replaced or stolen). */
    @PostMapping("/{id}/device-token/revoke")
    @org.springframework.transaction.annotation.Transactional
    public void revokeDeviceTokens(@PathVariable Long id) {
        AppUser user = users.findById(id).orElseThrow(() -> ApiException.notFound("User"));
        user.revokeDeviceTokens();
        users.save(user);
        audit.record("DEVICE_TOKEN_REVOKED", user.getUsername());
    }
}
