package id.ppob2.app.web;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.ppob2.app.security.HmacAuthenticationFilter;
import id.ppob2.catalog.domain.Product;
import id.ppob2.catalog.repository.ProductRepository;
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
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
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
