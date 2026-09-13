package id.ppob2.provider;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Development-only stand-in for the per-provider adapters Section 27.1 describes ({@code
 * ProviderAAdapter}, {@code ProviderBAdapter}, ...). No real provider integration exists yet, and
 * — unlike {@code StubQrisPaymentGateway}, which has exactly one payment gateway to stand in for —
 * Section 27.1's design calls for a registry of adapters keyed by provider, which this slice does
 * not build: there is a single generic bean used for every {@code provider_id}, flagged as a gap
 * in the fulfillment README notes rather than implemented, since there is no second real provider
 * to differentiate it against yet.
 *
 * <p>{@code fail-provider-sku-ids} is a deliberate dev-only test hook, not a hack left behind by
 * accident: with no real provider to naturally fail against, exercising Section 33.2's {@code
 * FULFILLING -> PARTIAL_FAILED}/{@code FAILED} paths end-to-end requires a way to force a
 * particular child order's purchase to fail. A configured provider_sku id fails every time (never
 * succeeds after retry) to also exercise Section 27.2's bounded-retry path predictably.
 *
 * <p>{@code ambiguous-provider-sku-ids} is the same kind of dev-only knob, for Section 34.1's
 * third failure class: "connection reset after the request was already sent." A configured
 * provider_sku id returns {@link PurchaseStatus#AMBIGUOUS} every time from {@link #purchase} —
 * deterministic, like the fail list, so the inquiry-before-retry path in {@code
 * FulfillmentExecutionService} is exercisable predictably rather than depending on real network
 * flakiness that doesn't exist in a stub.
 *
 * <p>{@link #inquire} always confirms {@code SUCCESS} — simulating Section 34.1's canonical
 * ambiguous case (the purchase actually went through; only the response was lost) — with a
 * provider-shaped reference synthesized fresh, not the caller's own idempotency key echoed back:
 * a real provider's inquiry response carries its own transaction reference, never the caller's.
 *
 * <p>{@code @Profile("!prod")} guard for the same reason as {@code StubQrisPaymentGateway}: this
 * must never be the only {@link GameProvider} bean in a production context.
 */
@Component
@Profile("!prod")
public class StubGameProviderAdapter implements GameProvider {

    private final Set<Long> failProviderSkuIds;
    private final Set<Long> ambiguousProviderSkuIds;

    public StubGameProviderAdapter(@Value("${ppob2.fulfillment.stub-provider.fail-provider-sku-ids:}") String failProviderSkuIds,
                                    @Value("${ppob2.fulfillment.stub-provider.ambiguous-provider-sku-ids:}") String ambiguousProviderSkuIds) {
        this.failProviderSkuIds = parseIds(failProviderSkuIds);
        this.ambiguousProviderSkuIds = parseIds(ambiguousProviderSkuIds);
    }

    private static Set<Long> parseIds(String csv) {
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(Long::parseLong)
                .collect(Collectors.toSet());
    }

    @Override
    public List<ProviderSkuDto> fetchCatalog() {
        throw new UnsupportedOperationException("No provider-catalog-sync job exists yet to consume this.");
    }

    @Override
    public ProviderPriceDto fetchPricing(String providerSkuCode) {
        throw new UnsupportedOperationException("No provider-price-sync job exists yet to consume this.");
    }

    @Override
    public PurchaseResult purchase(PurchaseRequest request) {
        if (failProviderSkuIds.contains(request.providerSkuId())) {
            return PurchaseResult.failed("STUB_INJECTED_FAILURE for provider_sku " + request.providerSkuId());
        }
        if (ambiguousProviderSkuIds.contains(request.providerSkuId())) {
            return PurchaseResult.ambiguous("STUB_INJECTED_AMBIGUOUS for provider_sku " + request.providerSkuId());
        }
        return PurchaseResult.success("STUBPROV-" + UUID.randomUUID());
    }

    @Override
    public InquiryResult inquire(String providerReference) {
        return new InquiryResult("STUBPROV-INQUIRY-" + UUID.randomUUID(), PurchaseStatus.SUCCESS);
    }
}
