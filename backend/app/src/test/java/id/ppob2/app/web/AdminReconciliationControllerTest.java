package id.ppob2.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import id.ppob2.app.web.AdminReconciliationController.ReconciliationListResponse;
import id.ppob2.app.web.AdminReconciliationController.ReconciliationView;
import id.ppob2.audit.AuditService;
import id.ppob2.reconciliation.ReconciliationQuery;
import id.ppob2.reconciliation.ReconciliationQueryService;
import id.ppob2.reconciliation.ReconciliationService;
import id.ppob2.reconciliation.domain.Reconciliation;
import id.ppob2.reconciliation.domain.ReconciliationStatus;
import id.ppob2.reconciliation.domain.ReconciliationType;
import id.ppob2.reconciliation.repository.ReconciliationRepository;
import id.ppob2.sharedkernel.error.ApiException;
import id.ppob2.sharedkernel.error.ErrorCode;
import id.ppob2.sharedkernel.money.Money;
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Covers the read side of the Section 41.9 reconciliation workflow: the projection (including the
 * derived {@code reference_type} that a list row is unreadable without), the paging the response
 * echoes back, the inverted-date-range rejection, and the fact that a GET writes nothing to
 * {@code audit_log}.
 *
 * <p>That last one is asserted rather than assumed because it is load-bearing for a claim made
 * elsewhere: TC-ADM-013 asserts exactly one {@code audit_log} row per admin action, with absolute
 * counts, so a GET that audited would break an unrelated test case — and would do it the
 * confusing way, by making a passing suite fail only once someone browsed the queue.
 */
class AdminReconciliationControllerTest {

    private final ReconciliationRepository repository = mock(ReconciliationRepository.class);
    private final ReconciliationService service = mock(ReconciliationService.class);
    private final ReconciliationQueryService queryService = mock(ReconciliationQueryService.class);
    private final AuditService auditService = mock(AuditService.class);

    private final AdminReconciliationController controller = new AdminReconciliationController(
            repository, service, queryService, auditService, new ObjectMapper());

