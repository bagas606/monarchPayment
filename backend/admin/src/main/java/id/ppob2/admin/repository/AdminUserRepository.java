package id.ppob2.admin.repository;

import id.ppob2.admin.domain.AdminUser;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdminUserRepository extends JpaRepository<AdminUser, Long> {

    Optional<AdminUser> findByUsername(String username);

    /**
     * Section 42.2's permission codes granted to this admin, via {@code admin_user_role} ->
     * {@code role_permission} -> {@code permission}. Native SQL rather than {@code Role}/
     * {@code Permission} JPA entities — same precedent as {@code ProviderTransactionRepository
     * .insertIfAbsent}: nothing in this codebase needs to load a role or permission as an object,
     * only the flat list of codes one admin_user currently holds.
     */
    @Query(value = "SELECT p.code FROM admin_user_role aur "
            + "JOIN role_permission rp ON rp.role_id = aur.role_id "
            + "JOIN permission p ON p.id = rp.permission_id "
            + "WHERE aur.admin_user_id = :adminUserId", nativeQuery = true)
    List<String> findPermissionCodes(@Param("adminUserId") Long adminUserId);
}
