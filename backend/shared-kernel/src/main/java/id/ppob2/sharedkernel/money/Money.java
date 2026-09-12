package id.ppob2.sharedkernel.money;

import com.fasterxml.jackson.annotation.JsonValue;
import java.io.Serializable;
import java.math.BigInteger;
import java.util.Objects;

/**
 * Whole-Rupiah monetary value. PRD Section 21.1 prohibits float/double for any money field;
 * this wrapper is the only allowed representation of an amount in the domain layer.
 */
public final class Money implements Comparable<Money>, Serializable {

    public static final Money ZERO = new Money(BigInteger.ZERO);

    private final BigInteger amount;

    private Money(BigInteger amount) {
        this.amount = Objects.requireNonNull(amount, "amount");
    }

    public static Money of(long rupiah) {
        return new Money(BigInteger.valueOf(rupiah));
    }

    public static Money of(BigInteger rupiah) {
        return new Money(rupiah);
    }

    public Money add(Money other) {
        return new Money(this.amount.add(other.amount));
    }

    public Money subtract(Money other) {
        return new Money(this.amount.subtract(other.amount));
    }

    public Money multiply(long factor) {
        return new Money(this.amount.multiply(BigInteger.valueOf(factor)));
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isGreaterThan(Money other) {
        return this.amount.compareTo(other.amount) > 0;
    }

    @JsonValue
    public BigInteger toBigInteger() {
        return amount;
    }

    public long toLong() {
        return amount.longValueExact();
    }

    @Override
    public int compareTo(Money other) {
        return this.amount.compareTo(other.amount);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Money money)) return false;
        return amount.equals(money.amount);
    }

    @Override
    public int hashCode() {
        return amount.hashCode();
    }

    @Override
    public String toString() {
        return amount.toString();
    }
}
