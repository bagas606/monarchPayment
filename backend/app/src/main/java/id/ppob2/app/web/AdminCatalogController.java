package id.ppob2.app.web;

import id.ppob2.admin.security.AdminPrincipal;
import id.ppob2.app.admin.AdminCatalogService;
import id.ppob2.pricing.ProviderPriceService;
import id.ppob2.sharedkernel.error.ApiException;
import id.ppob2.sharedkernel.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Section 41.3 (catalog, pricing) and the supported-amount half of 41.11. Reads need {@code catalog:view}; changing products,
 * providers, SKUs and amounts {@code catalog:edit}; a price {@code pricing:edit} (Section 42.2 keeps pricing among the
 * critical, minimal-role operations). Nothing here deletes: see {@link AdminCatalogService}.
 */
@RestController
public class AdminCatalogController {

    private final AdminCatalogService catalog;
    private final ProviderPriceService prices;

    public AdminCatalogController(AdminCatalogService catalog, ProviderPriceService prices) {
        this.catalog = catalog;
        this.prices = prices;
    }

    @GetMapping("/admin/products")
    @PreAuthorize("hasAuthority('catalog:view')")
    public ResponseEntity<?> products() {
        return ResponseEntity.ok(Map.of("items", catalog.products()));
    }

    @GetMapping("/admin/providers")
    @PreAuthorize("hasAuthority('catalog:view')")
    public ResponseEntity<?> providers() {
        return ResponseEntity.ok(Map.of("items", catalog.providers()));
    }

    @GetMapping("/admin/provider-skus")
    @PreAuthorize("hasAuthority('catalog:view')")
    public ResponseEntity<?> skus(@RequestParam(name = "product_id", required = false) Long productId,
                                  @RequestParam(name = "provider_id", required = false) Long providerId,
                                  @RequestParam(name = "status", required = false) String status) {
        return ResponseEntity.ok(Map.of("items", catalog.skus(productId, providerId, status == null || status.isBlank() ? null : status.trim())));
    }

    @GetMapping("/admin/provider-skus/{id}/prices")
    @PreAuthorize("hasAuthority('catalog:view')")
    public ResponseEntity<?> priceHistory(@PathVariable("id") long id) {
        return ResponseEntity.ok(Map.of("items", catalog.priceHistory(id)));
    }

    @GetMapping("/admin/supported-amounts")
    @PreAuthorize("hasAuthority('catalog:view')")
    public ResponseEntity<?> amounts(@RequestParam(name = "product_category", required = false) String category) {
        return ResponseEntity.ok(Map.of("items", catalog.supportedAmounts(category == null || category.isBlank() ? null : category.trim())));
    }

    @PostMapping("/admin/products")
    @PreAuthorize("hasAuthority('catalog:edit')")
    public ResponseEntity<?> createProduct(@RequestBody ProductBody b, @AuthenticationPrincipal AdminPrincipal p, HttpServletRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(catalog.createProduct(p.getAdminUserId(), r.getRemoteAddr(), b.code(), b.name(), b.category()));
    }

    @PatchMapping("/admin/products/{id}")
    @PreAuthorize("hasAuthority('catalog:edit')")
    public ResponseEntity<?> updateProduct(@PathVariable("id") long id, @RequestBody ProductBody b, @AuthenticationPrincipal AdminPrincipal p, HttpServletRequest r) {
        return ResponseEntity.ok(catalog.updateProduct(p.getAdminUserId(), r.getRemoteAddr(), id, b.name(), b.status()));
    }

    @PostMapping("/admin/providers")
    @PreAuthorize("hasAuthority('catalog:edit')")
    public ResponseEntity<?> createProvider(@RequestBody ProviderBody b, @AuthenticationPrincipal AdminPrincipal p, HttpServletRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(catalog.createProvider(p.getAdminUserId(), r.getRemoteAddr(), b.code(), b.name(), b.timeoutMs(), b.rateLimitPerMin()));
    }

    @PatchMapping("/admin/providers/{id}")
    @PreAuthorize("hasAuthority('catalog:edit')")
    public ResponseEntity<?> updateProvider(@PathVariable("id") long id, @RequestBody ProviderBody b, @AuthenticationPrincipal AdminPrincipal p, HttpServletRequest r) {
        return ResponseEntity.ok(catalog.updateProvider(p.getAdminUserId(), r.getRemoteAddr(), id, b.name(), b.status(), b.timeoutMs(), b.rateLimitPerMin()));
    }

    @PostMapping("/admin/provider-skus")
    @PreAuthorize("hasAuthority('catalog:edit')")
    public ResponseEntity<?> createSku(@RequestBody SkuBody b, @AuthenticationPrincipal AdminPrincipal p, HttpServletRequest r) {
        if (b.providerId() == null || b.productId() == null || b.faceValue() == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "'provider_id', 'product_id', 'provider_sku_code' and 'face_value' are required.");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(catalog.createSku(p.getAdminUserId(), r.getRemoteAddr(), b.providerId(),
                b.productId(), b.providerSkuCode(), b.faceValue(), b.quotaDaily()));
    }

    @PatchMapping("/admin/provider-skus/{id}")
    @PreAuthorize("hasAuthority('catalog:edit')")
    public ResponseEntity<?> updateSku(@PathVariable("id") long id, @RequestBody SkuBody b, @AuthenticationPrincipal AdminPrincipal p, HttpServletRequest r) {
        return ResponseEntity.ok(catalog.updateSku(p.getAdminUserId(), r.getRemoteAddr(), id, b.status(), b.quotaDaily(), b.faceValue(), b.reason()));
    }

    @PostMapping("/admin/provider-skus/{id}/prices")
    @PreAuthorize("hasAuthority('pricing:edit')")
    public ResponseEntity<?> setPrice(@PathVariable("id") long id, @RequestBody PriceBody b, @AuthenticationPrincipal AdminPrincipal p, HttpServletRequest r) {
        if (b.providerCost() == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "'provider_cost' is required.");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(catalog.setPrice(p.getAdminUserId(), r.getRemoteAddr(), id, b.providerCost(), b.reason(), prices));
    }

    @PostMapping("/admin/supported-amounts")
    @PreAuthorize("hasAuthority('catalog:edit')")
    public ResponseEntity<?> createAmount(@RequestBody AmountBody b, @AuthenticationPrincipal AdminPrincipal p, HttpServletRequest r) {
        if (b.amount() == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "'amount' is required.");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(catalog.createAmount(p.getAdminUserId(), r.getRemoteAddr(), b.productCategory(), b.amount()));
    }

    @PatchMapping("/admin/supported-amounts/{id}")
    @PreAuthorize("hasAuthority('catalog:edit')")
    public ResponseEntity<?> setAmountStatus(@PathVariable("id") long id, @RequestBody AmountBody b, @AuthenticationPrincipal AdminPrincipal p, HttpServletRequest r) {
        return ResponseEntity.ok(catalog.setAmountStatus(p.getAdminUserId(), r.getRemoteAddr(), id, b.status()));
    }

    public record ProductBody(String code, String name, String category, String status) {
    }

    public record ProviderBody(String code, String name, String status, Integer timeoutMs, Integer rateLimitPerMin) {
    }

    public record SkuBody(Long providerId, Long productId, String providerSkuCode, Long faceValue, Integer quotaDaily, String status, String reason) {
    }

    public record PriceBody(Long providerCost, String reason) {
    }

    public record AmountBody(String productCategory, Long amount, String status) {
    }
}
