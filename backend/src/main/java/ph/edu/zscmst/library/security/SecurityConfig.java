package ph.edu.zscmst.library.security;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import ph.edu.zscmst.library.user.AppUserRepository;

/**
 * Stateless JWT security. Tokens are signed with HMAC-SHA256 and carry a {@code roles} claim that
 * maps to Spring authorities ({@code ROLE_ADMIN}, {@code ROLE_PATRON}, ...).
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final String[] STAFF = {"ADMIN", "LIBRARIAN", "STAFF"};

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AppUserRepository users) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> { })
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/auth/login", "/api/auth/patron-login", "/api/auth/refresh",
                                "/api/auth/logout", "/api/auth/pin-reset/**").permitAll()
                        .requestMatchers("/api/opac/**").permitAll()
                        // WebSocket handshake for live announcements (public, read-only topic).
                        .requestMatchers("/api/ws/**", "/api/ws").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/error").permitAll()
                        .requestMatchers("/api/auth/**").authenticated()
                        .requestMatchers("/api/kiosk/**").hasAnyRole("KIOSK", "ADMIN", "LIBRARIAN")
                        .requestMatchers("/api/gate/device/**").hasAnyRole("GATE", "ADMIN")
                        .requestMatchers("/api/me/**").hasRole("PATRON")
                        .requestMatchers("/api/users/**", "/api/audit/**").hasRole("ADMIN")
                        // Payments are received by the cashier (admin as a fallback); the cashier sees only fines,
                        // receipts and the end-of-day report, and logs the receipts it prints.
                        .requestMatchers(HttpMethod.POST, "/api/fines/pay").hasAnyRole("CASHIER", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/fines", "/api/fines/receipts/**", "/api/fines/cash-report",
                                "/api/fines/patron/**", "/api/reports/export/fines", "/api/reports/export/fines/preview").hasAnyRole("CASHIER", "ADMIN", "LIBRARIAN", "STAFF")
                        .requestMatchers(HttpMethod.POST, "/api/print-log").hasAnyRole("CASHIER", "ADMIN", "LIBRARIAN", "STAFF")
                        .requestMatchers(HttpMethod.GET, "/api/print-log/document").hasAnyRole("CASHIER", "ADMIN", "LIBRARIAN", "STAFF")
                        .requestMatchers("/api/**").hasAnyRole(STAFF)
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
        http.addFilterBefore(new DeviceKeyFilter(users),
                org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter.class);
        http.addFilterAfter(new PasswordChangeFilter(),
                org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    @Bean
    SecretKey jwtSigningKey(JwtProperties props) {
        byte[] bytes = props.secret().getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("app.jwt.secret (JWT_SECRET) must be at least 32 bytes");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSigningKey, JwtProperties props, DeviceTokenValidator deviceTokens) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(props.issuer()), deviceTokens));
        return decoder;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${app.cors.allowed-origins}") String origins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.stream(origins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Device-Key"));
        config.setExposedHeaders(List.of("Content-Disposition"));
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
