package id.ppob2.app.admin;

import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * Builds an optional-filter {@code WHERE} clause with bound parameters only.
 *
 * <p>Every fragment passed to {@link #and} is a compile-time constant written by this codebase; the
 * caller's value only ever travels as a bind parameter. That is the whole defence against injection
 * here, which is why there is no method that accepts a column name or an operator from a request.
 */
public final class SqlFilter {

    private final List<String> clauses = new ArrayList<>();
    private final MapSqlParameterSource params = new MapSqlParameterSource();

    /** Adds {@code fragment} (containing {@code :name}) only when {@code value} is non-null. */
    public SqlFilter and(String fragment, String name, Object value) {
        if (value != null) {
            clauses.add(fragment);
            params.addValue(name, value);
        }
        return this;
    }

    public String where() {
        return clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
    }

    public MapSqlParameterSource params(Paging paging) {
        return new MapSqlParameterSource(params.getValues())
                .addValue("limit", paging.fetchLimit())
                .addValue("offset", paging.offset());
    }

    public MapSqlParameterSource params() {
        return new MapSqlParameterSource(params.getValues());
    }

    /** pgjdbc binds {@code OffsetDateTime} as timestamptz; a bare {@code Instant} is not supported by every driver version. */
    public static java.time.OffsetDateTime ts(java.time.Instant instant) {
        return instant == null ? null : java.time.OffsetDateTime.ofInstant(instant, java.time.ZoneOffset.UTC);
    }

    public static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
