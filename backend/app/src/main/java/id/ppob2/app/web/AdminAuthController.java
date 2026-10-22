package id.ppob2.app.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import id.ppob2.admin.domain.AdminUser;
import id.ppob2.admin.security.AdminPrincipal;
import id.ppob2.admin.session.AdminProperties;
import id.ppob2.admin.session.AdminSessionService;
import id.ppob2.admin.session.AdminSessionService.Issued;
import id.ppob2.admin.session.AdminSessionService.Resolved;
import id.ppob2.admin.session.SessionState;
import id.ppob2.admin.session.SessionTokens;
import id.ppob2.app.security.AdminSessionAuthenticationFilter;
import id.ppob2.app.security.SessionCookies;
import id.ppob2.sharedkernel.error.ApiException;
import id.ppob2.sharedkernel.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin Web login, MFA and session endpoints (Section 42.3).
 *
 * <p>The session token is put in an {@code HttpOnly; SameSite=Strict} cookie and is returned in the body
 * only when the caller asks ({@code return_token: true}). A browser UI never needs to read it, and a
 * token that script cannot read is a token that an XSS bug cannot steal. Scripts and the e2e harness ask
 * for it and send it as {@code Authorization: Bearer}.
 */
@RestController
public class AdminAuthController {

    private final AdminSessionService sessions;
    private final AdminProperties props;
    public AdminAuthController(AdminSessionService sessions, AdminProperties props) {
        this.sessions = sessions;
        this.props = props;
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password,
                               @JsonProperty("return_token") Boolean returnToken) {
    }

    public record CodeRequest(@NotBlank String code, @JsonProperty("return_token") Boolean returnToken) {
    }

    public record RevokeRequest(@NotBlank String username) {
    }

    public record UserView(String username, boolean mfaEnabled, List<String> permissions) {
    }

    public record SessionResponse(String status, String token, String csrfToken, Instant expiresAt,
                                  long idleTimeoutSeconds, UserView user) {
    }

    // ---- login -----------------------------------------------------------------------------------

    @PostMapping("/admin/auth/login")
    public ResponseEntity<SessionResponse> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request,
                                                 HttpServletResponse response) {
        Issued issued = sessions.login(body.username(), body.password(), request.getRemoteAddr());
        return respond(issued, Boolean.TRUE.equals(body.returnToken()), response);
    }

    @PostMapping("/admin/auth/mfa/verify")
    public ResponseEntity<SessionResponse> verify(@Valid @RequestBody CodeRequest body, HttpServletRequest request,
                                                  HttpServletResponse response) {
        Issued issued = sessions.verifyMfa(current(request), body.code(), request.getRemoteAddr());
        return respond(issued, Boolean.TRUE.equals(body.returnToken()), response);
    }

    public record EnrollmentResponse(String secret, String otpauthUri) {
    }

    @PostMapping("/admin/auth/mfa/enroll")
    public ResponseEntity<EnrollmentResponse> startEnrollment(HttpServletRequest request) {
        AdminSessionService.Enrollment e = sessions.startEnrollment(current(request), request.getRemoteAddr());
        return ResponseEntity.ok(new EnrollmentResponse(e.secret(), e.otpauthUri()));
    }

    /** Completes enrolment. If it was a mandatory one the response carries the new, ACTIVE session. */
    @PostMapping("/admin/auth/mfa/confirm")
    public ResponseEntity<SessionResponse> confirmEnrollment(@Valid @RequestBody CodeRequest body,
                                                             HttpServletRequest request, HttpServletResponse response) {
        Resolved resolved = current(request);
        return sessions.confirmEnrollment(resolved, body.code(), request.getRemoteAddr())
                .map(issued -> respond(issued, Boolean.TRUE.equals(body.returnToken()), response))
                // `resolved` predates the confirmation, so its flag still says false; the truth is true.
                .orElseGet(() -> ResponseEntity.ok(new SessionResponse("MFA_ENABLED", null, null, null, 0,
                        new UserView(resolved.user().getUsername(), true, List.of()))));
    }

    // ---- session ---------------------------------------------------------------------------------

    @GetMapping("/admin/auth/me")
    public ResponseEntity<SessionResponse> me(HttpServletRequest request) {
        Resolved resolved = current(request);
        if (resolved.session().getState() != SessionState.ACTIVE) {
            throw new ApiException(ErrorCode.SESSION_STATE_INVALID, "Finish signing in first.");
        }
        return ResponseEntity.ok(new SessionResponse("AUTHENTICATED", null, null,
                resolved.session().getAbsoluteExpiresAt(), props.session().idleTimeout().toSeconds(),
                view(resolved.user(), true)));
    }

    @PostMapping("/admin/auth/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        sessions.logout(current(request), request.getRemoteAddr());
        response.addHeader(HttpHeaders.SET_COOKIE, SessionCookies.clear(props).toString());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/admin/auth/sessions/revoke")
    @PreAuthorize("hasAuthority('session:revoke')")
    public ResponseEntity<java.util.Map<String, Integer>> revoke(@Valid @RequestBody RevokeRequest body,
                                                                 HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Long actor = ((AdminPrincipal) auth.getPrincipal()).getAdminUserId();
        int ended = sessions.revokeAllFor(body.username(), actor, request.getRemoteAddr());
        return ResponseEntity.ok(java.util.Map.of("revoked", ended));
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private Resolved current(HttpServletRequest request) {
        Object attribute = request.getAttribute(AdminSessionAuthenticationFilter.REQUEST_ATTRIBUTE);
        if (attribute instanceof Resolved resolved) {
            return resolved;
        }
        // Reached with HTTP Basic (dev/e2e) or no session: there is nothing here to verify or end.
        throw new ApiException(ErrorCode.AUTHENTICATION_FAILED, "A session is required. Sign in first.");
    }

    private ResponseEntity<SessionResponse> respond(Issued issued, boolean returnToken, HttpServletResponse response) {
        long maxAge = Math.max(0, Duration.between(Instant.now(), issued.absoluteExpiresAt()).getSeconds());
        response.addHeader(HttpHeaders.SET_COOKIE, SessionCookies.issue(props, issued.token(), maxAge).toString());
        String status = switch (issued.state()) {
            case ACTIVE -> "AUTHENTICATED";
            case PENDING_MFA -> "MFA_REQUIRED";
            case PENDING_ENROLL -> "MFA_ENROLLMENT_REQUIRED";
        };
        UserView user = null;
        if (issued.state() == SessionState.ACTIVE) {
            AdminPrincipal principal = (AdminPrincipal) sessions.principalForToken(issued.token());
            user = new UserView(principal.getUsername(), principal.isMfaEnabled(), permissions(principal));
        }
        return ResponseEntity.ok(new SessionResponse(status, returnToken ? issued.token() : null, issued.csrfToken(),
                issued.absoluteExpiresAt(), props.session().idleTimeout().toSeconds(), user));
    }

    private UserView view(AdminUser user, boolean withPermissions) {
        return new UserView(user.getUsername(), user.isMfaEnabled(),
                withPermissions ? permissions(sessions.principalFor(user)) : List.of());
    }

    private static List<String> permissions(AdminPrincipal principal) {
        return principal.getAuthorities().stream().map(a -> a.getAuthority())
                .filter(a -> !a.startsWith("ROLE_")).sorted().toList();
    }
}
