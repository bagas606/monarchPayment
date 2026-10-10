package id.ppob2.payment.repository;

import id.ppob2.payment.domain.Payment;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByParentOrderId(Long parentOrderId);

    Optional<Payment> findByPgReference(String pgReference);

    /**
     * Section 37.1: "computing expected_amount from the sum of payment records for the
     * settlement window." Native query — a JPQL {@code SUM(p.amount)} over {@code Money}'s
     * {@code AttributeConverter} is exactly the kind of thing that compiles and then misbehaves
     * (Hibernate has to decide how to aggregate a converted type), so this sums the underlying
     * {@code NUMERIC(18,0)} column directly and lets the caller wrap the result in {@link
     * id.ppob2.sharedkernel.money.Money}.
     *
     * <p><b>Takes an explicit half-open {@code [start, end)} instant range, and deliberately has no
     * {@code LocalDate} overload.</b> The previous signature took a date and filtered
     * {@code paid_at::date = :date}, which looks equivalent and is not: that cast runs in the
     * <em>Postgres session</em> timezone, which the PG JDBC driver sets from the JVM default — so
     * the same payment data produced a different {@code expected_amount} depending on the deploying
     * host's {@code TZ}, and therefore a different {@code MATCHED}/{@code DISCREPANCY} verdict.
     * Verified, not inferred: {@code 2026-10-10T23:30:00Z} casts to {@code 2026-10-11} under
     * {@code Asia/Jakarta} and {@code 2026-10-10} under {@code UTC}. The date overload is gone
     * rather than deprecated so no caller can reintroduce the host dependence; the window is now
     * resolved by the caller from {@code BusinessTimeConfig}'s clock.
     *
     * <p>Half-open rather than {@code BETWEEN}: a payment at exactly midnight must belong to one
     * settlement window only. "Settlement window" is still a single business day — Section 37.1
     * flags the real T+N schedule as "must be verified against latest Ayolinx contract", so this
     * remains a placeholder window; what changed is that its boundary is now a decision in code
     * rather than a property of the host.
     */
    @Query(value = "SELECT COALESCE(SUM(amount), 0) FROM payment "
            + "WHERE status = 'SUCCESS' AND paid_at >= :start AND paid_at < :end",
            nativeQuery = true)
    BigDecimal sumSuccessfulAmountPaidBetween(@Param("start") Instant start, @Param("end") Instant end);
}
