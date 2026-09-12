package id.ppob2.admin.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Maps to the `admin_user` table, PRD Section 22.26. Only this table is built in this slice —
 * {@code role}/{@code permission}/{@code admin_user_role}/{@code role_permission} (Section 42's
 * RBAC) are not, so there is no per-permission gating here: any {@code ACTIVE} admin_user can
 * drive every admin action this codebase exposes. Flagged in the README.
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
}
