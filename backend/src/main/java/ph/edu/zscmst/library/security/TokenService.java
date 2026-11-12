package ph.edu.zscmst.library.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ph.edu.zscmst.library.common.AuditService;
import ph.edu.zscmst.library.common.SystemSettings;
import ph.edu.zscmst.library.patron.Patron;
import ph.edu.zscmst.library.patron.PatronRepository;
import ph.edu.zscmst.library.user.AppUser;
import ph.edu.zscmst.library.user.AppUserRepository;

/**
 * Issues short-lived JWT access tokens and long-lived, single-use (rotating) refresh tokens.
 * Re-use of an already-rotated refresh token revokes every session of that subject.
 */
@Service
public class TokenService {

    public enum SubjectType { USER, PATRON }

    public record IssuedToken(String value, Instant expiresAt) {
    }

    public record TokenPair(IssuedToken access, IssuedToken refresh, PrincipalInfo principal) {
    }

    /** {@code mustChangePassword}: the account may only change its password until it does. */
    public record PrincipalInfo(String subject, String username, String displayName, List<String> roles, Long patronId,
                                boolean mustChangePassword) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JwtEncoder encoder;
    private final JwtProperties props;
    private final RefreshTokenRepository refreshTokens;
    private final AppUserRepository users;
    private final PatronRepository patrons;
    private final AuditService audit;
    private final SystemSettings system;

    public TokenService(JwtEncoder encoder, JwtProperties props, RefreshTokenRepository refreshTokens,
                        AppUserRepository users, PatronRepository patrons, AuditService audit, SystemSettings system) {
        this.system = system;
        this.encoder = encoder;
        this.props = props;
        this.refreshTokens = refreshTokens;
        this.users = users;
        this.patrons = patrons;
        this.audit = audit;
    }

    public static PrincipalInfo principalOf(AppUser user) {
        return new PrincipalInfo("user:" + user.getId(), user.getUsername(), user.getFullName(),
                List.of(user.getRole().name()), null, user.isMustChangePassword());
    }

    public static PrincipalInfo principalOf(Patron patron) {
        return new PrincipalInfo("patron:" + patron.getId(), patron.getCardNumber(), patron.getFullName(),
                List.of("PATRON"), patron.getId(), false);
    }

    @Transactional
    public TokenPair issue(AppUser user) {
        PrincipalInfo p = principalOf(user);
        return new TokenPair(accessToken(p, system.accessTokenTtl().toSeconds()),
                newRefreshToken(SubjectType.USER, user.getId()), p);
    }

    @Transactional
    public TokenPair issue(Patron patron) {
        PrincipalInfo p = principalOf(patron);
        return new TokenPair(accessToken(p, system.accessTokenTtl().toSeconds()),
                newRefreshToken(SubjectType.PATRON, patron.getId()), p);
    }

    /** Long-lived token for unattended hardware; carries the account's token version so it can be revoked. */
    public IssuedToken issueDeviceToken(AppUser device) {
        return accessToken(principalOf(device), system.deviceTokenTtl().toSeconds(), device.getTokenVersion());
    }

    @Transactional(noRollbackFor = BadCredentialsException.class)
    public TokenPair refresh(String rawRefreshToken) {
        RefreshToken token = refreshTokens.findByTokenHash(hash(rawRefreshToken))
                .orElseThrow(() -> new BadCredentialsException("Invalid refresh token"));
        if (token.isRevoked()) {
            // A rotated token was presented again: assume theft and kill every session of the subject.
            refreshTokens.revokeAll(token.getSubjectType(), token.getSubjectId());
            audit.record(token.getSubjectType() + ":" + token.getSubjectId(), "REFRESH_TOKEN_REUSE",
                    "All sessions revoked");
            throw new BadCredentialsException("Refresh token has been revoked");
        }
        if (token.getExpiresAt().isBefore(Instant.now())) {
            throw new BadCredentialsException("Refresh token expired");
        }
        token.revoke();
        return switch (token.getSubjectType()) {
            case USER -> {
                AppUser user = users.findById(token.getSubjectId())
                        .filter(AppUser::isEnabled)
                        .orElseThrow(() -> new BadCredentialsException("Account disabled"));
                yield issue(user);
            }
            case PATRON -> issue(patrons.findById(token.getSubjectId())
                    .filter(p -> p.getAnonymizedAt() == null)
                    .orElseThrow(() -> new BadCredentialsException("Patron not found")));
        };
    }

    @Transactional
    public void revoke(String rawRefreshToken) {
        refreshTokens.findByTokenHash(hash(rawRefreshToken)).ifPresent(RefreshToken::revoke);
    }

    @Transactional
    public void revokeAll(SubjectType type, Long id) {
        refreshTokens.revokeAll(type, id);
    }

    @Scheduled(cron = "0 15 3 * * *")
    @Transactional
    public void purgeExpired() {
        refreshTokens.deleteExpired(Instant.now().minus(1, ChronoUnit.DAYS));
    }

    private IssuedToken accessToken(PrincipalInfo p, long ttlSeconds) {
        return accessToken(p, ttlSeconds, null);
    }

    private IssuedToken accessToken(PrincipalInfo p, long ttlSeconds, Integer deviceTokenVersion) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(ttlSeconds);
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(props.issuer())
                .issuedAt(now)
                .expiresAt(exp)
                .subject(p.subject())
                .claim("username", p.username())
                .claim("name", p.displayName())
                .claim("roles", p.roles());
        if (p.patronId() != null) {
            claims.claim("patronId", p.patronId());
        }
        if (p.mustChangePassword()) {
            claims.claim(PasswordChangeFilter.CLAIM, true);
        }
        if (deviceTokenVersion != null) {
            claims.claim(DeviceTokenValidator.VERSION_CLAIM, deviceTokenVersion);
        }
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String value = encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
        return new IssuedToken(value, exp);
    }

    private IssuedToken newRefreshToken(SubjectType type, Long subjectId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant exp = Instant.now().plus(system.refreshTokenTtl());
        refreshTokens.save(new RefreshToken(hash(raw), type, subjectId, exp));
        return new IssuedToken(raw, exp);
    }

    private static String hash(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
