package id.ppob2.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import id.ppob2.admin.domain.AdminUser;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

class AdminPrincipalTest {

    @Test
    void authoritiesIncludeRoleAdminPlusEveryGrantedPermissionCode() {
        AdminUser adminUser = new AdminUser("ops1", "hash");
        AdminPrincipal principal = new AdminPrincipal(adminUser, List.of("retry:execute", "reconciliation:investigate"));

        List<String> authorityNames = principal.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();

        assertThat(authorityNames).containsExactlyInAnyOrder("ROLE_ADMIN", "retry:execute", "reconciliation:investigate");
    }

    @Test
    void authoritiesAreJustRoleAdminWhenNoPermissionsGranted() {
        AdminUser adminUser = new AdminUser("viewer1", "hash");
        AdminPrincipal principal = new AdminPrincipal(adminUser, List.of());

        List<String> authorityNames = principal.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();

        assertThat(authorityNames).containsExactly("ROLE_ADMIN");
    }
}
