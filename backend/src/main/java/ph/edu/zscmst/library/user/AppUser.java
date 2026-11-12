package ph.edu.zscmst.library.user;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String username;
    private String passwordHash;
    private String fullName;
    @Enumerated(EnumType.STRING)
    private Role role;
    private boolean enabled = true;
    private int failedLogins;
    private Instant lockedUntil;
    private Instant createdAt = Instant.now();
    private int tokenVersion;
    private boolean mustChangePassword;
    private String deviceKeyHash;
    private Instant deviceKeyIssuedAt;
    private Instant deviceLastSeenAt;

    protected AppUser() {
    }

    public AppUser(String username, String passwordHash, String fullName, Role role) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.role = role;
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Instant getCreatedAt() { return createdAt; }
    public int getFailedLogins() { return failedLogins; }
    public Instant getLockedUntil() { return lockedUntil; }
    public boolean isLocked(Instant now) { return lockedUntil != null && lockedUntil.isAfter(now); }

    /** Records a failed sign-in; locks the account for {@code lockMinutes} after {@code max} failures in a row. */
    public void loginFailed(int max, int lockMinutes, Instant now) {
        failedLogins++;
        if (failedLogins >= max) {
            lockedUntil = now.plusSeconds(lockMinutes * 60L);
            failedLogins = 0;
        }
    }

    public void unlock() {
        failedLogins = 0;
        lockedUntil = null;
    }

    public int getTokenVersion() { return tokenVersion; }

    /** Invalidates every device token issued to this account so far. */
    public void revokeDeviceTokens() { tokenVersion++; }

    public boolean isMustChangePassword() { return mustChangePassword; }
    public void setMustChangePassword(boolean mustChangePassword) { this.mustChangePassword = mustChangePassword; }

    /** People (not kiosk or gate devices) choose their own password after an administrator sets one. */
    public boolean isPerson() { return role != Role.KIOSK && role != Role.GATE; }

    public String getDeviceKeyHash() { return deviceKeyHash; }
    public Instant getDeviceKeyIssuedAt() { return deviceKeyIssuedAt; }
    public Instant getDeviceLastSeenAt() { return deviceLastSeenAt; }
    public void setDeviceLastSeenAt(Instant at) { this.deviceLastSeenAt = at; }

    /** Replaces (or, with null, removes) this device's key; the old key stops working at once. */
    public void setDeviceKey(String hash) {
        this.deviceKeyHash = hash;
        this.deviceKeyIssuedAt = hash == null ? null : Instant.now();
    }

    public boolean isDevice() { return role == Role.KIOSK || role == Role.GATE; }
}
