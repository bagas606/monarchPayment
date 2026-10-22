package id.ppob2.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.ppob2.audit.AuditService;
import id.ppob2.sharedkernel.error.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * The remaining instance of the leak {@code handleBodyValidation} documents: Spring's own messages for
 * these three exceptions name Java types and echo the rejected input. Asserted on the real exception
 * objects, whose {@code getMessage()} is exactly what the handler used to send.
 */
class GlobalExceptionHandlerBadRequestTest {

    private final GlobalExceptionHandler handler =
            new GlobalExceptionHandler(mock(AuditService.class), new ObjectMapper());
    private final HttpServletRequest request = mock(HttpServletRequest.class);

    void typedParameter(Long ignored) {
    }

    @Test
    void typeMismatchNamesTheParameterButNotTheJavaTypeOrTheInput() throws Exception {
        MethodParameter parameter = new MethodParameter(
                GlobalExceptionHandlerBadRequestTest.class.getDeclaredMethod("typedParameter", Long.class), 0);
        MethodArgumentTypeMismatchException ex = new MethodArgumentTypeMismatchException(
                "abc'; --", Long.class, "parent_order_id", parameter, new NumberFormatException());
        assertThat(ex.getMessage()).as("the input the old handler published").contains("java.lang.Long");

        ResponseEntity<ErrorResponse> response = handler.handleTypeMismatch(ex, request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().message()).isEqualTo("Parameter 'parent_order_id' has an invalid value.");
    }

    @Test
    void missingParameterAndHeaderDoNotLeakTheParameterType() throws Exception {
        MethodParameter parameter = new MethodParameter(
                GlobalExceptionHandlerBadRequestTest.class.getDeclaredMethod("typedParameter", Long.class), 0);

        MissingServletRequestParameterException missing =
                new MissingServletRequestParameterException("reseller_txn_id", "String");
        assertThat(missing.getMessage()).contains("String");
        assertThat(handler.handleMissingParameter(missing, request).getBody().message())
                .isEqualTo("Required request parameter 'reseller_txn_id' is missing.");

        MissingRequestHeaderException header = new MissingRequestHeaderException("Idempotency-Key", parameter);
        assertThat(handler.handleMissingHeader(header, request).getBody().message())
                .isEqualTo("Required header 'Idempotency-Key' is missing.");
    }
}
