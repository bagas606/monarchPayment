package id.ppob2.sharedkernel.error;

import java.time.Instant;
import java.util.List;

/** Standard error envelope shape, PRD Section 50.1. */
public record ErrorResponse(
        String errorCode,
        String message,
        List<ApiException.ErrorDetail> details,
        String correlationId,
        Instant timestamp
) {
    public static ErrorResponse of(ErrorCode code, String message, String correlationId) {
        return new ErrorResponse(code.name(), message, List.of(), correlationId, Instant.now());
    }

    public static ErrorResponse of(ApiException ex, String correlationId) {
        return new ErrorResponse(ex.errorCode().name(), ex.getMessage(), ex.details(), correlationId, Instant.now());
    }
}
