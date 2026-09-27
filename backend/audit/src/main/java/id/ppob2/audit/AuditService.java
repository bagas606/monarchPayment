package id.ppob2.audit;

import id.ppob2.audit.domain.AuditLog;
import id.ppob2.audit.repository.AuditLogRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;

/** Cross-cutting immutable audit capture, PRD Section 20.1 (`audit` module) and 45.1. */
@Service
public class AuditService {

    private final AuditLogRepository repository;

    public AuditService(AuditLogRepository repository) {
        this.repository = repository;
    }

    public void recordSystemAction(String action, String targetType, Long targetId, String afterState) {
        repository.save(new AuditLog("SYSTEM", null, action, targetType, targetId, null, afterState, null, Instant.now()));
    }

    public void recordAdminAction(Long actorId, String action, String targetType, Long targetId,
                                   String beforeState, String afterState, String ipAddress) {
        repository.save(new AuditLog("ADMIN_USER", actorId, action, targetType, targetId, beforeState, afterState, ipAddress, Instant.now()));
    }

    /**
     * A security-relevant admin action that was REJECTED, where the acting identity cannot be
     * resolved to an {@code admin_user.id} — by definition the case for a failed login (the
     * credentials were wrong, so there may be no such user at all).
     *
     * <p>Exists because PRD Section 43's "every action recorded" was implemented success-only:
     * {@link #recordAdminAction} is called on the success path and nowhere else, so an invalid
     * login (TC-ADM-002) and a permission-denied attempt (TC-ADM-005) both left {@code audit_log}
     * completely empty — the two cases a security audit would most want. {@code actor_id} is null
     * rather than invented, and the attempted identity is carried in {@code after_state} where it
     * can be read without pretending it resolved to a real account.
     */
    public void recordRejectedAdminAttempt(String action, String targetType, String afterState, String ipAddress) {
        repository.save(new AuditLog("ADMIN_USER", null, action, targetType, null, null, afterState, ipAddress, Instant.now()));
    }
}
