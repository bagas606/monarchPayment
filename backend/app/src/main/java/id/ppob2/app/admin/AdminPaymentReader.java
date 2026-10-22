package id.ppob2.app.admin;

import static id.ppob2.app.admin.AdminTransactionReader.instant;
import static id.ppob2.app.admin.AdminTransactionReader.page;

import id.ppob2.app.admin.AdminTransactionReader.Page;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read model for Section 41.7 (payments and callback history).
 *
 * <p>What is deliberately absent, by never being selected: {@code payment.qr_payload} (the QR content
 * is replaced by a {@code has_qr} flag, as 41.7 asks), {@code payment_event.raw_payload} and
 * {@code webhook_event.payload} (the gateway's body, which carries payer data and the signed material).
 *
 * <p><b>Two tables, because two different things happen to a callback.</b> {@code payment_event} holds
 * callbacks that were matched to a payment; a callback whose signature fails never gets that far, and
 * is recorded only in {@code webhook_event} with status {@code FAILED} (Section 22.24: every attempt is
 * logged "regardless of outcome"). So the "rejected / invalid-signature attempts for security review"
 * that Section 41.7 asks for live in {@link #listWebhookEvents}, not in {@link #listEvents} - a first
 * draft read {@code payment_event.signature_valid = false} for that and would have shown an empty list
 * on a system under attack.
 */
@Component
public class AdminPaymentReader {

    private final NamedParameterJdbcTemplate jdbc;

    public AdminPaymentReader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record PaymentRow(long id, long parentOrderId, String orderNo, String pgReference, String method,
                             String amount, String status, boolean hasQr, Instant expiresAt, Instant paidAt,
                             Instant refundedAt) {
    }

    private static final String PAYMENT_SELECT = """
            SELECT p.id, p.parent_order_id, po.order_no, p.pg_reference, p.method, p.amount, p.status,
                   (p.qr_payload IS NOT NULL) AS has_qr, p.expires_at, p.paid_at, p.refunded_at
            FROM payment p JOIN parent_order po ON po.id = p.parent_order_id
            """;

    @Transactional(readOnly = true)
    public Page<PaymentRow> listPayments(String status, String pgReference, Long parentOrderId, Instant from,
                                         Instant to, Paging paging) {
        SqlFilter f = new SqlFilter()
                .and("p.status = :status", "status", status)
                .and("p.pg_reference = :pg", "pg", pgReference)
                .and("p.parent_order_id = :parent", "parent", parentOrderId)
                .and("po.created_at >= :from", "from", SqlFilter.ts(from))
                .and("po.created_at < :to", "to", SqlFilter.ts(to));
        List<PaymentRow> rows = jdbc.query(PAYMENT_SELECT + f.where() + " ORDER BY p.id DESC LIMIT :limit OFFSET :offset",
                f.params(paging), (rs, i) -> payment(rs));
        return page(rows, paging);
    }

    private static PaymentRow payment(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PaymentRow(rs.getLong("id"), rs.getLong("parent_order_id"), rs.getString("order_no"),
                rs.getString("pg_reference"), rs.getString("method"), rs.getBigDecimal("amount").toPlainString(),
                rs.getString("status"), rs.getBoolean("has_qr"), instant(rs, "expires_at"), instant(rs, "paid_at"),
                instant(rs, "refunded_at"));
    }

    public record EventRow(long id, long paymentId, String eventType, boolean signatureValid, String dedupKey,
                           Instant receivedAt) {
    }

    public record PaymentDetail(PaymentRow payment, List<EventRow> events) {
    }

    @Transactional(readOnly = true)
    public Optional<PaymentDetail> paymentDetail(long id) {
        MapSqlParameterSource p = new MapSqlParameterSource("id", id);
        List<PaymentRow> rows = jdbc.query(PAYMENT_SELECT + " WHERE p.id = :id", p, (rs, i) -> payment(rs));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        List<EventRow> events = jdbc.query("""
                SELECT id, payment_id, event_type, signature_valid, dedup_key, received_at
                FROM payment_event WHERE payment_id = :id ORDER BY received_at, id
                """, p, (rs, i) -> event(rs));
        return Optional.of(new PaymentDetail(rows.get(0), events));
    }

    private static EventRow event(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new EventRow(rs.getLong("id"), rs.getLong("payment_id"), rs.getString("event_type"),
                rs.getBoolean("signature_valid"), rs.getString("dedup_key"), instant(rs, "received_at"));
    }

    /** Callbacks that were matched to a payment. Rejected ones are in {@link #listWebhookEvents}. */
    @Transactional(readOnly = true)
    public Page<EventRow> listEvents(Boolean signatureValid, Long paymentId, Instant from, Instant to,
                                     Paging paging) {
        SqlFilter f = new SqlFilter()
                .and("signature_valid = :valid", "valid", signatureValid)
                .and("payment_id = :pid", "pid", paymentId)
                .and("received_at >= :from", "from", SqlFilter.ts(from))
                .and("received_at < :to", "to", SqlFilter.ts(to));
        List<EventRow> rows = jdbc.query("""
                SELECT id, payment_id, event_type, signature_valid, dedup_key, received_at FROM payment_event
                """ + f.where() + " ORDER BY id DESC LIMIT :limit OFFSET :offset", f.params(paging),
                (rs, i) -> event(rs));
        return page(rows, paging);
    }

    public record WebhookEventRow(long id, String direction, String source, String eventType, String status,
                                  String dedupKey, Instant receivedAt) {
    }

    /** The full inbound/outbound webhook log, including rejected ({@code FAILED}) callbacks. No payloads. */
    @Transactional(readOnly = true)
    public Page<WebhookEventRow> listWebhookEvents(String direction, String source, String status, Instant from,
                                                   Instant to, Paging paging) {
        SqlFilter f = new SqlFilter()
                .and("direction = :direction", "direction", direction)
                .and("source = :source", "source", source)
                .and("status = :status", "status", status)
                .and("received_at >= :from", "from", SqlFilter.ts(from))
                .and("received_at < :to", "to", SqlFilter.ts(to));
        List<WebhookEventRow> rows = jdbc.query("""
                SELECT id, direction, source, event_type, status, dedup_key, received_at FROM webhook_event
                """ + f.where() + " ORDER BY id DESC LIMIT :limit OFFSET :offset", f.params(paging),
                (rs, i) -> new WebhookEventRow(rs.getLong("id"), rs.getString("direction"), rs.getString("source"),
                        rs.getString("event_type"), rs.getString("status"), rs.getString("dedup_key"),
                        instant(rs, "received_at")));
        return page(rows, paging);
    }
}
