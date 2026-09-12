package id.ppob2.provider;

import java.math.BigInteger;

/** Section 27.1's {@code fetchPricing(String providerSkuCode)} return shape — same unbuilt-job
 * caveat as {@link ProviderSkuDto}; nothing consumes this yet either (mirrors the unbuilt
 * {@code provider_price}, Section 22.7, flagged in earlier slices). */
public record ProviderPriceDto(String providerSkuCode, BigInteger cost) {
}
