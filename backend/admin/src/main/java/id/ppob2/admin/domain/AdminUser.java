package id.ppob2.admin.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Maps to the `admin_user` table, PRD Section 22.26. Section 42's role/permission tables
 * ({@code role}, {@code permission}, {@code admin_user_role}, {@code role_permission}) are a
 * separate slice, not columns on this entity — see {@code AdminUserRepository
 * .findPermissionCodes} and {@code AdminPrincipal} for how an admin_user's granted permission
 * codes are resolved. Section 42.3 (MFA, JWT/session idle+absolute timeout) remains out of scope;
 * flagged in the README.
 */
@Entity
@Table(name = "admin_user")
public class AdminUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "mfa_enabled", nullable = false)
    private boolean mfaEnabled;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "mfa_secret")
    private String mfaSecret;

    @Column(name = "mfa_last_step")
    private Long mfaLastStep;

    @Column(name = "failed_logins", nullable = false)
    private int failedLogins;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    protected AdminUser() {
    }

    public AdminUser(String username, String passwordHash) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.mfaEnabled = false;
        this.status = "ACTIVE";
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }

    public boolean isMfaEnabled() {
        return mfaEnabled;
    }

    /** AES-GCM ciphertext, never the TOTP secret itself. */
    public String getMfaSecret() {
        return mfaSecret;
    }

    public Long getMfaLastStep() {
        return mfaLastStep;
    }

    public boolean isLocked(Instant now) {
        return lockedUntil != null && now.isBefore(lockedUntil);
    }

    /** Overwrites any earlier unconfirmed secret; the account is not MFA-protected until {@link #confirmMfa}. */
    public void beginMfaEnrollment(String encryptedSecret) {
        this.mfaSecret = encryptedSecret;
        this.mfaEnabled = false;
        this.mfaLastStep = null;
    }

    public void confirmMfa(long step) {
        this.mfaEnabled = true;
        this.mfaLastStep = step;
    }

    /** Replay protection: a step must be strictly newer than the last accepted one. */
    public boolean canAcceptMfaStep(long step) {
        return mfaLastStep == null || step > mfaLastStep;
    }

    public void acceptMfaStep(long step) {
        this.mfaLastStep = step;
    }

    public void recordLoginFailure(Instant now, int maxFailures, java.time.Duration lockout) {
        this.failedLogins++;
        if (this.failedLogins >= maxFailures) {
            this.lockedUntil = now.plus(lockout);
            this.failedLogins = 0;
        }
    }

    /** A correct password clears the failure streak even when MFA is still owed; the streak guards guessing. */
    public void resetFailuresOnly() {
        this.failedLogins = 0;
    }

    public void recordLoginSuccess(Instant now) {
        this.failedLogins = 0;
        this.lockedUntil = null;
        this.lastLoginAt = now;
    }
}
