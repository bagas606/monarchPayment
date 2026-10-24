package id.ppob2.app.admin;

import id.ppob2.sharedkernel.error.ApiException;
import id.ppob2.sharedkernel.error.ErrorCode;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Page parameters shared by the backoffice list endpoints.
 *
 * <p>Lists answer "is there another page" by fetching {@code size + 1} rows instead of running a
 * {@code count(*)}: on tables that grow with every order, a count is the expensive half of the request
 * and the UI only ever needs to know whether to show "next".
 */
public record Paging(int page, int size) {

    public static final int DEFAULT_SIZE = 25;
    public static final int MAX_SIZE = 100;
    /** An offset this deep is a scan; ask the caller to filter instead. */
    static final int MAX_PAGE = 10_000;

    public static Paging of(Integer page, Integer size) {
        int p = page == null ? 0 : page;
        int s = size == null ? DEFAULT_SIZE : size;
        if (p < 0 || p > MAX_PAGE) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "'page' must be between 0 and " + MAX_PAGE + ".");
        }
        if (s < 1) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "'size' must be at least 1.");
        }
        // Clamped, not rejected: the response echoes the size actually used.
        return new Paging(p, Math.min(s, MAX_SIZE));
    }

    public long offset() {
        return (long) page * size;
    }

    /** Rows to ask the database for: one more than will be returned. */
    public int fetchLimit() {
        return size + 1;
    }

    /** ISO-8601 instant, e.g. {@code 2026-10-01T00:00:00Z}. */
    public static Instant instant(String name, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "'" + name + "' must be an ISO-8601 instant such as 2026-10-01T00:00:00Z.");
        }
    }

    public static <E extends Enum<E>> E enumValue(String name, String value, Class<E> type) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim());
        } catch (IllegalArgumentException e) {
            StringBuilder allowed = new StringBuilder();
            for (E constant : type.getEnumConstants()) {
                if (allowed.length() > 0) {
                    allowed.append(", ");
                }
                allowed.append(constant.name());
            }
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "'" + name + "' must be one of: " + allowed + ".");
        }
    }
}
