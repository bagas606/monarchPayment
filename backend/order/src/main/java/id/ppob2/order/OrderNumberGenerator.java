package id.ppob2.order;

import id.ppob2.order.repository.ParentOrderRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

/** Produces `ORD-YYYYMMDD-NNNNNN`-shaped order numbers per the Section 23.4 example, backed by
 * a dedicated Postgres sequence so concurrent creations never collide. The numeric suffix is
 * zero-padded to six digits but deliberately allowed to widen past that once the global,
 * never-reset sequence exceeds 999,999 — wrapping it back to six digits via modulo would
 * eventually collide with an earlier order's number on the same date and violate
 * `parent_order_order_no_uk`. If a fixed-width field is contractually required downstream (e.g.
 * PPOB1 parses a 6-digit suffix), the fix is a per-day-reset sequence, not a modulo. */
@Component
public class OrderNumberGenerator {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final Clock businessClock;
    private final ParentOrderRepository repository;

    public OrderNumberGenerator(Clock businessClock, ParentOrderRepository repository) {
        this.businessClock = businessClock;
        this.repository = repository;
    }

    public String next() {
        // Was a hardcoded ZoneOffset.UTC -- explicit, so never host-dependent, but it would have
        // disagreed with every other business date once the zone became configurable: an order
        // booked on the Jakarta 11th would have carried a 10th in its number. Safe to move because
        // the backing sequence is global and never reset per day, so changing the date label cannot
        // collide with an earlier order (see the class note on why there is no modulo).
        String datePart = LocalDate.now(businessClock).format(DATE_FORMAT);
        long sequence = repository.nextOrderNoSequence();
        return "ORD-" + datePart + "-" + String.format("%06d", sequence);
    }
}
