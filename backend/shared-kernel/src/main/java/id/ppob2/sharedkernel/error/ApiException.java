package id.ppob2.sharedkernel.error;

import java.util.List;

/** Base exception carrying a standard error code, mapped to the Section 50.1 error envelope. */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;
    private final List<ErrorDetail> details;

    public ApiException(ErrorCode errorCode, String message) {
        this(errorCode, message, List.of());
    }

    public ApiException(ErrorCode errorCode, String message, List<ErrorDetail> details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public List<ErrorDetail> details() {
        return details;
    }

    public record ErrorDetail(String field, String issue) {
    }
}
