package id.ppob2.provider;

import java.math.BigInteger;

/** Section 27.1's {@code fetchCatalog()} return shape — for a provider-catalog-sync job that
 * doesn't exist yet (nothing in this codebase consumes it). Kept only for interface fidelity. */
public record ProviderSkuDto(String providerSkuCode, String name, BigInteger faceValue) {
}
