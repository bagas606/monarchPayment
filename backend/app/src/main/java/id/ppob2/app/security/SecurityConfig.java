package id.ppob2.app.security;

import id.ppob2.admin.security.AdminUserDetailsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Open API authentication is fully handled by {@link HmacAuthenticationFilter} (Section 23.2),
 * not Spring Security's authentication mechanisms — there is no username/password/session
 * principal for machine-to-machine partner traffic. This chain only disables the defaults
 * (CSRF, form login, sessions) that don't apply to a stateless signed API and permits requests
 * that already passed the HMAC filter through to the controllers.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain openApiFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/v1/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    /**
     * {@code /internal/webhooks/**} (Ayolinx callbacks, Section 25.2) has its own signature
     * scheme verified inside {@code PaymentCallbackService} — like the Open API chain above, it
     * needs Spring Security out of the way, not one of its authentication mechanisms. Without
     * this chain, the path falls through to Spring Boot's default security auto-configuration
     * (the "Using generated security password" one seen in boot logs) and every callback would
     * be rejected with a login challenge before ever reaching the controller.
     */
    @Bean
    public SecurityFilterChain internalWebhookFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/internal/webhooks/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    /**
     * {@code /admin/**} (Section 41/42: Admin Web) and {@code /internal/settlement/**} (Section
     * 37.1 report ingestion) — real HTTP Basic auth against the {@code admin_user} table via
     * {@link AdminUserDetailsService}, unlike every chain above which deliberately has no Spring
     * Security authentication mechanism at all. Settlement ingestion was previously its own
     * {@code permitAll} chain, flagged since that slice as needing "real authn/authz (or an
     * Admin-Web-gated trigger)" — now that Admin Web auth exists, gating it behind the same
     * mechanism is that fix, on the reasoning that triggering settlement ingestion is itself an
     * ops/Admin action (Section 41.8), not a partner- or PG-facing one.
     *
     * <p>This chain still only checks "is an authenticated {@code ROLE_ADMIN}" at the URL level —
     * every {@link AdminPrincipal} always carries {@code ROLE_ADMIN}, so this check can never
     * itself deny an authenticated admin; it only rejects unauthenticated/wrong-credential
     * requests (401, via Basic Auth). Section 42's fine-grained permission checks (retry:execute,
     * reconciliation:investigate, reconciliation:resolve, settlement:ingest) are enforced one
     * level deeper, via {@code @PreAuthorize} on the individual controller methods, using the
     * permission-code authorities {@code AdminPrincipal} carries. A {@code @PreAuthorize} denial
     * throws {@code AuthorizationDeniedException} from inside the controller's AOP proxy —
     * {@code DispatcherServlet}'s own exception resolution resolves that via {@code
     * GlobalExceptionHandler}'s {@code @ExceptionHandler(AccessDeniedException.class)} before it
     * could ever reach a filter-level {@code AccessDeniedHandler} here, which is why the 403 is
     * produced there and not in this class. Section 42.3's session management (JWT/idle+absolute
     * timeout, revocation) and MFA are still not built; Basic Auth is stateless per-request, which
     * sidesteps session-timeout semantics entirely rather than approximating them.
     */
    @Bean
    public SecurityFilterChain adminFilterChain(HttpSecurity http, AdminUserDetailsService adminUserDetailsService,
                                                 PasswordEncoder passwordEncoder) throws Exception {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
        authProvider.setUserDetailsService(adminUserDetailsService);
        authProvider.setPasswordEncoder(passwordEncoder);

        http
                .securityMatcher("/admin/**", "/internal/settlement/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authenticationProvider(authProvider)
                .httpBasic(basic -> {})
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("ADMIN"));
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
