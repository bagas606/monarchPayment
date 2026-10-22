package id.ppob2.admin.repository;

import id.ppob2.admin.domain.AdminSession;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdminSessionRepository extends JpaRepository<AdminSession, Long> {

    Optional<AdminSession> findByTokenHash(String tokenHash);

    List<AdminSession> findByAdminUserIdAndRevokedAtIsNull(Long adminUserId);

    /** Housekeeping: rows that can no longer be used for anything. Returns how many were removed. */
    @Modifying
    @Query("DELETE FROM AdminSession s WHERE s.absoluteExpiresAt < :cutoff OR s.revokedAt < :cutoff")
    int deleteDeadBefore(@Param("cutoff") Instant cutoff);
}
