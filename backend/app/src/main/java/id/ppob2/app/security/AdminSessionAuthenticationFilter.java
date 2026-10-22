package id.ppob2.app.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.ppob2.admin.security.AdminPrincipal;
import id.ppob2.admin.session.AdminProperties;
import id.ppob2.admin.session.AdminSessionService;
import id.ppob2.admin.session.AdminSessionService.Resolved;
import id.ppob2.admin.session.SessionState;
import id.ppob2.admin.session.SessionTokens;
import id.ppob2.app.web.CorrelationIdFilter;
import id.ppob2.sharedkernel.error.ErrorCode;
import id.ppob2.sharedkernel.error.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Turns an admin session token into a Spring Security {@code Authentication} (Section 42.3).
 *
 * <p><b>Two ways to present the token, with different CSRF treatment.</b> {@code Authorization: Bearer}
 * is for scripts and tests: a browser never attaches it on its own, so a cross-site page cannot cause it
 * to be sent and it needs no CSRF defence. The cookie is for the browser UI, and a cookie IS attached
 * automatically - so for any state-changing method it must be accompanied by {@code X-CSRF-Token} equal
 * to the value derived from the token ({@link SessionTokens#csrf}). {@code SameSite=Strict} is the first
 * line of defence; this is the second, because the first is a browser behaviour and not ours to rely on.
 *
 * <p><b>A pending session authenticates as nothing but {@code ROLE_PENDING_MFA}.</b> It carries the
 * account's identity (so the MFA endpoints know whose code to check) and none of its permissions, so a
 * password-only session cannot reach any admin data however the URL rules are written.
 */
public class AdminSessionAuthenticationFilter extends OncePerRequestFilter {

    public static final String REQUEST_ATTRIBUTE = "ppob2.admin.session";
    public static final String TOKEN_ATTRIBUTE = "ppob2.admin.token";
    public static final String CSRF_HEADER = "X-CSRF-Token";
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final AdminSessionService sessions;
    private final AdminProperties props;
    private final ObjectMapper objectMapper;

    public AdminSessionAuthenticationFilter(AdminSessionService sessions, AdminProperties props,
                                             ObjectMapper objectMapper) {
        this.sessions = sessions;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    /**
     * Sign-in must ignore whatever credential the browser happens to be carrying. {@code permitAll} only
     * affects authorization - this filter still runs on the login URL, resolves a live cookie and then
     * demands its CSRF header. A user who reloads at the MFA step has a live pending cookie and a fresh
     * page that holds no CSRF value, so without this their next sign-in is refused with a 403 until the
     * pending session times out. Login creates a session; it does not depend on one, and it is the one
     * state-changing endpoint that is deliberately CSRF-exempt (SameSite=Strict, and a JSON body a
     * cross-site form cannot send).
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "/admin/auth/login".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String bearer = bearerToken(request);
        String cookie = bearer == null ? cookieToken(request) : null;
        String token = bearer != null ? bearer : cookie;

        if (token == null) {
            chain.doFilter(request, response);
            return;
        }

        Optional<Resolved> resolved = sessions.resolve(token);
        if (resolved.isEmpty()) {
            if (cookie != null) {
                response.addHeader(HttpHeaders.SET_COOKIE, SessionCookies.clear(props).toString());
            }
            chain.doFilter(request, response);
            return;
        }

        if (cookie != null && !SAFE_METHODS.contains(request.getMethod())
                && !SessionTokens.constantTimeEquals(request.getHeader(CSRF_HEADER), SessionTokens.csrf(token))) {
            reject(request, response, "Missing or invalid " + CSRF_HEADER + " header.");
            return;
        }

        Resolved session = resolved.get();
        AdminPrincipal principal = sessions.principalFor(session.user());
        UsernamePasswordAuthenticationToken authentication = session.session().getState() == SessionState.ACTIVE
                ? UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities())
                : UsernamePasswordAuthenticationToken.authenticated(principal, null,
                        List.of(new SimpleGrantedAuthority("ROLE_PENDING_MFA")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        request.setAttribute(REQUEST_ATTRIBUTE, session);
        request.setAttribute(TOKEN_ATTRIBUTE, token);
        chain.doFilter(request, response);
    }

    private static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return header.substring(7).trim();
        }
        return null;
    }

    private String cookieToken(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie c : request.getCookies()) {
            if (c.getName().equals(props.session().cookieName())) {
                return c.getValue();
            }
        }
        return null;
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, String message) throws IOException {
        Object correlation = request.getAttribute(CorrelationIdFilter.ATTRIBUTE);
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(),
                ErrorResponse.of(ErrorCode.PERMISSION_DENIED, message, correlation == null ? null : correlation.toString()));
    }
}
