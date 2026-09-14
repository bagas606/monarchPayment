package id.ppob2.order.repository;

import id.ppob2.order.domain.OrderState;
import id.ppob2.order.domain.ParentOrder;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ParentOrderRepository extends JpaRepository<ParentOrder, Long> {

    Optional<ParentOrder> findByClientIdAndIdempotencyKey(String clientId, String idempotencyKey);

    Optional<ParentOrder> findByOrderNo(String orderNo);

    @Query(value = "select nextval('parent_order_no_seq')", nativeQuery = true)
    long nextOrderNoSequence();

    /**
     * PRD Section 48.3's QR expiry sweep. Bounded by {@code limit} — an unbounded query here
     * would load every expired order into memory on the first run after any period of
     * accumulation (or the first deploy against existing data). The sweep is idempotent and runs
     * repeatedly (see {@code QrExpirySweepJob}), so draining across ticks rather than in one pass
     * is the intended behavior, not a limitation to work around.
     */
    List<ParentOrder> findByStateAndExpiresAtBefore(OrderState state, Instant instant, Limit limit);

    /**
     * PRD Section 37.3: per-partner input to {@code SettlementAllocationService}'s pro-rata
     * apportionment. {@code payment.parent_order_id} joins to {@code parent_order.partner_id} --
     * {@code payment} itself carries no partner reference (Section 22.17 has none; partner
     * attribution lives on the order, not the payment). This lives here rather than in `payment`
     * because Section 20.2 grants {@code order -> payment} (not the reverse), so `order` is the
     * side of that edge allowed to join across both tables; a native query joining two tables by
     * name (not a JPA relationship) doesn't add a compile-time module dependency either way, but
     * keeping the query on the permitted side of the edge matches this codebase's existing
     * boundary discipline rather than merely happening to compile.
     *
     * <p>Excludes {@code partner_id IS NULL} orders (Section 22.15: nullable for future
     * consumer channels, e.g. {@code PPOB2_WEB}) -- there is no partner to attribute those to.
     * Every order today is {@code RESELLER_API} with a non-null {@code partner_id} (BR-ORD-003), so
     * this exclusion is a no-op in the current MVP, not an active filter.
     */
    @Query(value = "SELECT po.partner_id AS partnerId, COALESCE(SUM(p.amount), 0) AS amount "
            + "FROM parent_order po JOIN payment p ON p.parent_order_id = po.id "
            + "WHERE p.status = 'SUCCESS' AND p.paid_at::date = :date AND po.partner_id IS NOT NULL "
            + "GROUP BY po.partner_id",
            nativeQuery = true)
    List<PartnerAmountProjection> sumSuccessfulPaymentAmountByPartnerForDate(@Param("date") LocalDate date);

    interface PartnerAmountProjection {
        Long getPartnerId();

        BigDecimal getAmount();
    }
}
