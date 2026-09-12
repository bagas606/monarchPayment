package id.ppob2.admin.security;

import id.ppob2.admin.repository.AdminUserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** PRD Section 41 / 42: Admin Web authentication backed by the real {@code admin_user} table,
 * not Spring Boot's generated in-memory default user (which still protects every other
 * unmatched path — see {@code SecurityConfig}). */
@Service
public class AdminUserDetailsService implements UserDetailsService {

    private final AdminUserRepository repository;

    public AdminUserDetailsService(AdminUserRepository repository) {
        this.repository = repository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return repository.findByUsername(username)
                .map(AdminPrincipal::new)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown admin username: " + username));
    }
}
