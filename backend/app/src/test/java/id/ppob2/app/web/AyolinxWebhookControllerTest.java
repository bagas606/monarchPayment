package id.ppob2.app.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.ppob2.app.security.HmacAuthenticationFilter;
import id.ppob2.payment.callback.PaymentCallbackOutcome;
import id.ppob2.payment.callback.PaymentCallbackService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Controller-level only: HTTP status mapping from {@link PaymentCallbackOutcome}. The actual
 * dedup/transaction/JSONB-binding behavior this slice's real bug lived in
 * ({@code PaymentEventDeduplicator}, {@code ParentOrderTransitionService.markPaid}) is verified
 * against real Postgres, not mocks — see the backend README's testing notes for why a mocked
 * repository could not have caught either bug.
 */
@WebMvcTest(
        controllers = AyolinxWebhookController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = HmacAuthenticationFilter.class)
)
@AutoConfigureMockMvc(addFilters = false)
class AyolinxWebhookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PaymentCallbackService paymentCallbackService;

    @Test
    void processedMapsTo200() throws Exception {
        given(paymentCallbackService.processCallback(any(), any())).willReturn(PaymentCallbackOutcome.PROCESSED);
        mockMvc.perform(post("/internal/webhooks/ayolinx").content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void duplicateMapsTo200() throws Exception {
        given(paymentCallbackService.processCallback(any(), any())).willReturn(PaymentCallbackOutcome.DUPLICATE_IGNORED);
        mockMvc.perform(post("/internal/webhooks/ayolinx").content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void invalidSignatureMapsTo401() throws Exception {
        given(paymentCallbackService.processCallback(any(), any())).willReturn(PaymentCallbackOutcome.SIGNATURE_INVALID);
        mockMvc.perform(post("/internal/webhooks/ayolinx").content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void malformedMapsTo400() throws Exception {
        given(paymentCallbackService.processCallback(any(), any())).willReturn(PaymentCallbackOutcome.MALFORMED);
        mockMvc.perform(post("/internal/webhooks/ayolinx").content("not json"))
                .andExpect(status().isBadRequest());
    }
}
