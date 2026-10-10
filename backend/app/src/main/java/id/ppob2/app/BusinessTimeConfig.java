package id.ppob2.app;

import java.time.Clock;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The single definition of "what day is it" for this platform.
 *
 * <p><b>The bug this exists to close.</b> Before it, PPOB2 had no business-day definition at all —
 * it had the JVM's default timezone, used implicitly in two different ways:
 * <ul>
 *   <li>Seven {@code LocalDate.now()} call sites (daily usage counters, quota enforcement,
 *       {@code reconciliation.recon_date}) took the host's zone.</li>
 *   <li>Two native settlement queries grouped with {@code paid_at::date}. That cast runs in the
 *       <em>Postgres session</em> timezone, which the PG JDBC driver sets from the JVM default —
 *       <em>not</em> from {@code spring.jpa.properties.hibernate.jdbc.time_zone}, which only
 *       governs how Hibernate binds parameters, and which is the setting a reader would reasonably
 *       assume covered this.</li>
 * </ul>
 * Verified directly against Postgres rather than reasoned about: the instant
 * {@code 2026-10-10T23:30:00Z} casts to {@code 2026-10-11} under {@code Asia/Jakarta} and to
 * {@code 2026-10-10} under {@code UTC}. So the same payment data produced a different
 * {@code expected_amount} per settlement date depending on the deploying host's {@code TZ} — which
 * decides {@code MATCHED} vs {@code DISCREPANCY}, whether a {@code PAYMENT_VS_SETTLEMENT}
 * reconciliation record opens, and how {@code settlement_partner_allocation} apportions. For an
 * Indonesian platform, a host running {@code Asia/Jakarta} is not a hypothetical, and it would have
 * grouped settlements seven hours off from a UTC host with no sign that anything had changed.
 *
 * <p><b>Why a {@code java.time.Clock} and not a bespoke type.</b> {@code LocalDate.now(clock)} is
 * the idiomatic way to make "today" a dependency rather than a global, and it makes every affected
 * unit test able to pin a date with {@code Clock.fixed(...)} instead of asserting against
 * {@code LocalDate.now()} — which is a test that cannot fail for the bug it is nominally about.
 * It also needs no change to {@code shared-kernel}, which is deliberately free of framework
 * dependencies.
 *
 * <p><b>Why the default is {@code UTC} and not {@code Asia/Jakarta}.</b> Defaulting to UTC
 * reproduces the behaviour this container already had, so the fix changes the <em>boundary's
 * provenance</em> without silently moving anyone's settlement figures. {@code Asia/Jakarta} is very
 * likely the correct business value — but Section 37.1 flags the settlement schedule and cutoff
 * rules as "must be verified against latest Ayolinx contract", and picking the plausible answer
 * here would dress an unverified assumption up as a decision. Set
 * {@code PPOB2_BUSINESS_ZONE=Asia/Jakarta} once that cutoff is confirmed; this class is the one
 * place that then has to change.
 *
 * <p><b>Not to be confused with {@code ppob2.payment.ayolinx.timezone}</b>, which is unrelated: that
 * one formats timestamps in Ayolinx's API payloads (Section 26's request format), and has nothing
 * to do with which day a payment is settled on.
 */
@Configuration
public class BusinessTimeConfig {

    private static final Logger log = LoggerFactory.getLogger(BusinessTimeConfig.class);

    /**
     * @param businessZone an IANA zone id. Resolved eagerly via {@link ZoneId#of}, so a typo fails
     *     the context start rather than silently falling back to a default — a misconfigured
     *     business day is exactly the kind of fault that should refuse to boot rather than quietly
     *     mis-group a day's money.
     */
    @Bean
    public Clock businessClock(@Value("${ppob2.business-zone:UTC}") String businessZone) {
        ZoneId zone = ZoneId.of(businessZone);
        log.info("Business day boundary: {} (ppob2.business-zone). Settlement windows, daily usage "
                + "quotas and reconciliation dates are all cut in this zone.", zone);
        return Clock.system(zone);
    }
}
