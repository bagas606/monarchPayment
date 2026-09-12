package id.ppob2.provider;

import java.util.List;

/** Exact interface from PRD Section 27.1. Only {@link #purchase} and {@link #inquire} have a real
 * implementation in this slice's stub — see {@code StubGameProviderAdapter}'s Javadoc for why
 * {@link #fetchCatalog}/{@link #fetchPricing} are unimplemented rather than faked. */
public interface GameProvider {
    List<ProviderSkuDto> fetchCatalog();

    ProviderPriceDto fetchPricing(String providerSkuCode);

    PurchaseResult purchase(PurchaseRequest request);

    InquiryResult inquire(String providerReference);

    default void handleCallback(CallbackPayload payload) {
        // optional, provider-dependent — no provider adapter in this codebase pushes callbacks yet.
    }
}
