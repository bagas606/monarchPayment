package id.ppob2.reconciliation;

import id.ppob2.reconciliation.domain.ReconciliationStatus;
import id.ppob2.reconciliation.domain.ReconciliationType;
import java.time.LocalDate;
import java.util.List;

/**
 * A validated, already-clamped filter for {@link ReconciliationQueryService#search}. Every field
 * is optional except the paging pair, which always has a value because {@link #of} supplies one.
 *
 * <p>Clamping lives in {@link #of} rather than in the controller so that the bound can't be
 * bypassed by a second caller added later: {@code size} is the only parameter on this read surface
 * that a client can use to make the server do unbounded work, and "the HTTP layer remembers to
 * check it" is the kind of invariant that holds until there are two HTTP layers.
 *
 * @param types        {@code recon_type} values to include; empty or {@code null} means every type
 * @param statuses     {@code status} values to include; empty or {@code null} means every status
 * @param referenceId  exact {@code reference_id} match. Only meaningful together with a single
 *                     {@code type} — {@code reference_id} is polymorphic across types, see
 *                     {@link ReconciliationType#referenceType()}
 * @param fromDate     inclusive lower bound on {@code recon_date}
 * @param toDate       inclusive upper bound on {@code recon_date}
 * @param page         zero-based page index
 * @param size         page size, never above {@link #MAX_PAGE_SIZE}
 */
public record ReconciliationQuery(
        List<ReconciliationType> types,
        List<ReconciliationStatus> statuses,
        Long referenceId,
        LocalDate fromDate,
        LocalDate toDate,
        int page,
        int size
) {

    /** Chosen to be comfortably larger than a plausible discrepancy queue so that the common
     * "show me everything OPEN" call is a single request, while still bounding one response. */
    public static final int MAX_PAGE_SIZE = 200;

    public static final int DEFAULT_PAGE_SIZE = 50;

    /**
     * Normalizes raw request parameters. Out-of-range paging is clamped, not rejected: a negative
     * {@code page} or an oversized {@code size} has an unambiguous intended meaning (the first
     * page; as many as allowed), and the effective values are echoed back in the response so the
     * caller can see what it actually got rather than having to infer it.
     *
     * <p>Garbage that has no such meaning is NOT clamped here — an unparseable {@code recon_type}
     * or {@code recon_date} never reaches this method, because Spring's parameter conversion
     * rejects it first with {@code MethodArgumentTypeMismatchException} → {@code 400
     * VALIDATION_ERROR}. Nor is an inverted date range, which is a contradiction rather than an
     * out-of-range value and would otherwise return an empty page that looks like "no
     * discrepancies"; the controller rejects that with a 400.
     */
    public static ReconciliationQuery of(List<ReconciliationType> types, List<ReconciliationStatus> statuses,
                                          Long referenceId, LocalDate fromDate, LocalDate toDate,
                                          Integer page, Integer size) {
        int effectivePage = (page == null || page < 0) ? 0 : page;
        int effectiveSize = (size == null || size < 1) ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        return new ReconciliationQuery(types, statuses, referenceId, fromDate, toDate, effectivePage, effectiveSize);
    }
}
