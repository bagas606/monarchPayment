package id.ppob2.admin.security;

import id.ppob2.admin.domain.AdminUser;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Wraps {@link AdminUser} so authenticated controllers can read back the admin_user id (needed
 * for {@code ReconciliationService.resolve}'s {@code resolvedBy} and
 * {@code AuditService.recordAdminAction}'s actor) — a plain {@code UserDetails} only exposes the
 * username. Single fixed {@code ROLE_ADMIN} authority: Section 42's role/permission model isn't
 * built, so there's nothing finer-grained to grant yet.
 */
public class AdminPrincipal implements UserDetails {

    private final AdminUser adminUser;

    public AdminPrincipal(AdminUser adminUser) {
        this.adminUser = adminUser;
    }

    public Long getAdminUserId() {
        return adminUser.getId();
    }

    @Override
    public List<GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    @Override
    public String getPassword() {
        return adminUser.getPasswordHash();
    }

    @Override
    public String getUsername() {
        return adminUser.getUsername();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return adminUser.isActive();
    }
}
