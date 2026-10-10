package id.ppob2.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import id.ppob2.pricing.repository.PatternUsageRepository;
import id.ppob2.pricing.repository.SkuUsageRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link UsageTrackingService} is the <b>write</b> half of a pair whose two halves must agree:
 * {@code RoutingService} reads these same counters to enforce {@code provider_sku.quota_daily}
 * (Section 32.1, {@code TC-BE-020}).
 *
 * <p>Both used to call {@code LocalDate.now()}, which took the JVM default zone. That happened to
 * keep them in agreement — but it made the day boundary a property of the deploying host, and
 * nothing in the code said so or stopped the two from drifting apart. The failure mode if they ever
 * did is quiet: a quota checked against a counter nobody increments is enforcement that has stopped
 * enforcing, with no error anywhere.
 *
 * <p>These tests therefore pin the clock to <b>23:30 UTC</b>, an instant whose Jakarta date is the
 * <em>next</em> day. Any regression to a host-zone or hardcoded-zone lookup changes the date these
 * assertions see, which is exactly what a test using {@code LocalDate.now()} cannot do — both sides
 * of that comparison move together.
 */
class UsageTrackingServiceTest {

    private static final Instant LATE_UTC_EVENING = Instant.parse("2026-10-10T23:30:00Z");

    private final PatternUsageRepository patternUsageRepository = mock(PatternUsageRepository.class);
    private final SkuUsageRepository skuUsageRepository = mock(SkuUsageRepository.class);

    private UsageTrackingService serviceIn(String zone) {
        return new UsageTrackingService(
                Clock.fixed(LATE_UTC_EVENING, ZoneId.of(zone)), patternUsageRepository, skuUsageRepository);
    }

    @Test
    void usageIsKeyedByTheBusinessDayNotTheHostsDay() {
        serviceIn("UTC").recordPatternSelected(7L, Map.of(1L, 2));

        LocalDate expected = LocalDate.of(2026, 10, 10);
        verify(patternUsageRepository).increment(7L, expected);
        verify(skuUsageRepository).increment(1L, expected, 2);
    }

    @Test
    void aDifferentBusinessZoneMovesTheDayTheCounterLandsOn() {
        // Same instant, Jakarta is UTC+7, so 23:30 UTC is already the 11th there. This is the whole
        // point of the setting: the day a late-evening sale is counted against is a decision, and
        // it has to be the same decision everywhere in the platform.
        serviceIn("Asia/Jakarta").recordPatternSelected(7L, Map.of(1L, 2));

        LocalDate expected = LocalDate.of(2026, 10, 11);
        verify(patternUsageRepository).increment(7L, expected);
        verify(skuUsageRepository).increment(1L, expected, 2);
    }

    @Test
    void theDayExposedToCallersIsTheSameOneTheCountersAreWrittenWith() {
        // RoutingService resolves its own LocalDate.now(businessClock) from the same bean, so this
        // asserts the contract between the two halves rather than re-asserting the line above:
        // given one clock, the write side and the read side name the same day.
        UsageTrackingService service = serviceIn("Asia/Jakarta");

        service.recordPatternSelected(7L, Map.of(1L, 1));

        assertThat(service.today()).isEqualTo(LocalDate.of(2026, 10, 11));
        verify(patternUsageRepository).increment(7L, service.today());
    }

    @Test
    void everySkuInThePatternIsCountedWithItsOwnQuantity() {
        serviceIn("UTC").recordPatternSelected(9L, Map.of(1L, 3, 2L, 1));

        LocalDate expected = LocalDate.of(2026, 10, 10);
        verify(skuUsageRepository).increment(1L, expected, 3);
        verify(skuUsageRepository).increment(2L, expected, 1);
        verify(patternUsageRepository).increment(9L, expected);
    }
}
