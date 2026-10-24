package id.ppob2.configuration;

import com.fasterxml.jackson.databind.node.DecimalNode;
import id.ppob2.configuration.domain.ConfigEntry;
import id.ppob2.configuration.repository.ConfigEntryRepository;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Typed access to {@link ConfigKey}s. Reads fall back to the key's default when no override row exists; writes
 * are validated against the key's type and range. Reads go to the database every time: these values change a
 * handful of times a year and are read by batch jobs, so a cache would only add a way to serve a stale fee.
 */
@Service
public class ConfigurationService {

    private final ConfigEntryRepository repository;

    public ConfigurationService(ConfigEntryRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public BigDecimal get(ConfigKey key) {
        return repository.findByConfigKey(key.key())
                .map(e -> e.getConfigValue().decimalValue())
                .orElse(key.defaultValue());
    }

    @Transactional(readOnly = true)
    public int getInt(ConfigKey key) {
        return get(key).intValueExact();
    }

    /** Every known key with its effective value, for the admin view. */
    @Transactional(readOnly = true)
    public Map<ConfigKey, Effective> all() {
        Map<String, ConfigEntry> overrides = new LinkedHashMap<>();
        repository.findAll().forEach(e -> overrides.put(e.getConfigKey(), e));
        Map<ConfigKey, Effective> out = new LinkedHashMap<>();
        for (ConfigKey k : ConfigKey.values()) {
            ConfigEntry e = overrides.get(k.key());
            out.put(k, e == null
                    ? new Effective(k.defaultValue(), true, null)
                    : new Effective(e.getConfigValue().decimalValue(), false, e.getUpdatedBy()));
        }
        return out;
    }

    /** @return {previous effective value, new value} */
    @Transactional
    public BigDecimal[] set(ConfigKey key, BigDecimal raw, Long adminUserId) {
        BigDecimal value = key.validate(raw);
        BigDecimal previous = get(key);
        ConfigEntry entry = repository.findByConfigKey(key.key()).orElse(null);
        if (entry == null) {
            repository.save(new ConfigEntry(key.key(), DecimalNode.valueOf(value), key.description(), adminUserId));
        } else {
            entry.change(DecimalNode.valueOf(value), adminUserId);
            repository.save(entry);
        }
        return new BigDecimal[] {previous, value};
    }

    public record Effective(BigDecimal value, boolean isDefault, Long updatedBy) {
    }
}
