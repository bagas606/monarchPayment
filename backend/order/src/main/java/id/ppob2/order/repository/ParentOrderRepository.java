package id.ppob2.order.repository;

import id.ppob2.order.domain.OrderState;
import id.ppob2.order.domain.ParentOrder;
import java.time.Instant;
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
}
