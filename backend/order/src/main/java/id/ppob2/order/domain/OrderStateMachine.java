package id.ppob2.order.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Guards every transition against the table in PRD Section 33.2. Any transition not listed
 * there — most notably anything out of a terminal state — is explicitly rejected, per Section
 * 33.2's closing note that {@code SUCCESS} (and the other terminal states) are immutable once
 * reached.
 */
public final class OrderStateMachine {

    private static final Map<OrderState, Set<OrderState>> ALLOWED = new EnumMap<>(OrderState.class);

    static {
        ALLOWED.put(OrderState.CREATED, EnumSet.of(OrderState.PAYMENT_PENDING, OrderState.CANCELLED, OrderState.EXPIRED));
        ALLOWED.put(OrderState.PAYMENT_PENDING, EnumSet.of(OrderState.PAID, OrderState.CANCELLED, OrderState.EXPIRED));
        ALLOWED.put(OrderState.PAID, EnumSet.of(OrderState.DECOMPOSITION_SELECTED, OrderState.REFUND_PENDING));
        ALLOWED.put(OrderState.DECOMPOSITION_SELECTED, EnumSet.of(OrderState.FULFILLING));
        // NOTE: this table only enforces which *states* may follow one another. Section 34.1's
        // real invariant for FULFILLING/PARTIAL_FAILED -> SUCCESS is child-count-shaped ("parent
        // SUCCESS requires ALL child orders SUCCESS"), which this class cannot see or enforce —
        // the fulfillment slice's caller MUST verify every child order succeeded before invoking
        // this transition, not just that the state-shape happens to be allowed here.
        ALLOWED.put(OrderState.FULFILLING, EnumSet.of(OrderState.SUCCESS, OrderState.PARTIAL_FAILED, OrderState.FAILED));
        ALLOWED.put(OrderState.PARTIAL_FAILED, EnumSet.of(OrderState.SUCCESS, OrderState.REFUND_PENDING));
        ALLOWED.put(OrderState.FAILED, EnumSet.of(OrderState.REFUND_PENDING));
        ALLOWED.put(OrderState.REFUND_PENDING, EnumSet.of(OrderState.REFUNDED));
        ALLOWED.put(OrderState.REFUNDED, EnumSet.noneOf(OrderState.class));
        ALLOWED.put(OrderState.SUCCESS, EnumSet.noneOf(OrderState.class));
        ALLOWED.put(OrderState.EXPIRED, EnumSet.noneOf(OrderState.class));
        ALLOWED.put(OrderState.CANCELLED, EnumSet.noneOf(OrderState.class));
    }

    private OrderStateMachine() {
    }

    public static boolean canTransition(OrderState from, OrderState to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    public static void requireTransition(OrderState from, OrderState to) {
        if (!canTransition(from, to)) {
            throw new IllegalStateException("Illegal order state transition: " + from + " -> " + to);
        }
    }
}
