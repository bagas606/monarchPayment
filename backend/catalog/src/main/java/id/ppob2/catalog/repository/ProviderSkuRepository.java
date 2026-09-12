package id.ppob2.catalog.repository;

import id.ppob2.catalog.domain.ProviderSku;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProviderSkuRepository extends JpaRepository<ProviderSku, Long> {
    List<ProviderSku> findByIdIn(List<Long> ids);
}
