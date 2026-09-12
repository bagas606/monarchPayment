package id.ppob2.partner.repository;

import id.ppob2.partner.domain.ApiClient;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiClientRepository extends JpaRepository<ApiClient, Long> {
    Optional<ApiClient> findByClientId(String clientId);
}
