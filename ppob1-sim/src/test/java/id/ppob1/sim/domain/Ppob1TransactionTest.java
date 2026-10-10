package id.ppob1.sim.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;

/**
 * {@link Ppob1Transaction#adoptPpob2OrderId} — the guard behind the guard.
 *
 * <p>{@code WebhookIngestService} already refuses to adopt onto a transaction that holds an order
 * id, and {@code WebhookIngestServiceTest} covers that path. This covers the entity's own refusal,
 * which exists because the whole danger of matching a callback on the reseller's own id is that the
 * id is unique in PPOB1 but not necessarily upstream: one careless adopt replaces the order PPOB1 is
 * tracking with a different one and leaves no trace of the first. The service check can be removed
 * by an edit; this one cannot be bypassed by a new caller.
 */
class Ppob1TransactionTest {

    private static Ppob1Transaction submitting() {
        return new Ppob1Transaction("PPOB1-TX-1", "MOBILE_LEGENDS", BigInteger.valueOf(20000), "GAMEID-1");
    }

    @Test
    void aTransactionWithNoUpstreamIdAdoptsOne() {
        Ppob1Transaction transaction = submitting();
        assertThat(transaction.getPpob2OrderId()).isNull();

        transaction.adoptPpob2OrderId("ORD-1");

        assertThat(transaction.getPpob2OrderId()).isEqualTo("ORD-1");
        // Adopting an id is not learning a state: the caller applies that separately so the
        // StateSource stays its decision.
        assertThat(transaction.getLocalState()).isEqualTo(LocalState.SUBMITTING);
        assertThat(transaction.getLastSource()).isNull();
    }

    @Test
    void adoptingOverAnExistingIdThrowsAndChangesNothing() {
        Ppob1Transaction transaction = submitting();
        transaction.acceptedByPpob2("ORD-FIRST", "PAYMENT_PENDING", "00020101", null);

        assertThatThrownBy(() -> transaction.adoptPpob2OrderId("ORD-SECOND"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ORD-FIRST")
                .hasMessageContaining("ORD-SECOND");

        assertThat(transaction.getPpob2OrderId()).isEqualTo("ORD-FIRST");
    }
}
