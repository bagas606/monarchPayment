package id.ppob2.app.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.ppob2.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * PRD Section 43 / TC-ADM-002: an Admin Web login with invalid credentials must be "rejected,
 * failure audit-logged". Rejection always worked; the audit half did not exist — confirmed twice
 * (2026-09-14 and again 2026-09-27) that {@code audit_log} had zero rows after an invalid login,
 * because {@code AuditService} was only ever called on success paths and no authentication-event
 * listener existed at all.
 *
 * <p>Spring Security's {@code ProviderManager} publishes {@link AbstractAuthenticationFailureEvent}
 * subclasses through the {@code DefaultAuthenticationEventPublisher} Spring Boot auto-configures,
 * so this needs no wiring beyond being a bean. Listening to the abstract parent rather than only
 * {@code AuthenticationFailureBadCredentialsEvent} is deliberate: a disabled or locked account
 * ({@code admin_user.status != 'ACTIVE'}) raises a different subclass, and that is just as
 * security-relevant as a wrong password — the previous gap was partly a matter of enumerating
 * cases, so this enumerates none.
 *
 * <p>The attempted username is recorded; the submitted password never is, not even on failure
 * (a mistyped password is frequently another account's correct one).
 */
@Component
public class AdminSecurityAuditListener {

    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public AdminSecurityAuditListener(AuditService auditService, ObjectMapper objectMapper) {
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @EventListener
    public void onAuthenticationFailure(AbstractAuthenticationFailureEvent event) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("username", String.valueOf(event.getAuthentication().getName()));
        detail.put("outcome", "REJECTED");
        detail.put("reason", event.getException().getClass().getSimpleName());
        auditService.recordRejectedAdminAttempt("ADMIN_LOGIN_FAILED", "ADMIN_LOGIN", json(detail), clientIp());
    }

    /**
     * Read from the current request rather than the event: {@code AbstractAuthenticationFailureEvent}
     * carries no remote address, and {@code audit_log.ip_address} is the column a security review
     * actually pivots on. Null-safe because authentication failures can in principle be raised
     * outside a servlet request (none do today), and an audit row with no IP still beats none.
     */
    private String clientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            return request != null ? request.getRemoteAddr() : null;
        }
        return null;
    }

    private String json(Map<String, String> detail) {
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (Exception e) {
            return "{}";
        }
    }
}
