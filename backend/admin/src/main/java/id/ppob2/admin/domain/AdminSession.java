package id.ppob2.admin.domain;

import id.ppob2.admin.session.SessionState;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** One row per issued session token. Only the token's SHA-256 is stored; see V27. */
@Entity
@Table(name = "admin_session")
public class AdminSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "admin_user_id", nullable = false)
    private Long adminUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SessionState state;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "absolute_expires_at", nullable = false)
    private Instant absoluteExpiresAt;

    @Column(name = "mfa_attempts", nullable = false)
    private int mfaAttempts;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_reason", length = 32)
    private String revokedReason;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    protected AdminSession() {
    }

    public AdminSession(String tokenHash, Long adminUserId, SessionState state, Instant now,
                        Instant absoluteExpiresAt, String ipAddress) {
        this.tokenHash = tokenHash;
        this.adminUserId = adminUserId;
        this.state = state;
        this.createdAt = now;
        this.lastSeenAt = now;
        this.absoluteExpiresAt = absoluteExpiresAt;
        this.ipAddress = ipAddress;
    }

    public Long getId() {
        return id;
    }

    public Long getAdminUserId() {
        return adminUserId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public SessionState getState() {
        return state;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public Instant getAbsoluteExpiresAt() {
        return absoluteExpiresAt;
    }

    public int getMfaAttempts() {
        return mfaAttempts;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public void touch(Instant now) {
        this.lastSeenAt = now;
    }

    public int recordMfaFailure() {
        return ++mfaAttempts;
    }

    public void revoke(Instant now, String reason) {
        if (revokedAt == null) {
            this.revokedAt = now;
            this.revokedReason = reason;
        }
    }
}
