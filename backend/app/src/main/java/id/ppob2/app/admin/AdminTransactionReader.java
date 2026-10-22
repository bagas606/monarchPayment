package id.ppob2.app.admin;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read model for the Section 41.2 transaction views.
 *
 * <p><b>Plain SQL with explicit column lists, not the JPA entities</b>, for a reason that is a
 * property of the design rather than a convenience: the entities these views span
 * ({@code ParentOrder}, {@code ChildOrder}, {@code ProviderTransaction}, {@code Payment}) carry
 * columns that must never reach an admin screen - {@code payment.qr_payload},
 * {@code provider_transaction.request_payload / response_payload}, {@code parent_order.callback_url}'s
 * query string. A SELECT that never names those columns cannot leak them, whatever a later edit adds
 * to a DTO. It is also one statement per view, instead of the N+1 that walking lazy associations
 * would produce for a drill-down.
 *
 * <p>The timeline is <b>derived</b>, not recorded - see {@link #timeline}.
 */
@Component
public class AdminTransactionReader {

    private static final String PARENT_SELECT = """
            SELECT po.id, po.order_no, po.state, po.parent_amount, po.order_source, po.created_at, po.expires_at,
                   po.updated_at, po.pattern_id, po.customer_reference, po.callback_url, po.metadata,
                   po.metadata ->> 'reseller_txn_id' AS reseller_txn_id,
                   pt.code AS partner_code, pr.code AS product_code, pr.name AS product_name
            FROM parent_order po
            LEFT JOIN partner pt ON pt.id = po.partner_id
            LEFT JOIN product pr ON pr.id = po.product_id
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final JsonRedactor redactor;

    public AdminTransactionReader(NamedParameterJdbcTemplate jdbc, JsonRedactor redactor) {
        this.jdbc = jdbc;
        this.redactor = redactor;
    }

    public record ParentRow(long id, String orderNo, String state, String parentAmount, String partnerCode,
                            String productCode, String productName, String orderSource, String customerReference,
                            String resellerTxnId, Instant createdAt, Instant expiresAt) {
    }

    public record Page<T>(int page, int size, boolean hasMore, List<T> items) {
    }

    @Transactional(readOnly = true)
    public Page<ParentRow> listParentOrders(String state, String partnerCode, String productCode, String orderNo,
                                            String customerReference, Instant from, Instant to, Paging paging) {
        SqlFilter f = new SqlFilter()
                .and("po.state = :state", "state", state)
                .and("pt.code = :partner", "partner", partnerCode)
                .and("pr.code = :product", "product", productCode)
                .and("po.order_no = :orderNo", "orderNo", orderNo)
                .and("po.customer_reference = :custRef", "custRef", customerReference)
                .and("po.created_at >= :from", "from", SqlFilter.ts(from))
                .and("po.created_at < :to", "to", SqlFilter.ts(to));
        List<ParentRow> rows = jdbc.query(PARENT_SELECT + f.where() + " ORDER BY po.id DESC LIMIT :limit OFFSET :offset",
                f.params(paging), (rs, i) -> new ParentRow(rs.getLong("id"), rs.getString("order_no"),
                        rs.getString("state"), rs.getBigDecimal("parent_amount").toPlainString(),
                        rs.getString("partner_code"), rs.getString("product_code"), rs.getString("product_name"),
                        rs.getString("order_source"), rs.getString("customer_reference"), rs.getString("reseller_txn_id"),
                        instant(rs, "created_at"), instant(rs, "expires_at")));
        return page(rows, paging);
    }

    // ---- child orders ---------------------------------------------------------------------------

    public record ChildRow(long id, long parentOrderId, String parentOrderNo, int sequenceNo, String state,
                           int quantity, int attemptCount, String providerCode, String providerSkuCode,
                           String faceValue, String providerTransactionStatus, Integer latencyMs,
                           String providerReference, Instant updatedAt) {
    }

    private static final String CHILD_SELECT = """
            SELECT c.id, c.parent_order_id, po.order_no, c.sequence_no, c.state, c.quantity, c.attempt_count,
                   pv.code AS provider_code, s.provider_sku_code, s.face_value,
                   t.status AS tx_status, t.latency_ms, t.provider_reference, c.updated_at
            FROM child_order c
            JOIN parent_order po ON po.id = c.parent_order_id
            LEFT JOIN provider_sku s ON s.id = c.provider_sku_id
            LEFT JOIN provider pv ON pv.id = s.provider_id
            LEFT JOIN provider_transaction t ON t.id = c.provider_transaction_id
            """;

    private ChildRow child(ResultSet rs) throws SQLException {
        return new ChildRow(rs.getLong("id"), rs.getLong("parent_order_id"), rs.getString("order_no"),
                rs.getInt("sequence_no"), rs.getString("state"), rs.getInt("quantity"), rs.getInt("attempt_count"),
                rs.getString("provider_code"), rs.getString("provider_sku_code"),
                rs.getBigDecimal("face_value") == null ? null : rs.getBigDecimal("face_value").toPlainString(),
                rs.getString("tx_status"), (Integer) rs.getObject("latency_ms"), rs.getString("provider_reference"),
                instant(rs, "updated_at"));
    }

    @Transactional(readOnly = true)
    public Page<ChildRow> listChildOrders(String state, Long parentOrderId, String providerCode, Paging paging) {
        SqlFilter f = new SqlFilter()
                .and("c.state = :state", "state", state)
                .and("c.parent_order_id = :parent", "parent", parentOrderId)
                .and("pv.code = :provider", "provider", providerCode);
        List<ChildRow> rows = jdbc.query(CHILD_SELECT + f.where() + " ORDER BY c.id DESC LIMIT :limit OFFSET :offset",
                f.params(paging), (rs, i) -> child(rs));
        return page(rows, paging);
    }

    // ---- detail ---------------------------------------------------------------------------------

    public record PaymentSummary(long id, String pgReference, String method, String amount, String status,
                                 boolean hasQr, Instant expiresAt, Instant paidAt, Instant refundedAt) {
    }

    public record DeliveryRow(long id, String eventType, String status, int attemptCount, Instant nextAttemptAt,
                              Instant lastAttemptAt, String lastFailureReason) {
    }

    public record TimelineEntry(Instant at, String event, String source, String detail) {
    }

    public record PatternSummary(long id, long generationId, String parentAmount, int componentCount,
                                 int totalQuantity, String structuralStatus, JsonNode components) {
    }

    public record OrderDetail(ParentRow order, String state, Instant updatedAt, PatternSummary pattern,
                              String callbackHost, JsonNode metadata, PaymentSummary payment, List<ChildRow> children, List<DeliveryRow> webhookDeliveries,
                              Timeline timeline) {
    }

    /** A timeline that says what it is. See {@link AdminTransactionReader#timeline}. */
    public record Timeline(boolean derived, String note, List<TimelineEntry> entries) {
    }

    @Transactional(readOnly = true)
    public Optional<OrderDetail> orderDetail(long id) {
        MapSqlParameterSource p = new MapSqlParameterSource("id", id);
        List<Object[]> parents = jdbc.query(PARENT_SELECT + " WHERE po.id = :id", p, (rs, i) -> new Object[]{
                new ParentRow(rs.getLong("id"), rs.getString("order_no"), rs.getString("state"),
                        rs.getBigDecimal("parent_amount").toPlainString(), rs.getString("partner_code"),
                        rs.getString("product_code"), rs.getString("product_name"), rs.getString("order_source"),
                        rs.getString("customer_reference"), rs.getString("reseller_txn_id"),
                        instant(rs, "created_at"), instant(rs, "expires_at")),
                instant(rs, "updated_at"), rs.getObject("pattern_id"), rs.getString("callback_url"),
                rs.getString("metadata")});
        if (parents.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = parents.get(0);
        ParentRow parent = (ParentRow) row[0];

        PaymentSummary payment = jdbc.query("""
                SELECT id, pg_reference, method, amount, status, (qr_payload IS NOT NULL) AS has_qr,
                       expires_at, paid_at, refunded_at
                FROM payment WHERE parent_order_id = :id
                """, p, rs -> rs.next() ? new PaymentSummary(rs.getLong("id"), rs.getString("pg_reference"),
                rs.getString("method"), rs.getBigDecimal("amount").toPlainString(), rs.getString("status"),
                rs.getBoolean("has_qr"), instant(rs, "expires_at"), instant(rs, "paid_at"),
                instant(rs, "refunded_at")) : null);

        List<ChildRow> children = jdbc.query(CHILD_SELECT + " WHERE c.parent_order_id = :id ORDER BY c.sequence_no",
                p, (rs, i) -> child(rs));

        List<DeliveryRow> deliveries = jdbc.query("""
                SELECT id, event_type, status, attempt_count, next_attempt_at, last_attempt_at, last_failure_reason
                FROM webhook_delivery WHERE parent_order_id = :id ORDER BY id
                """, p, (rs, i) -> new DeliveryRow(rs.getLong("id"), rs.getString("event_type"),
                rs.getString("status"), rs.getInt("attempt_count"), instant(rs, "next_attempt_at"),
                instant(rs, "last_attempt_at"), rs.getString("last_failure_reason")));

        PatternSummary pattern = row[2] == null ? null : jdbc.query("""
                SELECT id, generation_id, parent_amount, component_count, total_quantity, structural_status,
                       components::text AS components
                FROM decomposition_pattern WHERE id = :pid
                """, new MapSqlParameterSource("pid", ((Number) row[2]).longValue()),
                rs -> rs.next() ? new PatternSummary(rs.getLong("id"), rs.getLong("generation_id"),
                        rs.getBigDecimal("parent_amount").toPlainString(), rs.getInt("component_count"),
                        rs.getInt("total_quantity"), rs.getString("structural_status"),
                        redactor.redact(rs.getString("components"))) : null);

        return Optional.of(new OrderDetail(parent, parent.state(), (Instant) row[1], pattern,
                originOnly((String) row[3]), redactor.redact((String) row[4]), payment, children, deliveries,
                timeline(id, parent, (Instant) row[1], payment, children, deliveries)));
    }

    /**
     * Builds the order's timeline from timestamps that were actually recorded. It is labelled
     * {@code derived} because that is the truth: the schema has no state-history table, so automatic
     * transitions (PAID to DECOMPOSITION_SELECTED to FULFILLING, FULFILLING to SUCCESS) are not
     * individually timestamped anywhere. What can be shown honestly is the order's creation, each payment
     * callback as received, payment paid/refund times, each webhook delivery attempt, every admin action
     * recorded in {@code audit_log} against the order or one of its children, and the order's last
     * update. Pretending to a complete state sequence would invite an operator to trust a gap as fact.
     */
    private Timeline timeline(long orderId, ParentRow parent, Instant updatedAt, PaymentSummary payment,
                              List<ChildRow> children, List<DeliveryRow> deliveries) {
        List<TimelineEntry> entries = new ArrayList<>();
        entries.add(new TimelineEntry(parent.createdAt(), "ORDER_CREATED", "parent_order", "state CREATED"));
        if (payment != null) {
            jdbc.query("""
                    SELECT event_type, signature_valid, received_at FROM payment_event
                    WHERE payment_id = :pid ORDER BY received_at
                    """, new MapSqlParameterSource("pid", payment.id()), rs -> {
                entries.add(new TimelineEntry(instant(rs, "received_at"), "PAYMENT_" + rs.getString("event_type"),
                        "payment_event", rs.getBoolean("signature_valid") ? "signature valid" : "SIGNATURE INVALID"));
            });
            if (payment.paidAt() != null) {
                entries.add(new TimelineEntry(payment.paidAt(), "PAYMENT_PAID", "payment", null));
            }
            if (payment.refundedAt() != null) {
                entries.add(new TimelineEntry(payment.refundedAt(), "PAYMENT_REFUNDED", "payment", null));
            }
        }
        for (DeliveryRow d : deliveries) {
            if (d.lastAttemptAt() != null) {
                entries.add(new TimelineEntry(d.lastAttemptAt(), "WEBHOOK_" + d.status(), "webhook_delivery",
                        "attempt " + d.attemptCount()));
            }
        }
        List<Long> childIds = children.stream().map(ChildRow::id).toList();
        MapSqlParameterSource ap = new MapSqlParameterSource("oid", orderId)
                .addValue("cids", childIds.isEmpty() ? List.of(-1L) : childIds);
        jdbc.query("""
                SELECT a.action, a.occurred_at, a.target_type, u.username
                FROM audit_log a LEFT JOIN admin_user u ON a.actor_type = 'ADMIN_USER' AND u.id = a.actor_id
                WHERE (a.target_type = 'PARENT_ORDER' AND a.target_id = :oid)
                   OR (a.target_type = 'CHILD_ORDER' AND a.target_id IN (:cids))
                ORDER BY a.occurred_at
                """, ap, rs -> {
            String who = rs.getString("username");
            entries.add(new TimelineEntry(instant(rs, "occurred_at"), rs.getString("action"), "audit_log",
                    who == null ? null : "by " + who));
        });
        entries.add(new TimelineEntry(updatedAt, "LAST_UPDATED", "parent_order", "current state " + parent.state()));
        entries.removeIf(e -> e.at() == null);
        // ORDER_CREATED is pinned first: payment.paid_at is stored to the second, so on a fast payment
        // it can sort a few hundred milliseconds *before* the order it paid for. Every other entry is
        // placed by its recorded time and nothing is adjusted.
        TimelineEntry created = entries.get(0);
        List<TimelineEntry> rest = new ArrayList<>(entries.subList(1, entries.size()));
        rest.sort(Comparator.comparing(TimelineEntry::at));
        entries.clear();
        entries.add(created);
        entries.addAll(rest);
        return new Timeline(true, "Reconstructed from recorded timestamps and audit rows. The schema keeps no "
                + "state-history table, so automatic transitions are not individually timestamped.", entries);
    }

    // ---- helpers --------------------------------------------------------------------------------

    /**
     * A callback URL can carry a token in its path or query. The scheme and host say where the callback
     * goes - enough to debug a delivery - and nothing else.
     */
    static String originOnly(String url) {
        if (url == null) {
            return null;
        }
        try {
            URI uri = URI.create(url);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return "[unparseable]";
            }
            return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
        } catch (IllegalArgumentException e) {
            return "[unparseable]";
        }
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    static <T> Page<T> page(List<T> fetched, Paging paging) {
        boolean more = fetched.size() > paging.size();
        return new Page<>(paging.page(), paging.size(), more, more ? fetched.subList(0, paging.size()) : fetched);
    }
}
