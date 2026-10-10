package id.ppob2.app.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.ppob2.catalog.domain.Product;
import id.ppob2.catalog.repository.ProductRepository;
import id.ppob2.order.CreateOrderCommand;
import id.ppob2.order.CreateOrderResult;
import id.ppob2.order.OrderApplicationService;
import id.ppob2.partner.domain.Partner;
import id.ppob2.partner.repository.PartnerRepository;
import id.ppob2.sharedkernel.channel.ChannelContext;
import id.ppob2.sharedkernel.channel.ChannelContextHolder;
import id.ppob2.sharedkernel.error.ApiException;
import id.ppob2.sharedkernel.error.ErrorCode;
import id.ppob2.sharedkernel.money.Money;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * PRD Section 23.4: POST /api/v1/orders. Resolves {@code product_code} (catalog) and the
 * caller's numeric channel/partner ids (partner) before invoking {@code OrderApplicationService}
 * — that composition deliberately lives here rather than in `order`, for the same reason
 * {@code ChannelContextResolver} lives here: Section 20.2 does not grant `order` a compile
 * dependency on `catalog`, and this is the "API / Channel Adapters" layer from Section 19.
 */
@RestController
public class CreateOrderController {

    /**
     * Bound because storing is not the same as accepting. Before V23 this field went nowhere, so
     * its size cost nothing; now it is durable storage whose size the partner chooses, on a table
     * every settlement and reconciliation query reads. Section 23.4's own example is about forty
     * characters, so this is fifty times the documented shape — a ceiling on abuse, not a
     * constraint a real integration can notice.
     */
    static final int MAX_METADATA_CHARS = 2000;

    private final ProductRepository productRepository;
    private final PartnerRepository partnerRepository;
    private final OrderApplicationService orderApplicationService;
    private final ObjectMapper objectMapper;

    public CreateOrderController(ProductRepository productRepository,
                                  PartnerRepository partnerRepository,
                                  OrderApplicationService orderApplicationService,
                                  ObjectMapper objectMapper) {
        this.productRepository = productRepository;
        this.partnerRepository = partnerRepository;
        this.orderApplicationService = orderApplicationService;
        this.objectMapper = objectMapper;
    }

    /**
     * {@code @Valid} is load-bearing and was missing. {@link CreateOrderRequest} has carried
     * {@code @NotBlank}/{@code @NotNull} since the first slice, and without this annotation Spring
     * never ran either: the constraints were decorative, and a request omitting
     * {@code parent_amount} reached {@code Money.of(null)} and came back {@code 500 INTERNAL_ERROR}
     * — which Section 23.1 tells partners to retry, for a request no retry can fix.
     *
     * <p>{@code GlobalExceptionHandler.handleBadRequest} already handles
     * {@code MethodArgumentNotValidException} and its Javadoc already names "a bean-validation
     * failure" among the cases it covers, so the envelope this now produces is the one that handler
     * was written for and had never once been reached. Fixed here rather than filed because this
     * slice adds a hand-checked bound to the same request record, and a hand-checked bound sitting
     * above three unenforced annotations is worse than either.
     */
    @PostMapping("/api/v1/orders")
    public ResponseEntity<CreateOrderResponse> createOrder(
            @Valid @RequestBody CreateOrderRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            HttpServletRequest servletRequest) {

        ChannelContext channelContext = ChannelContextHolder.get();
        Product product = productRepository.findByCode(request.productCode())
                .filter(Product::isActive)
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_ERROR,
                        "Unknown or inactive product_code: " + request.productCode()));

        Partner partner = partnerRepository.findByCode(channelContext.partnerId())
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR,
                        "Authenticated client resolved to an unknown partner."));

        CreateOrderCommand command = new CreateOrderCommand(
                channelContext,
                partner.getChannelId(),
                partner.getId(),
                product.getId(),
                product.getCode(),
                product.getCategory(),
                Money.of(request.parentAmount()),
                request.customerReference(),
                idempotencyKey,
                request.callbackUrl(),
                serializeMetadata(request.metadata()));

        CreateOrderResult result = orderApplicationService.createOrder(command);

        Object correlationId = servletRequest.getAttribute(CorrelationIdFilter.ATTRIBUTE);
        CreateOrderResponse body = new CreateOrderResponse(
                result.orderNo(),
                result.state().name(),
                result.parentAmount(),
                new PaymentBlock("QRIS_DYNAMIC", result.qrPayload(), result.paymentExpiresAt()),
                correlationId != null ? correlationId.toString() : null,
                result.createdAt());

        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /**
     * Section 23.4's {@code metadata} object, as the JSON string {@code parent_order.metadata}
     * stores (V23). Before V23 this field was bound and then dropped; the whole fix is that it now
     * reaches the command.
     *
     * <p><b>A null map must produce a Java null, not {@code "null"}.</b>
     * {@code writeValueAsString(null)} returns the three characters {@code null}, which {@code
     * jsonb} accepts and stores as a JSON null — a non-NULL column value that then fails
     * {@code metadata IS NULL}, matches nothing in the {@code ->>} lookup, and renders as the string
     * "null" to anyone reading the table. Every order placed without metadata would have carried it.
     * Hence the explicit check first, and {@code ParentOrder.getMetadata}'s note that null here
     * means SQL NULL.
     *
     * <p>An empty object is left as {@code {}} rather than folded into null: the caller sent a
     * {@code metadata} key, and "sent nothing in it" and "sent no metadata at all" are different
     * facts about the request that this column can afford to keep apart.
     *
     * <p>The size check is here, by hand, rather than as a {@code @Size} on the record. A bound on
     * a {@code Map} field is awkward to express in bean validation, and more to the point this
     * check must hold whether or not the method-level {@code @Valid} above survives a future edit —
     * it did not exist until this slice, which is exactly the failure mode being guarded against.
     */
    private String serializeMetadata(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException unreachable) {
            // Jackson has already parsed this map out of the request body, so every value in it is
            // a String, Number, Boolean, List, Map or null and re-serializing cannot fail. Mapped
            // to the documented 400 rather than left to become a 500 if that ever stops being true.
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "'metadata' could not be stored as JSON.");
        }
        if (json.length() > MAX_METADATA_CHARS) {
            // The limit is reported; the offending payload is not echoed back. Partner request
            // content has no business in an error envelope -- the same reason
            // GlobalExceptionHandler refuses to echo Jackson's parse messages, which quote the
            // body fragment they choked on.
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "'metadata' serializes to " + json.length() + " characters, over the "
                            + MAX_METADATA_CHARS + "-character limit.");
        }
        return json;
    }

    /**
     * {@code metadata} is typed as an open map, not a record with a {@code resellerTxnId} field.
     * Section 23.4 documents it as an object with one key and does not say it is closed, so a
     * narrower type would reject a key some other partner is already sending — turning a field
     * PPOB2 merely fails to use into a field PPOB2 refuses. The one key with a reader lives in the
     * expression index V23 creates, not in this type.
     */
    public record CreateOrderRequest(
            @NotBlank String productCode,
            @NotNull BigInteger parentAmount,
            String customerReference,
            String callbackUrl,
            Map<String, Object> metadata
    ) {
    }

    public record PaymentBlock(String method, String qrPayload, Instant expiresAt) {
    }

    public record CreateOrderResponse(
            String orderId,
            String state,
            Money parentAmount,
            PaymentBlock payment,
            String correlationId,
            Instant createdAt
    ) {
    }
}
