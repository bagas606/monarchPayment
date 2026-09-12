package id.ppob2.app.web;

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

    private final ProductRepository productRepository;
    private final PartnerRepository partnerRepository;
    private final OrderApplicationService orderApplicationService;

    public CreateOrderController(ProductRepository productRepository,
                                  PartnerRepository partnerRepository,
                                  OrderApplicationService orderApplicationService) {
        this.productRepository = productRepository;
        this.partnerRepository = partnerRepository;
        this.orderApplicationService = orderApplicationService;
    }

    @PostMapping("/api/v1/orders")
    public ResponseEntity<CreateOrderResponse> createOrder(
            @RequestBody CreateOrderRequest request,
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
                request.callbackUrl());

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
