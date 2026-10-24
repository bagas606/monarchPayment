package id.ppob2.app.web;

import id.ppob2.app.admin.AdminTransactionReader;
import id.ppob2.app.admin.AdminTransactionReader.ChildRow;
import id.ppob2.app.admin.AdminTransactionReader.OrderDetail;
import id.ppob2.app.admin.AdminTransactionReader.Page;
import id.ppob2.app.admin.AdminTransactionReader.ParentRow;
import id.ppob2.app.admin.Paging;
import id.ppob2.app.admin.SqlFilter;
import id.ppob2.order.domain.ChildOrderState;
import id.ppob2.order.domain.OrderState;
import id.ppob2.sharedkernel.error.ApiException;
import id.ppob2.sharedkernel.error.ErrorCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Section 41.2 / TC-ADM-006: transaction list, search and drill-down.
 *
 * <p>Read-only, so nothing is written to {@code audit_log} - same precedent as {@link
 * AdminOrderController} and {@link AdminReconciliationController} (Section 43 "Recorded" is about
 * mutations, and TC-ADM-013's absolute audit-row counts would break if a GET wrote one).
 *
 * <p>{@code GET /admin/orders} (reseller id lookup) is left as it is; it is the narrow, partner-facing
 * support query and these are the general ones, under {@code /admin/parent-orders} next to the
 * mutations that already live there ({@code POST /admin/parent-orders/{id}/refund}).
 */
@RestController
public class AdminTransactionController {

    private final AdminTransactionReader reader;

    public AdminTransactionController(AdminTransactionReader reader) {
        this.reader = reader;
    }

    @GetMapping("/admin/parent-orders")
    @PreAuthorize("hasAuthority('order:view')")
    public ResponseEntity<Page<ParentRow>> listParentOrders(
            @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "partner_code", required = false) String partnerCode,
            @RequestParam(name = "product_code", required = false) String productCode,
            @RequestParam(name = "order_no", required = false) String orderNo,
            @RequestParam(name = "customer_reference", required = false) String customerReference,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size) {
        OrderState parsed = Paging.enumValue("state", state, OrderState.class);
        return ResponseEntity.ok(reader.listParentOrders(parsed == null ? null : parsed.name(),
                SqlFilter.blankToNull(partnerCode), SqlFilter.blankToNull(productCode), SqlFilter.blankToNull(orderNo),
                SqlFilter.blankToNull(customerReference), Paging.instant("from", from), Paging.instant("to", to), Paging.of(page, size)));
    }

    @GetMapping("/admin/parent-orders/{id}")
    @PreAuthorize("hasAuthority('order:view')")
    public ResponseEntity<OrderDetail> parentOrderDetail(@PathVariable("id") long id) {
        return ResponseEntity.ok(reader.orderDetail(id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Order not found.")));
    }

    @GetMapping("/admin/child-orders")
    @PreAuthorize("hasAuthority('order:view')")
    public ResponseEntity<Page<ChildRow>> listChildOrders(
            @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "parent_order_id", required = false) Long parentOrderId,
            @RequestParam(name = "provider_code", required = false) String providerCode,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size) {
        ChildOrderState parsed = Paging.enumValue("state", state, ChildOrderState.class);
        return ResponseEntity.ok(reader.listChildOrders(parsed == null ? null : parsed.name(), parentOrderId,
                SqlFilter.blankToNull(providerCode), Paging.of(page, size)));
    }
}
