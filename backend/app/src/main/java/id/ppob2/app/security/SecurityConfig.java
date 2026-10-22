package id.ppob2.app.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.ppob2.admin.security.AdminUserDetailsService;
import id.ppob2.admin.session.AdminProperties;
import id.ppob2.admin.session.AdminSessionService;
import id.ppob2.app.web.CorrelationIdFilter;
import id.ppob2.sharedkernel.error.ErrorCode;
import id.ppob2.sharedkernel.error.ErrorResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
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
@EnableConfigurationProperties(AdminProperties.class)
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
                                                 PasswordEncoder passwordEncoder, AdminProperties adminProperties,
                                                 AdminSessionService sessionService, ObjectMapper objectMapper)
            throws Exception {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
        authProvider.setUserDetailsService(adminUserDetailsService);
        authProvider.setPasswordEncoder(passwordEncoder);

        http
                .securityMatcher("/admin/**", "/internal/settlement/**")
                // Spring's CSRF filter is off because it is session-based; the cookie path has its own
                // double-submit check in AdminSessionAuthenticationFilter, and Bearer/Basic callers are
                // not ambient credentials a cross-site page can ride.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authenticationProvider(authProvider)
                .addFilterBefore(new AdminSessionAuthenticationFilter(sessionService, adminProperties, objectMapper),
                        BasicAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/admin/auth/login").permitAll()
                        // A pending (password-only) session may reach exactly these, and nothing else.
                        .requestMatchers("/admin/auth/mfa/**", "/admin/auth/logout")
                                .hasAnyRole("ADMIN", "PENDING_MFA")
                        .anyRequest().hasRole("ADMIN"));
        if (adminProperties.basicAuthEnabled()) {
            http.httpBasic(basic -> {});
        } else {
            // No Basic means no WWW-Authenticate challenge (a browser would pop its own login box) and a
            // JSON 401 instead of Spring's default 403 for "no authentication mechanism configured".
            http.exceptionHandling(eh -> eh.authenticationEntryPoint((request, response, ex) -> {
                response.setStatus(401);
                response.setContentType("application/json");
                Object correlation = request.getAttribute(CorrelationIdFilter.ATTRIBUTE);
                objectMapper.writeValue(response.getWriter(), ErrorResponse.of(ErrorCode.AUTHENTICATION_FAILED,
                        "Authentication required.", correlation == null ? null : correlation.toString()));
            }));
        }
        return http.build();
    }

    /**
     * The backoffice UI's static files. No authentication here - the page itself holds nothing; every
     * byte of data comes from {@code /admin/**}, which is authenticated - but the headers matter, because
     * this is the page that can drive refunds:
     * <ul>
     *   <li>CSP with no inline script/style and {@code connect-src 'self'}: an injected element cannot
     *       run script or send data to another origin, which is the containment for an XSS bug the UI
     *       already tries not to have (it builds the DOM with {@code textContent}, never {@code innerHTML}).</li>
     *   <li>{@code frame-ancestors 'none'} / X-Frame-Options DENY: no clickjacking a refund button.</li>
     *   <li>{@code no-referrer}: order numbers in the URL fragment never leave the origin.</li>
     * </ul>
     * Everything is served from the app itself - no CDN script or font on a payment back-office.
     */
    @Bean
    public SecurityFilterChain backofficeUiFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/backoffice/**", "/backoffice")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; script-src 'self'; "
                                + "style-src 'self'; img-src 'self' data:; connect-src 'self'; base-uri 'none'; "
                                + "form-action 'self'; frame-ancestors 'none'"))
                        .frameOptions(frame -> frame.deny())
                        .referrerPolicy(ref -> ref.policy(
                                org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
                                        .ReferrerPolicy.NO_REFERRER)))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
