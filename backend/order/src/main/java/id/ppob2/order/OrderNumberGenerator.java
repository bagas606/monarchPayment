package id.ppob2.order;

import id.ppob2.order.repository.ParentOrderRepository;
import java.time.LocalDate;
import java.time.ZoneOffset;
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

    private final ParentOrderRepository repository;

    public OrderNumberGenerator(ParentOrderRepository repository) {
        this.repository = repository;
    }

    public String next() {
        String datePart = LocalDate.now(ZoneOffset.UTC).format(DATE_FORMAT);
        long sequence = repository.nextOrderNoSequence();
        return "ORD-" + datePart + "-" + String.format("%06d", sequence);
    }
}
