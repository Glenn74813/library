package ph.edu.zscmst.library.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Accounts signed in with a temporary password (set by an administrator) may only use the
 * sign-in endpoints, so the first thing they can do is choose their own password.
 */
public class PasswordChangeFilter extends OncePerRequestFilter {

    public static final String CLAIM = "pwdChange";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt && Boolean.TRUE.equals(jwt.getToken().getClaim(CLAIM))
                && !request.getRequestURI().startsWith("/api/auth/") && !request.getRequestURI().startsWith("/api/opac/")) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/problem+json");
            response.getWriter().write("{\"status\":403,\"title\":\"Forbidden\",\"detail\":\"Please change your temporary password first\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
