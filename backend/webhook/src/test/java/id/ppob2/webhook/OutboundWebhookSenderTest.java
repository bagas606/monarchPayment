package id.ppob2.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The Section 23.8 body's shape. This class had no test at all, which is why the one case that
 * matters most here was previously unreachable by any means: an order carrying no
 * {@code metadata.reseller_txn_id} must send <b>no such key</b>, and every order the e2e harness
 * places through {@code ppob1-sim} carries one — so that branch could never be driven end to end.
 *
 * <p>The failure it guards against is specific. {@code payload} builds a {@code Map}, and Jackson's
 * {@code NON_NULL} inclusion does not apply to map entries at all; this class also deliberately uses
 * its own bare {@code ObjectMapper} rather than the app's configured bean, so no global setting
 * applies either. A {@code put(key, null)} would therefore serialize
 * {@code "reseller_txn_id":null} — a key asserting the reseller has no id, to a partner that may
 * well branch on the key's presence.
 */
class OutboundWebhookSenderTest {

    private final OutboundWebhookSender sender = new OutboundWebhookSender();

    @Test
    void theResellersOwnIdIsEchoedBackWhenTheOrderCarriesOne() {
        Map<String, Object> payload = sender.payload("ORD-1", "SUCCESS", "abc123", "PPOB1-TX-98213");

        assertThat(payload).containsEntry("reseller_txn_id", "PPOB1-TX-98213");
        // The five documented fields are unchanged -- this is an additive extension of Section
        // 23.8's body, not a replacement, so a partner parsing only those five is unaffected.
        assertThat(payload)
                .containsEntry("event_type", "ORDER_STATUS_CHANGED")
                .containsEntry("order_id", "ORD-1")
                .containsEntry("state", "SUCCESS")
                .containsEntry("nonce", "abc123")
                .containsKey("timestamp");
    }

    @Test
    void anOrderWithNoResellerIdSendsNoSuchKeyAtAll() {
        Map<String, Object> payload = sender.payload("ORD-1", "SUCCESS", "abc123", null);

        // containsKey, not the value: a null value and an absent key serialize differently and mean
        // different things to the receiver.
        assertThat(payload).doesNotContainKey("reseller_txn_id");
        assertThat(payload).hasSize(5);
    }

    @Test
    void aPartnerSuppliedValueIsCarriedAsDataAndNotConcatenatedIntoJson() {
        // The value is whatever a partner put in metadata.reseller_txn_id. It goes through Jackson,
        // which escapes it -- unlike the hand-built fallback body() this class keeps for the
        // serialization-failure path, which is why that one deliberately carries only PPOB2's own
        // order_no and OrderState name.
        Map<String, Object> payload =
                sender.payload("ORD-1", "SUCCESS", "abc123", "x\",\"state\":\"FAILED");

        assertThat(payload).containsEntry("reseller_txn_id", "x\",\"state\":\"FAILED");
        assertThat(payload).containsEntry("state", "SUCCESS");
    }
}
