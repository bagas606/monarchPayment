package id.ppob2.payment.repository;

import id.ppob2.payment.domain.Payment;
import java.math.BigDecimal;
import java.time.LocalDate;
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
     * id.ppob2.sharedkernel.money.Money}. "Settlement window" is simplified to a single calendar
     * day matching {@code paid_at}'s date — Section 37.1 flags the real T+N schedule and cutoff
     * rules as "must be verified against latest Ayolinx contract," so this is a placeholder
     * window, not a modeled one.
     */
    @Query(value = "SELECT COALESCE(SUM(amount), 0) FROM payment WHERE status = 'SUCCESS' AND paid_at::date = :date",
            nativeQuery = true)
    BigDecimal sumSuccessfulAmountForDate(@Param("date") LocalDate date);
}
