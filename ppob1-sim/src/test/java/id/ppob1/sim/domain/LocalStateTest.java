package id.ppob1.sim.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link LocalState#fromPpob2State} is the only place PPOB2's vocabulary becomes PPOB1's, so it is
 * the only place a new upstream state can silently become a wrong local one.
 *
 * <p>The list of PPOB2 states below is a hardcoded copy of PPOB2's {@code OrderState} as of
 * 2026-10-10, and deliberately so: PPOB1 has no compile-time dependency on PPOB2 (see
 * {@code build.gradle.kts}), which is the point of the simulator. The cost is that this list can go
 * stale — but a stale list is a visible, fixable problem, whereas importing the enum would make the
 * whole exercise circular. {@link #anUnrecognisedUpstreamStateBecomesUnknownNotAGuess} is the real
 * safety net for a state added after this was written.
 */
class LocalStateTest {

    /** PPOB2's OrderState, copied 2026-10-10. */
    private static final List<String> PPOB2_STATES = List.of(
            "CREATED", "PAYMENT_PENDING", "PAID", "DECOMPOSITION_SELECTED", "FULFILLING",
            "SUCCESS", "PARTIAL_FAILED", "FAILED", "REFUND_PENDING", "REFUNDED", "EXPIRED", "CANCELLED");

    @Test
    void everyKnownPpob2StateMapsToSomethingOtherThanUnknown() {
        for (String state : PPOB2_STATES) {
            assertThat(LocalState.fromPpob2State(state))
                    .as("PPOB2 state %s must have a deliberate mapping", state)
                    .isNotEqualTo(LocalState.UNKNOWN);
        }
    }

    @Test
    void theThreeInProgressUpstreamStatesCollapseToOne() {
        // PPOB2's internal progress is not actionable by a reseller: there is nothing different
        // PPOB1 would do on DECOMPOSITION_SELECTED versus FULFILLING.
        assertThat(LocalState.fromPpob2State("PAID")).isEqualTo(LocalState.AWAITING_RESULT);
        assertThat(LocalState.fromPpob2State("DECOMPOSITION_SELECTED")).isEqualTo(LocalState.AWAITING_RESULT);
        assertThat(LocalState.fromPpob2State("FULFILLING")).isEqualTo(LocalState.AWAITING_RESULT);
    }

    @Test
    void partialFailureIsNotFoldedIntoFailure() {
        // The commercial consequence differs: something was delivered, so this needs a
        // partial-value decision rather than a refund.
        assertThat(LocalState.fromPpob2State("PARTIAL_FAILED")).isEqualTo(LocalState.PARTIALLY_FAILED);
        assertThat(LocalState.fromPpob2State("FAILED")).isEqualTo(LocalState.FAILED);
    }

    @Test
    void aPendingRefundIsAReceivableAndNotJustAFailure() {
        // PPOB2 executes refunds out-of-band (a human moving money), so REFUND_PENDING can persist
        // for a long time. PPOB1 has to be able to list what it is owed.
        assertThat(LocalState.fromPpob2State("REFUND_PENDING")).isEqualTo(LocalState.REFUND_EXPECTED);
        assertThat(LocalState.REFUND_EXPECTED.isTerminal())
                .as("a refund PPOB2 still owes is not a settled transaction")
                .isFalse();
        assertThat(LocalState.fromPpob2State("REFUNDED")).isEqualTo(LocalState.REFUNDED);
        assertThat(LocalState.REFUNDED.isTerminal()).isTrue();
    }

    @Test
    void anUnrecognisedUpstreamStateBecomesUnknownNotAGuess() {
        // The alternatives are both worse: throwing makes an inquiry fail and leaves PPOB1's
        // belief stale with no record of why, and defaulting to AWAITING_RESULT makes PPOB1 wait
        // forever for a transition that already happened.
        assertThat(LocalState.fromPpob2State("SOME_FUTURE_STATE")).isEqualTo(LocalState.UNKNOWN);
        assertThat(LocalState.fromPpob2State(null)).isEqualTo(LocalState.UNKNOWN);
    }

    @Test
    void submittingAndUnknownAreNotTerminalSoReconKeepsLookingAtThem() {
        assertThat(LocalState.SUBMITTING.isTerminal()).isFalse();
        assertThat(LocalState.UNKNOWN.isTerminal()).isFalse();
    }

    @Test
    void rejectedIsTerminalBecauseNoUpstreamOrderEverExisted() {
        assertThat(LocalState.REJECTED.isTerminal()).isTrue();
    }
}
