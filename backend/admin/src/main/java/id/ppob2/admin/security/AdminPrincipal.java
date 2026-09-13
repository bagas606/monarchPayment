package id.ppob2.admin.security;

import id.ppob2.admin.domain.AdminUser;
import java.util.ArrayList;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Wraps {@link AdminUser} so authenticated controllers can read back the admin_user id (needed
 * for {@code ReconciliationService.resolve}'s {@code resolvedBy} and
 * {@code AuditService.recordAdminAction}'s actor) — a plain {@code UserDetails} only exposes the
 * username. Carries {@code ROLE_ADMIN} (unchanged — every chain matcher still just requires "is an
 * authenticated admin") plus one plain {@link SimpleGrantedAuthority} per Section 42.2 permission
 * code the admin_user's roles grant, deliberately with no {@code ROLE_} prefix since these are
 * checked via {@code hasAuthority(...)}, not {@code hasRole(...)}.
 */
public class AdminPrincipal implements UserDetails {

    private final AdminUser adminUser;
    private final List<String> permissionCodes;

    public AdminPrincipal(AdminUser adminUser, List<String> permissionCodes) {
        this.adminUser = adminUser;
        this.permissionCodes = permissionCodes;
    }

    public Long getAdminUserId() {
        return adminUser.getId();
    }

    @Override
    public List<GrantedAuthority> getAuthorities() {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        for (String code : permissionCodes) {
            authorities.add(new SimpleGrantedAuthority(code));
        }
        return authorities;
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
