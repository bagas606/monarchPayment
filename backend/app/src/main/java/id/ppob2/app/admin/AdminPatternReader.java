package id.ppob2.app.admin;

import static id.ppob2.app.admin.AdminTransactionReader.instant;
import static id.ppob2.app.admin.AdminTransactionReader.page;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.ppob2.app.admin.AdminTransactionReader.Page;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Read model for Section 41.4 (decomposition) and the pattern half of 41.5 (margin). Plain SQL, explicit columns. */
@Component
public class AdminPatternReader {

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock businessClock;
    private final ObjectMapper mapper;

    public AdminPatternReader(NamedParameterJdbcTemplate jdbc, Clock businessClock, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.businessClock = businessClock;
        this.mapper = mapper;
    }

    public record GenerationRow(long id, String status, String triggeredBy, String scope, Instant startedAt, Instant completedAt,
                                Instant activatedAt, Long patternCount, String notes, boolean ready) {
    }

    public record GenerationDetail(GenerationRow generation, JsonNode params, JsonNode report) {
    }

    public record ComponentRow(long providerSkuId, String providerSkuCode, long providerId, int quantity, long faceValue) {
    }

    public record EconomicsRow(LocalDate snapshotDate, BigDecimal providerCostTotal, BigDecimal grossProfit, BigDecimal grossMarginPct,
                               BigDecimal paymentFee, BigDecimal netContribution, BigDecimal netMarginPct, BigDecimal score,
                               boolean eligible) {
    }

    public record PatternRow(long id, long generationId, long parentAmount, int componentCount, int totalQuantity,
                             String structuralStatus, String patternHash, List<ComponentRow> components, EconomicsRow economicsToday) {
    }

    public record PatternDetail(PatternRow pattern, List<EconomicsRow> economicsHistory) {
    }

    @Transactional(readOnly = true)
    public Page<GenerationRow> listGenerations(Paging paging) {
        List<GenerationRow> rows = jdbc.query("""
                SELECT id, status, triggered_by, scope, started_at, completed_at, activated_at, pattern_count, notes
                FROM pattern_generation ORDER BY id DESC LIMIT :limit OFFSET :offset
                """, new MapSqlParameterSource("limit", paging.fetchLimit()).addValue("offset", paging.offset()),
                (rs, i) -> generation(rs));
        return page(rows, paging);
    }

    @Transactional(readOnly = true)
    public Optional<GenerationDetail> generation(long id) {
        return jdbc.query("""
                SELECT id, status, triggered_by, scope, started_at, completed_at, activated_at, pattern_count, notes,
                       params::text AS params, report::text AS report
                FROM pattern_generation WHERE id = :id
                """, Map.of("id", id), (rs, i) -> new GenerationDetail(generation(rs),
                        json(rs.getString("params")), json(rs.getString("report")))).stream().findFirst();
    }

