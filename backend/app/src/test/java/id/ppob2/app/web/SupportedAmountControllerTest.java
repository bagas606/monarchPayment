package id.ppob2.app.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.ppob2.app.security.HmacAuthenticationFilter;
import id.ppob2.catalog.domain.Product;
import id.ppob2.catalog.repository.ProductRepository;
import id.ppob2.configuration.SupportedAmountQueryService;
import id.ppob2.sharedkernel.money.Money;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HmacAuthenticationFilter is excluded: it is itself a {@code Filter} bean, so the default
 * WebMvcTest component scan would otherwise try to instantiate it (and its JPA-backed
 * dependencies) even though {@code addFilters = false} keeps it out of the MockMvc chain.
 */
@WebMvcTest(
        controllers = SupportedAmountController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = HmacAuthenticationFilter.class)
)
@AutoConfigureMockMvc(addFilters = false)
class SupportedAmountControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProductRepository productRepository;

    @MockBean
    private SupportedAmountQueryService supportedAmountQueryService;

    @Test
    void returnsActiveAmountsInSnakeCaseEnvelope() throws Exception {
        Product product = new Product("MOBILE_LEGENDS", "Mobile Legends", "GAME_TOPUP", "ACTIVE");
        given(productRepository.findByCode("MOBILE_LEGENDS")).willReturn(Optional.of(product));
        given(supportedAmountQueryService.activeAmountsForCategory("GAME_TOPUP"))
                .willReturn(List.of(Money.of(10_000L), Money.of(20_000L)));

        mockMvc.perform(get("/api/v1/config/supported-amounts").param("product_code", "MOBILE_LEGENDS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.product_code").value("MOBILE_LEGENDS"))
                .andExpect(jsonPath("$.currency").value("IDR"))
                .andExpect(jsonPath("$.amounts[0]").value(10000))
                .andExpect(jsonPath("$.amounts[1]").value(20000));
    }

    @Test
    void rejectsUnknownProductWithValidationError() throws Exception {
        given(productRepository.findByCode(any())).willReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/config/supported-amounts").param("product_code", "UNKNOWN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error_code").value("VALIDATION_ERROR"))
                .andExpect(content().contentTypeCompatibleWith("application/json"));
    }
}
