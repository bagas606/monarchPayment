package id.ppob2.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.ppob2.app.security.HmacAuthenticationFilter;
import id.ppob2.catalog.domain.Product;
import id.ppob2.catalog.repository.ProductRepository;
import id.ppob2.order.CreateOrderCommand;
import id.ppob2.order.CreateOrderResult;
import id.ppob2.order.OrderApplicationService;
import id.ppob2.order.domain.OrderState;
import id.ppob2.partner.domain.Partner;
import id.ppob2.partner.repository.PartnerRepository;
import id.ppob2.sharedkernel.channel.ChannelContext;
import id.ppob2.sharedkernel.channel.ChannelContextHolder;
import id.ppob2.sharedkernel.channel.ChannelType;
import id.ppob2.sharedkernel.error.ApiException;
import id.ppob2.sharedkernel.error.ErrorCode;
import id.ppob2.sharedkernel.money.Money;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import id.ppob2.audit.AuditService;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** HmacAuthenticationFilter is excluded for the same reason as in SupportedAmountControllerTest.
 * Since it's excluded, ChannelContextHolder — which that filter normally populates per request
 * — is set manually here instead. */
@WebMvcTest(
        controllers = CreateOrderController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = HmacAuthenticationFilter.class)
)
@AutoConfigureMockMvc(addFilters = false)
class CreateOrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    /** GlobalExceptionHandler is component-scanned into every one of these slices and now takes
     * an AuditService (it records permission-denied attempts, PRD TC-ADM-005). Mocked rather
     * than excluded: these slices assert on the error envelope that handler produces. */
    @MockBean
    private AuditService auditService;

    @MockBean
    private ProductRepository productRepository;

    @MockBean
    private PartnerRepository partnerRepository;

    @MockBean
    private OrderApplicationService orderApplicationService;

    @BeforeEach
    void setChannelContext() {
        ChannelContextHolder.set(new ChannelContext(ChannelType.RESELLER_API, "PPOB1", "ppob1-client", null, "ppob1-client", null));
    }

    @AfterEach
    void clearChannelContext() {
        ChannelContextHolder.clear();
    }

    @Test
    void createsOrderAndReturnsQrPayload() throws Exception {
        Product product = new Product("MOBILE_LEGENDS", "Mobile Legends", "GAME_TOPUP", "ACTIVE");
        Partner partner = new Partner("PPOB1", "PPOB1", 1L, "ACTIVE", null);
        given(productRepository.findByCode("MOBILE_LEGENDS")).willReturn(Optional.of(product));
        given(partnerRepository.findByCode("PPOB1")).willReturn(Optional.of(partner));
        given(orderApplicationService.createOrder(ArgumentMatchers.any())).willReturn(new CreateOrderResult(
                "ORD-20260912-000123",
                OrderState.PAYMENT_PENDING,
                Money.of(100_000L),
                "00020101021226...6304ABCD",
                Instant.parse("2026-09-12T03:15:00Z"),
                Instant.parse("2026-09-12T03:00:00Z")));

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "idem-1")
                        .content("""
                                {"product_code":"MOBILE_LEGENDS","parent_amount":100000,"customer_reference":"GAMEID-1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.order_id").value("ORD-20260912-000123"))
                .andExpect(jsonPath("$.state").value("PAYMENT_PENDING"))
                .andExpect(jsonPath("$.parent_amount").value(100000))
                .andExpect(jsonPath("$.payment.qr_payload").value("00020101021226...6304ABCD"));
    }

    /**
     * The gap this slice closes. Section 23.4's {@code metadata} was bound by the request record
     * and then passed nowhere — so the assertion that matters is not that the request succeeds (it
     * always did) but that the value reaches the command, where {@code ParentOrderCreationService}
     * can persist it.
     */
    @Test
    void metadataReachesTheCommandAsJson() throws Exception {
        stubCreate();

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "idem-md")
                        .content("""
                                {"product_code":"MOBILE_LEGENDS","parent_amount":100000,
                                 "metadata":{"reseller_txn_id":"PPOB1-TX-98213"}}
                                """))
                .andExpect(status().isCreated());

        assertThat(capturedCommand().metadata()).isEqualTo("{\"reseller_txn_id\":\"PPOB1-TX-98213\"}");
    }

    /**
     * {@code writeValueAsString(null)} returns the three characters {@code null}, which {@code
     * jsonb} stores as a JSON null — a non-NULL column value that fails {@code metadata IS NULL},
     * matches nothing in the {@code ->>} lookup, and reads as the word "null" in the table. Every
     * order placed without metadata would have carried it, so the null check is asserted rather
     * than trusted.
     */
    @Test
    void anOrderWithoutMetadataCarriesNullNotTheJsonTextNull() throws Exception {
        stubCreate();

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "idem-nomd")
                        .content("""
                                {"product_code":"MOBILE_LEGENDS","parent_amount":100000}
                                """))
                .andExpect(status().isCreated());

        assertThat(capturedCommand().metadata()).isNull();
    }

    /** An explicitly empty object is kept as {@code {}} — "sent nothing in it" and "sent no
     * metadata at all" are different facts about the request. */
    @Test
    void anExplicitlyEmptyMetadataObjectIsKeptAsEmptyNotNull() throws Exception {
        stubCreate();

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "idem-emptymd")
                        .content("""
                                {"product_code":"MOBILE_LEGENDS","parent_amount":100000,"metadata":{}}
                                """))
                .andExpect(status().isCreated());

        assertThat(capturedCommand().metadata()).isEqualTo("{}");
    }

    @Test
    void oversizedMetadataIsRejectedAndNoOrderIsCreated() throws Exception {
        // stubCreate() is load-bearing, not boilerplate. Without it the mocked ProductRepository
        // returns Mockito's default Optional.empty(), the controller throws
        // VALIDATION_ERROR "Unknown or inactive product_code" from the line above the metadata
        // check, and this test passes on the same status, the same error_code and the same absence
        // of an orderApplicationService call -- having never reached the bound it exists to assert.
        // Hence the message assertion too: it is the only part of this that the product lookup
        // cannot fake.
        stubCreate();
        String padding = "x".repeat(CreateOrderController.MAX_METADATA_CHARS);

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "idem-bigmd")
                        .content("{\"product_code\":\"MOBILE_LEGENDS\",\"parent_amount\":100000,"
                                + "\"metadata\":{\"reseller_txn_id\":\"" + padding + "\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error_code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(containsString("2000-character limit")));

        // Rejected before the order exists, not after -- the bound is on what gets stored.
        verifyNoInteractions(orderApplicationService);
    }

    /**
     * {@code @NotNull} on {@code parentAmount} had never been enforced: the method took a plain
     * {@code @RequestBody} with no {@code @Valid}, so this request reached {@code Money.of(null)}
     * and came back {@code 500 INTERNAL_ERROR} — a status Section 23.1 tells partners to retry, for
     * a request no retry can fix. Asserted at 400 now, with the Section 23.9 code.
     */
    @Test
    void aMissingParentAmountIsRejectedAsValidationNotAsAServerError() throws Exception {
        Product product = new Product("MOBILE_LEGENDS", "Mobile Legends", "GAME_TOPUP", "ACTIVE");
        Partner partner = new Partner("PPOB1", "PPOB1", 1L, "ACTIVE", null);
        given(productRepository.findByCode("MOBILE_LEGENDS")).willReturn(Optional.of(product));
        given(partnerRepository.findByCode("PPOB1")).willReturn(Optional.of(partner));

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "idem-noamount")
                        .content("""
                                {"product_code":"MOBILE_LEGENDS","customer_reference":"GAMEID-1"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error_code").value("VALIDATION_ERROR"))
                // Named in the envelope's `details`, under the name the partner sent -- see
                // GlobalExceptionHandler.handleBodyValidation for why this is not the raw
                // exception message.
                .andExpect(jsonPath("$.details[0].field").value("parent_amount"))
                .andExpect(jsonPath("$.details[0].issue").value("must not be null"));

        verifyNoInteractions(orderApplicationService);
    }

    /** {@code @NotBlank} on {@code productCode}, the other annotation the missing {@code @Valid}
     * left decorative. Rejected by validation now, before the catalog lookup it used to reach. */
    @Test
    void aBlankProductCodeIsRejectedByValidationBeforeTheCatalogLookup() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "idem-noproduct")
                        .content("""
                                {"product_code":"  ","parent_amount":100000}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error_code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("product_code"));

        verifyNoInteractions(productRepository, orderApplicationService);
    }

    /**
     * What the shared {@code handleBadRequest} branch produced the moment {@code @Valid} made it
     * reachable: {@code MethodArgumentNotValidException.getMessage()}, which names the controller
     * class, the generic return type and the method parameters, and quotes the rejected value.
     * Asserted as an absence because that is the only way this stays fixed — the envelope looking
     * right does not prove the leak is gone, and the leak was not a theory but the first response
     * this endpoint actually gave.
     */
    @Test
    void aValidationFailureLeaksNeitherTheMethodSignatureNorTheRejectedValue() throws Exception {
        String body = mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "idem-leak")
                        .content("""
                                {"product_code":"SECRET-PARTNER-VALUE","parent_amount":null}
                                """))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .doesNotContain("CreateOrderController")
                .doesNotContain("id.ppob2.app.web")
                .doesNotContain("org.springframework")
                .doesNotContain("rejected value")
                .doesNotContain("SECRET-PARTNER-VALUE");
    }

    private void stubCreate() {
        Product product = new Product("MOBILE_LEGENDS", "Mobile Legends", "GAME_TOPUP", "ACTIVE");
        Partner partner = new Partner("PPOB1", "PPOB1", 1L, "ACTIVE", null);
        given(productRepository.findByCode("MOBILE_LEGENDS")).willReturn(Optional.of(product));
        given(partnerRepository.findByCode("PPOB1")).willReturn(Optional.of(partner));
        given(orderApplicationService.createOrder(ArgumentMatchers.any())).willReturn(new CreateOrderResult(
                "ORD-20260912-000123", OrderState.PAYMENT_PENDING, Money.of(100_000L),
                "00020101021226...6304ABCD", Instant.parse("2026-09-12T03:15:00Z"),
                Instant.parse("2026-09-12T03:00:00Z")));
    }

    private CreateOrderCommand capturedCommand() {
        ArgumentCaptor<CreateOrderCommand> captor = ArgumentCaptor.forClass(CreateOrderCommand.class);
        verify(orderApplicationService).createOrder(captor.capture());
        return captor.getValue();
    }

    @Test
    void rejectsUnsupportedAmountFromDownstreamValidation() throws Exception {
        Product product = new Product("MOBILE_LEGENDS", "Mobile Legends", "GAME_TOPUP", "ACTIVE");
        Partner partner = new Partner("PPOB1", "PPOB1", 1L, "ACTIVE", null);
        given(productRepository.findByCode("MOBILE_LEGENDS")).willReturn(Optional.of(product));
        given(partnerRepository.findByCode("PPOB1")).willReturn(Optional.of(partner));
        given(orderApplicationService.createOrder(ArgumentMatchers.any()))
                .willThrow(new ApiException(ErrorCode.UNSUPPORTED_AMOUNT, "Requested amount is not in the active supported-amount configuration."));

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "idem-2")
                        .content("""
                                {"product_code":"MOBILE_LEGENDS","parent_amount":123456}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error_code").value("UNSUPPORTED_AMOUNT"));
    }
}