    private JsonNode json(String text) {
        try {
            return text == null ? null : mapper.readTree(text);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }

    private static GenerationRow generation(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new GenerationRow(rs.getLong("id"), rs.getString("status"), rs.getString("triggered_by"), rs.getString("scope"),
                instant(rs, "started_at"), instant(rs, "completed_at"), instant(rs, "activated_at"),
                (Long) rs.getObject("pattern_count"), rs.getString("notes"),
                "VALIDATING".equals(rs.getString("status")) && rs.getObject("completed_at") != null);
    }

    @Transactional(readOnly = true)
    public Optional<Long> activeGenerationId() {
        return jdbc.queryForList("SELECT id FROM pattern_generation WHERE status = 'ACTIVE'", Map.of(), Long.class).stream().findFirst();
    }

    /** {@code generationId} null = the ACTIVE one. */
    @Transactional(readOnly = true)
    public Page<PatternRow> listPatterns(Long generationId, Long parentAmount, Long productId, String structuralStatus,
                                         Boolean eligible, Paging paging) {
        Long gen = generationId != null ? generationId : activeGenerationId().orElse(-1L);
        LocalDate today = LocalDate.now(businessClock);
        SqlFilter f = new SqlFilter()
                .and("p.generation_id = :gen", "gen", gen)
                .and("p.parent_amount = :amount", "amount", parentAmount)
                .and("p.structural_status = :ss", "ss", structuralStatus)
                .and("e.eligible = :eligible", "eligible", eligible)
                .and("EXISTS (SELECT 1 FROM decomposition_component c JOIN provider_sku s ON s.id = c.provider_sku_id "
                        + "WHERE c.pattern_id = p.id AND s.product_id = :product)", "product", productId);
        MapSqlParameterSource params = f.params(paging).addValue("today", today);
        List<PatternRow> rows = jdbc.query("""
                SELECT p.id, p.generation_id, p.parent_amount, p.component_count, p.total_quantity, p.structural_status, p.pattern_hash,
                       e.snapshot_date, e.provider_cost_total, e.gross_profit, e.gross_margin_pct, e.payment_fee, e.net_contribution,
                       e.net_margin_pct, e.score, e.eligible
                FROM decomposition_pattern p
                LEFT JOIN pattern_economics e ON e.pattern_id = p.id AND e.snapshot_date = :today
                """ + f.where() + " ORDER BY p.parent_amount, p.total_quantity, p.component_count, p.id LIMIT :limit OFFSET :offset",
                params, (rs, i) -> pattern(rs, List.of()));
        return page(withComponents(rows, paging), paging);
    }

    @Transactional(readOnly = true)
    public Optional<PatternDetail> pattern(long id) {
        LocalDate today = LocalDate.now(businessClock);
        List<PatternRow> rows = jdbc.query("""
                SELECT p.id, p.generation_id, p.parent_amount, p.component_count, p.total_quantity, p.structural_status, p.pattern_hash,
                       e.snapshot_date, e.provider_cost_total, e.gross_profit, e.gross_margin_pct, e.payment_fee, e.net_contribution,
                       e.net_margin_pct, e.score, e.eligible
                FROM decomposition_pattern p
                LEFT JOIN pattern_economics e ON e.pattern_id = p.id AND e.snapshot_date = :today
                WHERE p.id = :id
                """, new MapSqlParameterSource("id", id).addValue("today", today), (rs, i) -> pattern(rs, List.of()));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        List<EconomicsRow> history = jdbc.query("""
                SELECT snapshot_date, provider_cost_total, gross_profit, gross_margin_pct, payment_fee, net_contribution,
                       net_margin_pct, score, eligible
                FROM pattern_economics WHERE pattern_id = :id ORDER BY snapshot_date DESC LIMIT 90
                """, Map.of("id", id), (rs, i) -> economics(rs));
        return Optional.of(new PatternDetail(withComponents(rows, null).get(0), history));
    }

    private List<PatternRow> withComponents(List<PatternRow> rows, Paging paging) {
        List<PatternRow> visible = paging == null ? rows : rows.subList(0, Math.min(rows.size(), paging.size()));
        if (visible.isEmpty()) {
            return rows;
        }
        Map<Long, List<ComponentRow>> byPattern = new HashMap<>();
        jdbc.query("""
                SELECT c.pattern_id, c.provider_sku_id, s.provider_sku_code, s.provider_id, c.quantity, c.face_value::bigint AS face_value
                FROM decomposition_component c JOIN provider_sku s ON s.id = c.provider_sku_id
                WHERE c.pattern_id IN (:ids) ORDER BY c.pattern_id, c.provider_sku_id
                """, Map.of("ids", visible.stream().map(PatternRow::id).toList()), rs -> {
                    byPattern.computeIfAbsent(rs.getLong("pattern_id"), k -> new ArrayList<>()).add(new ComponentRow(
                            rs.getLong("provider_sku_id"), rs.getString("provider_sku_code"), rs.getLong("provider_id"),
                            rs.getInt("quantity"), rs.getLong("face_value")));
                });
        List<PatternRow> out = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            PatternRow r = rows.get(i);
            out.add(i < visible.size() ? new PatternRow(r.id(), r.generationId(), r.parentAmount(), r.componentCount(),
                    r.totalQuantity(), r.structuralStatus(), r.patternHash(), byPattern.getOrDefault(r.id(), List.of()),
                    r.economicsToday()) : r);
        }
        return out;
    }

    private static PatternRow pattern(java.sql.ResultSet rs, List<ComponentRow> components) throws java.sql.SQLException {
        EconomicsRow e = rs.getObject("snapshot_date") == null ? null : economics(rs);
        return new PatternRow(rs.getLong("id"), rs.getLong("generation_id"), rs.getLong("parent_amount"), rs.getInt("component_count"),
                rs.getInt("total_quantity"), rs.getString("structural_status"), rs.getString("pattern_hash"), components, e);
    }

    private static EconomicsRow economics(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new EconomicsRow(rs.getObject("snapshot_date", LocalDate.class), rs.getBigDecimal("provider_cost_total"),
                rs.getBigDecimal("gross_profit"), rs.getBigDecimal("gross_margin_pct"), rs.getBigDecimal("payment_fee"),
                rs.getBigDecimal("net_contribution"), rs.getBigDecimal("net_margin_pct"), rs.getBigDecimal("score"),
                rs.getBoolean("eligible"));
    }
}
