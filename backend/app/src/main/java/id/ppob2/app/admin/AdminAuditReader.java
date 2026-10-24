package id.ppob2.app.admin;

import static id.ppob2.app.admin.AdminTransactionReader.instant;
import static id.ppob2.app.admin.AdminTransactionReader.page;

import com.fasterxml.jackson.databind.JsonNode;
import id.ppob2.app.admin.AdminTransactionReader.Page;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read model for Section 41.12. Before/after states pass through {@link JsonRedactor}; the admin's
 * username is joined in so a row reads "who", not "actor 7".
 */
@Component
public class AdminAuditReader {

    private final NamedParameterJdbcTemplate jdbc;
    private final JsonRedactor redactor;

    public AdminAuditReader(NamedParameterJdbcTemplate jdbc, JsonRedactor redactor) {
        this.jdbc = jdbc;
        this.redactor = redactor;
    }

    public record AuditRow(long id, String actorType, Long actorId, String actorUsername, String action,
                           String targetType, Long targetId, JsonNode beforeState, JsonNode afterState,
                           String ipAddress, Instant occurredAt) {
    }

    @Transactional(readOnly = true)
    public Page<AuditRow> list(String action, String targetType, Long targetId, Long actorId, Instant from,
                               Instant to, Paging paging) {
        SqlFilter f = new SqlFilter()
                .and("a.action = :action", "action", action)
                .and("a.target_type = :tt", "tt", targetType)
                .and("a.target_id = :tid", "tid", targetId)
                .and("a.actor_id = :actor", "actor", actorId)
                .and("a.occurred_at >= :from", "from", SqlFilter.ts(from))
                .and("a.occurred_at < :to", "to", SqlFilter.ts(to));
        List<AuditRow> rows = jdbc.query("""
                SELECT a.id, a.actor_type, a.actor_id, u.username, a.action, a.target_type, a.target_id,
                       a.before_state::text AS before_state, a.after_state::text AS after_state,
                       a.ip_address, a.occurred_at
                FROM audit_log a
                LEFT JOIN admin_user u ON a.actor_type = 'ADMIN_USER' AND u.id = a.actor_id
                """ + f.where() + " ORDER BY a.id DESC LIMIT :limit OFFSET :offset", f.params(paging),
                (rs, i) -> new AuditRow(rs.getLong("id"), rs.getString("actor_type"), (Long) rs.getObject("actor_id"),
                        rs.getString("username"), rs.getString("action"), rs.getString("target_type"),
                        (Long) rs.getObject("target_id"), redactor.redact(rs.getString("before_state")),
                        redactor.redact(rs.getString("after_state")), rs.getString("ip_address"),
                        instant(rs, "occurred_at")));
        return page(rows, paging);
    }
}
