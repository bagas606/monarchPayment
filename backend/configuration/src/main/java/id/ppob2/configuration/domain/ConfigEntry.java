package id.ppob2.configuration.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Maps to the `configuration` table, PRD Section 22.25. */
@Entity
@Table(name = "configuration")
public class ConfigEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "config_key", nullable = false, unique = true, length = 128)
    private String configKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_value", nullable = false, columnDefinition = "jsonb")
    private JsonNode configValue;

    @Column(length = 255)
    private String description;

    @Column(name = "updated_by")
    private Long updatedBy;

    protected ConfigEntry() {
    }

    public ConfigEntry(String configKey, JsonNode configValue, String description, Long updatedBy) {
        this.configKey = configKey;
        this.configValue = configValue;
        this.description = description;
        this.updatedBy = updatedBy;
    }

    public String getConfigKey() {
        return configKey;
    }

    public JsonNode getConfigValue() {
        return configValue;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }

    public void change(JsonNode value, Long updatedBy) {
        this.configValue = value;
        this.updatedBy = updatedBy;
    }
}
