package ph.edu.zscmst.library.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import ph.edu.zscmst.library.user.AppUser;
import ph.edu.zscmst.library.user.AppUserRepository;

/**
 * Kiosks and gates send their device key in the X-Device-Key header instead of signing in. Only
 * the key's SHA-256 hash is stored. A wrong, replaced or revoked key, or a disabled device, gets
 * 401 so the kiosk returns to its setup screen.
 */
public class DeviceKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Device-Key";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration SEEN_EVERY = Duration.ofMinutes(1);

    private final AppUserRepository users;

    public DeviceKeyFilter(AppUserRepository users) {
        this.users = users;
    }

    /** A new random key, e.g. "kd_3q9x…" (43 characters of URL-safe base64 after the prefix). */
    public static String newKey() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "kd_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.trim().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(HEADER);
        if (key == null || key.isBlank() || request.getHeader("Authorization") != null) {
            chain.doFilter(request, response);
            return;
        }
        Optional<AppUser> device = users.findByDeviceKeyHash(hash(key)).filter(u -> u.isEnabled() && u.isDevice());
        if (device.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/problem+json");
            response.getWriter().write("{\"status\":401,\"title\":\"Unauthorized\",\"detail\":\"This device key is not accepted. "
                    + "It may have been replaced or revoked, or the device is disabled.\"}");
            return;
        }
        AppUser d = device.get();
        Instant now = Instant.now();
        if (d.getDeviceLastSeenAt() == null || d.getDeviceLastSeenAt().isBefore(now.minus(SEEN_EVERY))) {
            d.setDeviceLastSeenAt(now);
            users.save(d);
        }
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                d.getUsername(), null, List.of(new SimpleGrantedAuthority("ROLE_" + d.getRole().name()))));
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