    /** The app's real Jackson bean is SNAKE_CASE ({@code application.yml}); serializing the
     * response through anything else would not prove what an Admin Web client actually receives. */
    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    /** {@code id} is DB-generated, so there is no constructor that sets it — and the whole point of
     * a list row is the id an operator then POSTs to {@code /investigate}, so a test that left it
     * null would assert the projection while skipping its only actionable field. */
    private static Reconciliation withId(long id, Reconciliation reconciliation) {
        try {
            Field field = Reconciliation.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(reconciliation, id);
            return reconciliation;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Reconciliation tcBe018Row() {
        // The shape of the real TC-BE-018 record: 10000 collected, nothing delivered.
        return withId(7L, new Reconciliation(ReconciliationType.ORDER_VS_FULFILLMENT,
                LocalDate.of(2026, 10, 10), 31L, Money.of(10000L), Money.ZERO, Money.of(-10000L)));
    }

    private void givenPage(Page<Reconciliation> page) {
        given(queryService.search(any())).willReturn(page);
    }

    @Test
    void aListRowCarriesTheDerivedReferenceTypeSoTheReferenceIdIsInterpretable() {
        givenPage(new PageImpl<>(List.of(tcBe018Row()), PageRequest.of(0, 50), 1));

        ReconciliationListResponse body = controller
                .list(null, null, null, null, null, null, null).getBody();

        assertThat(body).isNotNull();
        ReconciliationView row = body.items().get(0);
        assertThat(row.id()).isEqualTo(7L);
        assertThat(row.reconType()).isEqualTo("ORDER_VS_FULFILLMENT");
        // Without this, reference_id=31 is an unqualified integer: the caller cannot tell whether
        // to drill into parent_order 31 or payment 31.
        assertThat(row.referenceType()).isEqualTo("PARENT_ORDER");
        assertThat(row.referenceId()).isEqualTo(31L);
        assertThat(row.discrepancy()).isEqualTo(Money.of(-10000L));
        assertThat(row.status()).isEqualTo("OPEN");
        // An unresolved row has neither — a non-null resolvedAt on an OPEN row would be a bug in
        // the projection, not just a cosmetic one, since it is the audit field.
        assertThat(row.resolvedBy()).isNull();
        assertThat(row.resolvedAt()).isNull();
    }

    @Test
    void aResolvedRowSurfacesTheResolverIdentitySection382Requires() {
        Reconciliation resolved = tcBe018Row();
        resolved.investigate();
        resolved.resolve(99L, Instant.parse("2026-10-10T04:05:06Z"));
        givenPage(new PageImpl<>(List.of(resolved), PageRequest.of(0, 50), 1));

        ReconciliationView row = controller.list(null, null, null, null, null, null, null)
                .getBody().items().get(0);

        assertThat(row.status()).isEqualTo("RESOLVED");
        assertThat(row.resolvedBy()).isEqualTo(99L);
        assertThat(row.resolvedAt()).isEqualTo("2026-10-10T04:05:06Z");
    }

    @Test
    void theResponseSerializesToSnakeCaseAgainstTheAppsRealJacksonConfig() throws Exception {
        givenPage(new PageImpl<>(List.of(tcBe018Row()), PageRequest.of(0, 50), 1));

        String json = objectMapper.writeValueAsString(
                controller.list(null, null, null, null, null, null, null).getBody());

        assertThat(json).contains("\"recon_type\":\"ORDER_VS_FULFILLMENT\"");
        assertThat(json).contains("\"reference_type\":\"PARENT_ORDER\"");
        assertThat(json).contains("\"total_elements\":1");
        // Money carries @JsonValue, so an amount must be a bare number, not a nested object.
        assertThat(json).contains("\"discrepancy\":-10000");
    }

    @Test
    void filtersAreHandedToTheQueryServiceUnchanged() {
        givenPage(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));

        controller.list(List.of(ReconciliationType.PAYMENT_VS_PG),
                List.of(ReconciliationStatus.OPEN, ReconciliationStatus.INVESTIGATING),
                42L, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 10), 2, 25);

        ArgumentCaptor<ReconciliationQuery> captor = ArgumentCaptor.forClass(ReconciliationQuery.class);
        org.mockito.Mockito.verify(queryService).search(captor.capture());
        ReconciliationQuery query = captor.getValue();

        assertThat(query.types()).containsExactly(ReconciliationType.PAYMENT_VS_PG);
        assertThat(query.statuses())
                .containsExactly(ReconciliationStatus.OPEN, ReconciliationStatus.INVESTIGATING);
        assertThat(query.referenceId()).isEqualTo(42L);
        assertThat(query.fromDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(query.toDate()).isEqualTo(LocalDate.of(2026, 10, 10));
        assertThat(query.page()).isEqualTo(2);
        assertThat(query.size()).isEqualTo(25);
    }

    @Test
    void theResponseEchoesTheClampedSizeNotTheRequestedOne() {
        givenPage(new PageImpl<>(List.of(), PageRequest.of(0, ReconciliationQuery.MAX_PAGE_SIZE), 0));

        ReconciliationListResponse body = controller
                .list(null, null, null, null, null, 0, 10_000).getBody();

        // Echoing 10000 back would tell the caller it had received the whole queue when it had
        // received at most MAX_PAGE_SIZE rows -- a truncated page that reads as complete.
        assertThat(body.size()).isEqualTo(ReconciliationQuery.MAX_PAGE_SIZE);
    }

    @Test
    void anInvertedDateRangeIsRejectedRatherThanReturningAnEmptyQueue() {
        assertThatThrownBy(() -> controller.list(null, null, null,
                LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 1), null, null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);

        // And it must be rejected BEFORE the query runs: an empty page on a discrepancy queue is
        // indistinguishable from "nothing is wrong".
        verifyNoInteractions(queryService);
    }

    @Test
    void aMissingRecordIs404NotAnEmptyBody() {
        given(queryService.findById(404L)).willReturn(Optional.empty());

        ResponseEntity<ReconciliationView> response = controller.detail(404L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void detailReturnsTheSameProjectionAsAListRow() {
        given(queryService.findById(7L)).willReturn(Optional.of(tcBe018Row()));

        ReconciliationView row = controller.detail(7L).getBody();

        assertThat(row).isNotNull();
        assertThat(row.referenceType()).isEqualTo("PARENT_ORDER");
        assertThat(row.discrepancy()).isEqualTo(Money.of(-10000L));
    }

    @Test
    void neitherReadEndpointTouchesTheAuditLog() {
        givenPage(new PageImpl<>(List.of(tcBe018Row()), PageRequest.of(0, 50), 1));
        given(queryService.findById(7L)).willReturn(Optional.of(tcBe018Row()));

        controller.list(null, null, null, null, null, null, null);
        controller.detail(7L);

        // Section 43's "Recorded" requirement is about admin mutations; TC-ADM-013's absolute
        // audit-row counts depend on reads staying out of audit_log.
        verifyNoInteractions(auditService);
    }
}
