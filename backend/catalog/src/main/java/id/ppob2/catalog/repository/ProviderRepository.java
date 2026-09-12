package id.ppob2.catalog.repository;

import id.ppob2.catalog.domain.Provider;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProviderRepository extends JpaRepository<Provider, Long> {
}
