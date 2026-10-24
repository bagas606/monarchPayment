package id.ppob2.configuration;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Optional;

/**
 * Every configuration value the platform knows, with its type, allowed range and default. The
 * {@code configuration} table (Section 22.25) holds only overrides, so a key absent from it means "use the
 * default here", and a key not in this enum cannot be written at all: an admin typo must not create a setting
 * nothing reads.
 *
 * <p>Only weights whose inputs exist are exposed. Section 31.3 lists seven terms; provider-health, availability,
 * capacity, load-balance and failure have no data source in this codebase yet, so offering sliders for them would
 * be configuration that changes nothing. They appear when their inputs do.
 */
public enum ConfigKey {

    ROUTING_WEIGHT_MARGIN("routing.weight.margin", Type.DECIMAL, "1", "0", "100",
            "w_margin: weight on net margin percent in a pattern's score"),
    ROUTING_WEIGHT_CHILDCOUNT("routing.weight.childcount", Type.DECIMAL, "0.5", "0", "100",
            "w_childcount: score points subtracted per child order beyond the first"),
    PAYMENT_FEE_FIXED("economics.payment_fee.fixed", Type.INTEGER, "0", "0", "1000000",
            "Fixed payment-gateway fee per order, Rupiah. 0 until the real MDR is confirmed with the PG contract"),
    PAYMENT_FEE_PERCENT("economics.payment_fee.percent", Type.DECIMAL, "0", "0", "10",
            "Percentage payment-gateway fee on the order amount. 0 until the real MDR is confirmed"),
    MIN_NET_MARGIN_PCT("economics.min_net_margin_pct", Type.DECIMAL, "0", "-100", "100",
            "Eligibility floor: a pattern whose net margin percent is below this is not routable"),
    GENERATION_MAX_PATTERNS("generation.max_patterns_per_amount", Type.INTEGER, "10000", "1", "10000",
            "K: patterns kept per (product, amount). Section 29.3 target is at most 10,000"),
    GENERATION_MAX_QUANTITY("generation.max_total_quantity", Type.INTEGER, "40", "1", "100",
            "Hard ceiling on child-order units in one generated pattern"),
    GENERATION_EXTRA_QUANTITY("generation.extra_quantity", Type.INTEGER, "4", "0", "20",
            "Units beyond the fewest achievable that a pattern may use (relative, so large amounts stay servable)");

    public enum Type { INTEGER, DECIMAL }

    private final String key;
    private final Type type;
    private final BigDecimal defaultValue;
    private final BigDecimal min;
    private final BigDecimal max;
    private final String description;

    ConfigKey(String key, Type type, String defaultValue, String min, String max, String description) {
        this.key = key;
        this.type = type;
        this.defaultValue = new BigDecimal(defaultValue);
        this.min = new BigDecimal(min);
        this.max = new BigDecimal(max);
        this.description = description;
    }

    public String key() {
        return key;
    }

    public Type type() {
        return type;
    }

    public BigDecimal defaultValue() {
        return defaultValue;
    }

    public BigDecimal min() {
        return min;
    }

    public BigDecimal max() {
        return max;
    }

    public String description() {
        return description;
    }

    public static Optional<ConfigKey> byKey(String key) {
        return Arrays.stream(values()).filter(k -> k.key.equals(key)).findFirst();
    }

    /** @throws IllegalArgumentException with a message safe to show an admin */
    public BigDecimal validate(BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException("A value is required for " + key + ".");
        }
        if (type == Type.INTEGER && value.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException(key + " must be a whole number.");
        }
        if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw new IllegalArgumentException(key + " must be between " + min.toPlainString() + " and " + max.toPlainString() + ".");
        }
        return type == Type.INTEGER ? value.setScale(0) : value.stripTrailingZeros();
    }
}
