package id.ppob1.sim.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * {@link CollectionBucket#of} has to be total over {@link LocalState} — that totality is what makes
 * a snapshot a census rather than a filter, and a census is the only form in which this figure is
 * defensible, since which buckets "count" is a commercial term nobody has decided.
 *
 * <p>The switch has no {@code default} branch, so adding a {@link LocalState} breaks the build
 * rather than silently bucketing it. {@link #everyLocalStateIsClassified} is the belt to that
 * braces, in case someone adds a {@code default} to make the build pass.
 */
class CollectionBucketTest {

    @Test
    void everyLocalStateIsClassified() {
        for (LocalState state : LocalState.values()) {
            assertThat(CollectionBucket.of(state))
                    .as("LocalState %s must be assigned a bucket deliberately", state)
                    .isNotNull();
        }
    }

    @Test
    void classificationFollowsWhetherMoneyWasCollectedNotWhetherItWasDelivered() {
        // This is the choice that makes the figure comparable with PPOB2's. Its
        // sumSuccessfulAmountForDate filters on payment.status='SUCCESS' alone, independent of the
        // order's state -- so upstream, a FAILED order's payment still counts toward the
        // settlement's expected_amount. Bucketing by delivery would understate PPOB1 against the
        // very number this is meant to be compared with.
        assertThat(CollectionBucket.of(LocalState.FAILED)).isEqualTo(CollectionBucket.COLLECTED_UNDELIVERED);
        assertThat(CollectionBucket.of(LocalState.REFUND_EXPECTED)).isEqualTo(CollectionBucket.COLLECTED_UNDELIVERED);
    }

    @Test
    void aRefundedTransactionIsItsOwnBucketBecauseItLeavesPpob2sFigureToo() {
        // Payment.markRefunded moves the status from SUCCESS to REFUNDED, so a refunded payment
        // stops matching sumSuccessfulAmountForDate. Folding it in with COLLECTED_UNDELIVERED
        // would put PPOB1's total permanently above PPOB2's by the refunded amount.
        assertThat(CollectionBucket.of(LocalState.REFUNDED)).isEqualTo(CollectionBucket.REVERSED);
    }

    @Test
    void partialDeliveryIsNotFoldedIntoDelivered() {
        // The customer paid one QR for the whole amount, so the whole amount was collected -- but
        // PPOB1 has an unresolved claim for the undelivered part, and PPOB2 has no partial-refund
        // mechanism to settle it with.
        assertThat(CollectionBucket.of(LocalState.PARTIALLY_FAILED)).isEqualTo(CollectionBucket.PARTIAL);
        assertThat(CollectionBucket.of(LocalState.COMPLETED)).isEqualTo(CollectionBucket.DELIVERED);
    }

    @Test
    void whatPpob1CannotTellIsNotQuietlyCountedAsNothingCollected() {
        // The tempting and wrong fold. An orphaned submit may have a live QR upstream that a
        // customer has already paid; calling it NOT_COLLECTED would understate the day silently,
        // which is the failure mode this figure exists to prevent.
        assertThat(CollectionBucket.of(LocalState.SUBMITTING)).isEqualTo(CollectionBucket.INDETERMINATE);
        assertThat(CollectionBucket.of(LocalState.UNKNOWN)).isEqualTo(CollectionBucket.INDETERMINATE);
        assertThat(CollectionBucket.of(LocalState.CANCELLED)).isEqualTo(CollectionBucket.NOT_COLLECTED);
    }

    @Test
    void onlyTheStatesThatCanStillMoveMakeASnapshotProvisional() {
        assertThat(Arrays.stream(CollectionBucket.values())
                .filter(CollectionBucket::makesSnapshotProvisional)
                .toList())
                .containsExactlyInAnyOrder(CollectionBucket.IN_FLIGHT, CollectionBucket.INDETERMINATE);
    }
}
