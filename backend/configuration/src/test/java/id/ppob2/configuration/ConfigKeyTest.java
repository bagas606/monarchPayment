package id.ppob2.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ConfigKeyTest {

    @Test
    void everyDefaultIsInsideItsOwnRange() {
        for (ConfigKey k : ConfigKey.values()) {
            assertThat(k.validate(k.defaultValue())).as(k.key()).isEqualByComparingTo(k.defaultValue());
        }
    }

    @Test
    void keysAreUniqueAndLookUpByName() {
        assertThat(Arrays.stream(ConfigKey.values()).map(ConfigKey::key).distinct().count()).isEqualTo(ConfigKey.values().length);
        assertThat(ConfigKey.byKey("routing.weight.margin")).contains(ConfigKey.ROUTING_WEIGHT_MARGIN);
        assertThat(ConfigKey.byKey("nope")).isEmpty();
    }

    @Test
    void rangeAndTypeAreEnforced() {
        assertThatThrownBy(() -> ConfigKey.ROUTING_WEIGHT_MARGIN.validate(new BigDecimal("-1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConfigKey.ROUTING_WEIGHT_MARGIN.validate(new BigDecimal("101"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConfigKey.GENERATION_MAX_PATTERNS.validate(new BigDecimal("10001"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConfigKey.GENERATION_MAX_PATTERNS.validate(new BigDecimal("12.5"))).hasMessageContaining("whole number");
        assertThatThrownBy(() -> ConfigKey.PAYMENT_FEE_FIXED.validate(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(ConfigKey.GENERATION_MAX_PATTERNS.validate(new BigDecimal("500.0"))).isEqualByComparingTo("500");
    }
}
