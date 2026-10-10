package id.ppob2.reconciliation;

import id.ppob2.reconciliation.domain.Reconciliation;
import id.ppob2.reconciliation.repository.ReconciliationRepository;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PRD FR-REC-003's second verb, and Section 38.2's first adjective: discrepancies must be
 * "recorded <em>and exposed</em> ... via Admin Web", "<em>visible</em> and actionable". Until this
 * service existed only the second half of each was true — {@code ReconciliationService} could open
 * a record and an operator could move it through {@code INVESTIGATING → RESOLVED} by id, but
 * nothing could answer "which discrepancies are open?", so every id an operator acted on had to
 * come from a SQL prompt. A queue nobody can enumerate is not an operational control.
 *
 * <p>Split from {@link ReconciliationService} rather than added to it on purpose. That class is
 * built around one write-side invariant — {@code open} is {@code Propagation.MANDATORY} so a
 * discrepancy commits atomically with whatever detected it — and every read here is the opposite:
 * {@code readOnly}, joining no caller's transaction, carrying no invariant. Mixing them would make
 * that class's propagation contract read as incidental rather than load-bearing.
 *
 * <p>Section 41.9 names three views (payment / provider / settlement reconciliation). They are
 * served by one filtered endpoint rather than three, because they differ only in which {@code
 * recon_type} values they show — three endpoints would be three copies of this method's paging,
 * clamping and projection, kept in sync by hand.
 */
@Service
public class ReconciliationQueryService {

    private final ReconciliationRepository repository;

    public ReconciliationQueryService(ReconciliationRepository repository) {
        this.repository = repository;
    }

    /**
     * Newest-first by {@code id}, with no client-supplied sort. Two reasons: {@code id} descending
     * is a total order on an append-only queue (so paging can't repeat or skip a row the way an
     * unstable sort on a non-unique column such as {@code recon_date} can), and accepting a sort
     * property from the request would mean handing Spring Data a client-controlled string, where
     * an unknown property raises {@code PropertyReferenceException} — an unmapped {@code 500} on
     * what is a client mistake.
     */
    @Transactional(readOnly = true)
    public Page<Reconciliation> search(ReconciliationQuery query) {
        return repository.findAll(toSpecification(query),
                PageRequest.of(query.page(), query.size(), Sort.by(Sort.Direction.DESC, "id")));
    }

    @Transactional(readOnly = true)
    public Optional<Reconciliation> findById(Long reconciliationId) {
        return repository.findById(reconciliationId);
    }

    /**
     * Only the filters that have a value become predicates, so an unfiltered call emits no {@code
     * WHERE} clause — see {@link ReconciliationRepository}'s note on why the {@code :param IS NULL
     * OR ...} form is not used here.
     */
    private Specification<Reconciliation> toSpecification(ReconciliationQuery query) {
        return (root, criteriaQuery, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (query.types() != null && !query.types().isEmpty()) {
                predicates.add(root.get("reconType").in(query.types()));
            }
            if (query.statuses() != null && !query.statuses().isEmpty()) {
                predicates.add(root.get("status").in(query.statuses()));
            }
            if (query.referenceId() != null) {
                predicates.add(builder.equal(root.get("referenceId"), query.referenceId()));
            }
            // Both bounds inclusive: recon_date is a DATE, so a caller asking for a single day
            // passes the same value twice and expects that day's rows.
            if (query.fromDate() != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get("reconDate"), query.fromDate()));
            }
            if (query.toDate() != null) {
                predicates.add(builder.lessThanOrEqualTo(root.get("reconDate"), query.toDate()));
            }
            return predicates.isEmpty() ? builder.conjunction() : builder.and(predicates.toArray(new Predicate[0]));
        };
    }
}
