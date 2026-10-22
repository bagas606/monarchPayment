package id.ppob2.app.web;

import id.ppob2.app.admin.AdminPaymentReader;
import id.ppob2.app.admin.AdminPaymentReader.EventRow;
import id.ppob2.app.admin.AdminPaymentReader.PaymentDetail;
import id.ppob2.app.admin.AdminPaymentReader.PaymentRow;
import id.ppob2.app.admin.AdminTransactionReader.Page;
import id.ppob2.app.admin.Paging;
import id.ppob2.app.admin.SqlFilter;
import id.ppob2.payment.domain.PaymentStatus;
import id.ppob2.sharedkernel.error.ApiException;
import id.ppob2.sharedkernel.error.ErrorCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Section 41.7: PG transaction list/detail and callback history. See {@link AdminPaymentReader} for what is withheld. */
@RestController
public class AdminPaymentController {

    private final AdminPaymentReader reader;

    public AdminPaymentController(AdminPaymentReader reader) {
        this.reader = reader;
    }

    @GetMapping("/admin/payments")
    @PreAuthorize("hasAuthority('payment:view')")
    public ResponseEntity<Page<PaymentRow>> list(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "pg_reference", required = false) String pgReference,
            @RequestParam(name = "parent_order_id", required = false) Long parentOrderId,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size) {
        PaymentStatus parsed = Paging.enumValue("status", status, PaymentStatus.class);
        return ResponseEntity.ok(reader.listPayments(parsed == null ? null : parsed.name(),
                SqlFilter.blankToNull(pgReference), parentOrderId, Paging.instant("from", from),
                Paging.instant("to", to), Paging.of(page, size)));
    }

    @GetMapping("/admin/payments/{id}")
    @PreAuthorize("hasAuthority('payment:view')")
    public ResponseEntity<PaymentDetail> detail(@PathVariable("id") long id) {
        return ResponseEntity.ok(reader.paymentDetail(id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Payment not found.")));
    }

    /** Callbacks matched to a payment. Rejected / invalid-signature attempts are {@code /admin/webhook-events?status=FAILED}. */
    @GetMapping("/admin/payment-events")
    @PreAuthorize("hasAuthority('payment:view')")
    public ResponseEntity<Page<EventRow>> events(
            @RequestParam(name = "signature_valid", required = false) Boolean signatureValid,
            @RequestParam(name = "payment_id", required = false) Long paymentId,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size) {
        return ResponseEntity.ok(reader.listEvents(signatureValid, paymentId, Paging.instant("from", from),
                Paging.instant("to", to), Paging.of(page, size)));
    }

    /** Section 41.7's rejected-callback view: the whole webhook log, no payloads. */
    @GetMapping("/admin/webhook-events")
    @PreAuthorize("hasAuthority('payment:view')")
    public ResponseEntity<Page<AdminPaymentReader.WebhookEventRow>> webhookEvents(
            @RequestParam(name = "direction", required = false) String direction,
            @RequestParam(name = "source", required = false) String source,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size) {
        id.ppob2.webhook.domain.WebhookDirection parsed =
                Paging.enumValue("direction", direction, id.ppob2.webhook.domain.WebhookDirection.class);
        return ResponseEntity.ok(reader.listWebhookEvents(parsed == null ? null : parsed.name(),
                SqlFilter.blankToNull(source), SqlFilter.blankToNull(status), Paging.instant("from", from),
                Paging.instant("to", to), Paging.of(page, size)));
    }
}
