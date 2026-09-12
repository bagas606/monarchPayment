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
}
