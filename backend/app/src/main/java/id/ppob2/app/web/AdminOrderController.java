package id.ppob2.app.web;

import id.ppob2.order.domain.ParentOrder;
import id.ppob2.order.repository.ParentOrderRepository;
import id.ppob2.partner.domain.Partner;
import id.ppob2.partner.repository.PartnerRepository;
import id.ppob2.sharedkernel.error.ApiException;
import id.ppob2.sharedkernel.error.ErrorCode;
import id.ppob2.sharedkernel.money.Money;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The read half of the {@code metadata.reseller_txn_id} gap V23 closes: a reverse lookup from a
 * reseller's own transaction id to PPOB2's order.
 *
 * <p><b>Why an endpoint and not just the column.</b> The gap's cost was never "the value is not
 * stored", it was that PPOB2 support could not answer "which of our orders is PPOB1's TX-98213?"
 * without asking PPOB1. Storing the value and leaving the only reader a {@code psql} prompt would
 * repeat exactly what V22 found about the reconciliation queue: "an operator's only way to find an
 * open discrepancy was a psql prompt ... the queue was recorded and actionable but not, in any
 * operational sense, visible."
 *
 * <p><b>This is not TC-ADM-006.</b> That test case is Admin Web transaction search — many filters,
 * paging, a result grid — and it is still {@code X}. This endpoint has exactly one filter and
 * requires it. It takes the {@code /admin/orders} path because that is where transaction search
 * belongs and extending it later is better than replacing it, but the required parameter is what
 * keeps today's version honest: without it, {@code GET /admin/orders} would mean "every order on
 * the platform, newest first", with no partner scope, which is a much larger decision than the one
 * this slice is making.
 *
 * <p>Read-only, so it records nothing to {@code audit_log} — the same reasoning and the same
 * precedent as {@link AdminReconciliationController}'s two GETs and {@link
 * AdminSettlementController}: Section 43's "Recorded" requirement is about admin <em>mutations</em>,
 * and it is load-bearing for TC-ADM-013's 1:1 absolute audit-row counts, which an audited GET would
 * quietly break.
 *
 * <p>No service layer between this and the repository, unlike {@code ReconciliationQueryService}
 * next door. That one exists because a power-set of optional filters needs a {@code Specification}
 * built somewhere; one exact-match query needs no such home, and a pass-through service would be a
 * layer whose only content is the word "return".
 */
@RestController
public class AdminOrderController {

    /**
     * How many matches are returned. The repository asks for one more than this (see {@code
     * ParentOrderRepository.findByResellerTxnId}) so that a truncated result can say so rather than
     * look complete — the same reason {@code AdminReconciliationController} echoes the clamped page
     * size instead of the requested one: a caller shown a truncated answer it cannot detect will
     * read it as the whole answer.
     */
    static final int MAX_RESULTS = 100;

    private final ParentOrderRepository parentOrderRepository;
    private final PartnerRepository partnerRepository;

    public AdminOrderController(ParentOrderRepository parentOrderRepository,
                                 PartnerRepository partnerRepository) {
        this.parentOrderRepository = parentOrderRepository;
        this.partnerRepository = partnerRepository;
    }

    /**
     * {@code reseller_txn_id} is named explicitly in {@code @RequestParam} because the app's global
     * {@code spring.jackson.property-naming-strategy: SNAKE_CASE} applies to JSON (de)serialization
     * only and has no effect on query-string binding — a parameter relying on it would arrive null,
     * and a required parameter arriving null is a 400 for a request the caller got right.
     */
    @GetMapping("/admin/orders")
    @PreAuthorize("hasAuthority('order:view')")
    public ResponseEntity<OrderLookupResponse> lookupByResellerTxnId(
            @RequestParam(name = "reseller_txn_id") String resellerTxnId) {

        // Required already rejects a missing parameter (400, via GlobalExceptionHandler's
        // MissingServletRequestParameterException branch). Blank is the case that gets through:
        // `?reseller_txn_id=` binds to the empty string, which is a legal JSON value and would
        // match any order whose reseller sent one. Rejected rather than run, because an empty
        // result for a typo'd URL reads as "no such transaction" -- an answer the operator would
        // act on.
        if (resellerTxnId.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "'reseller_txn_id' must not be blank.");
        }

        List<ParentOrder> found = parentOrderRepository.findByResellerTxnId(resellerTxnId);
        boolean truncated = found.size() > MAX_RESULTS;
        List<ParentOrder> page = truncated ? found.subList(0, MAX_RESULTS) : found;

        Map<Long, String> partnerCodes = partnerCodesFor(page);

        return ResponseEntity.ok(new OrderLookupResponse(
                resellerTxnId,
                page.size(),
                truncated,
                page.stream().map(order -> OrderMatch.of(order, partnerCodes)).toList()));
    }

    /**
     * Resolved here, at the composition root, in one query for the whole result rather than one per
     * row. {@code order -> partner} is in Section 20.2's allowed graph, so the join could have lived
     * in `order` — it lives here because the partner <em>code</em> is a presentation concern of this
     * response, and because {@link CreateOrderController} and {@link OrderQueryController} already
     * resolve partners at this layer.
     */
    private Map<Long, String> partnerCodesFor(List<ParentOrder> orders) {
        List<Long> ids = orders.stream()
                .map(ParentOrder::getPartnerId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return partnerRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Partner::getId, Partner::getCode));
    }

    /**
     * {@code count} is the number of items in this response, and {@code truncated} says whether
     * there were more. Both are present even when there is one match, because the shape a client
     * parses should not change with the size of the answer.
     */
    public record OrderLookupResponse(
            String resellerTxnId,
            int count,
            boolean truncated,
            List<OrderMatch> items
    ) {
    }

    /**
     * What a support investigation needs to carry on without PPOB1: our order number, whose order it
     * is, what state it reached, and the full {@code metadata} as stored.
     *
     * <p>{@code partnerCode} is included because a reseller id is only unique within a reseller (V23)
     * — with two matches and no partner on the row, the operator cannot tell which company's
     * transaction each one is, which is the whole question. {@code null} for an order with no
     * {@code partner_id} (Section 22.15 allows it for future consumer channels; every order today
     * has one).
     *
     * <p>{@code metadata} is handed back as the raw JSON string rather than a parsed object, so that
     * a key PPOB2 has no reader for still reaches the person investigating. It renders as a JSON
     * string, not an object — deliberate, and the reason the field is named plainly: re-parsing it
     * here would mean deciding what to do with a key this platform does not understand, and dropping
     * it is how the whole field came to be lost in the first place.
     */
    public record OrderMatch(
            Long id,
            String orderId,
            String partnerCode,
            String clientId,
            String state,
            Money parentAmount,
            String customerReference,
            String idempotencyKey,
            String createdAt,
            String updatedAt,
            String metadata
    ) {
        static OrderMatch of(ParentOrder order, Map<Long, String> partnerCodes) {
            return new OrderMatch(
                    order.getId(),
                    order.getOrderNo(),
                    order.getPartnerId() == null ? null : partnerCodes.get(order.getPartnerId()),
                    order.getClientId(),
                    order.getState().name(),
                    order.getParentAmount(),
                    order.getCustomerReference(),
                    order.getIdempotencyKey(),
                    order.getCreatedAt() == null ? null : order.getCreatedAt().toString(),
                    order.getUpdatedAt() == null ? null : order.getUpdatedAt().toString(),
                    order.getMetadata());
        }
    }
}
