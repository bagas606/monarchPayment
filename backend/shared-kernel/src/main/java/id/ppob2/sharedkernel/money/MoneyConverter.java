package id.ppob2.sharedkernel.money;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.math.BigInteger;

/**
 * Persists {@link Money} as {@code NUMERIC(18,0)} (Section 21.1). {@code autoApply = true} so
 * every entity field typed {@code Money} converts automatically — no per-field {@code @Convert}
 * needed, which is what keeps the wrapper from being skipped under time pressure.
 */
@Converter(autoApply = true)
public class MoneyConverter implements AttributeConverter<Money, BigInteger> {

    @Override
    public BigInteger convertToDatabaseColumn(Money attribute) {
        return attribute == null ? null : attribute.toBigInteger();
    }

    @Override
    public Money convertToEntityAttribute(BigInteger dbData) {
        return dbData == null ? null : Money.of(dbData);
    }
}
