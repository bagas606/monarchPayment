package id.ppob2.configuration.repository;

import id.ppob2.configuration.domain.ConfigEntry;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConfigEntryRepository extends JpaRepository<ConfigEntry, Long> {
    Optional<ConfigEntry> findByConfigKey(String configKey);
}
