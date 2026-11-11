package ph.edu.zscmst.library.security;

import org.springframework.context.annotation.Lazy;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import ph.edu.zscmst.library.user.AppUserRepository;

/**
 * Device tokens (security gate middleware) live for months, so each carries its account's token
 * version. Revoking bumps the version; disabling the account also stops its device tokens.
 */
@Component
public class DeviceTokenValidator implements OAuth2TokenValidator<Jwt> {

    public static final String VERSION_CLAIM = "dtv";

    private static final OAuth2TokenValidatorResult REVOKED = OAuth2TokenValidatorResult.failure(
            new OAuth2Error("invalid_token", "This device token was revoked", null));

    private final AppUserRepository users;

    public DeviceTokenValidator(@Lazy AppUserRepository users) {
        this.users = users;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        Object version = jwt.getClaims().get(VERSION_CLAIM);
        if (version == null) return OAuth2TokenValidatorResult.success();
        String subject = jwt.getSubject();
        if (subject == null || !subject.startsWith("user:")) return REVOKED;
        long id;
        try {
            id = Long.parseLong(subject.substring(5));
        } catch (NumberFormatException e) {
            return REVOKED;
        }
        int v = ((Number) version).intValue();
        return users.findById(id).filter(u -> u.isEnabled() && u.getTokenVersion() == v).isPresent()
                ? OAuth2TokenValidatorResult.success() : REVOKED;
    }
}
