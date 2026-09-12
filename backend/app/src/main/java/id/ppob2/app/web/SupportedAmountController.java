package id.ppob2.app.web;

import id.ppob2.catalog.domain.Product;
import id.ppob2.catalog.repository.ProductRepository;
import id.ppob2.configuration.SupportedAmountQueryService;
import id.ppob2.sharedkernel.error.ApiException;
import id.ppob2.sharedkernel.error.ErrorCode;
import id.ppob2.sharedkernel.money.Money;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** PRD Section 23.3: GET /api/v1/config/supported-amounts. */
@RestController
public class SupportedAmountController {

    private final ProductRepository productRepository;
    private final SupportedAmountQueryService supportedAmountQueryService;

    public SupportedAmountController(ProductRepository productRepository,
                                      SupportedAmountQueryService supportedAmountQueryService) {
        this.productRepository = productRepository;
        this.supportedAmountQueryService = supportedAmountQueryService;
    }

    @GetMapping("/api/v1/config/supported-amounts")
    public SupportedAmountsResponse getSupportedAmounts(@RequestParam("product_code") String productCode) {
        Product product = productRepository.findByCode(productCode)
                .filter(Product::isActive)
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_ERROR, "Unknown or inactive product_code: " + productCode));

        List<Money> amounts = supportedAmountQueryService.activeAmountsForCategory(product.getCategory());
        return new SupportedAmountsResponse(productCode, "IDR", amounts, Instant.now());
    }

    public record SupportedAmountsResponse(
            String productCode,
            String currency,
            List<Money> amounts,
            Instant generatedAt
    ) {
    }
}
