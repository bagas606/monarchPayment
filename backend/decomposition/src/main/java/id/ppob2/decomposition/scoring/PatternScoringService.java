package id.ppob2.decomposition.scoring;

import id.ppob2.configuration.ConfigKey;
import id.ppob2.configuration.ConfigurationService;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes {@code pattern_economics} (Section 30.1) for one generation and one business day, set-based in SQL so
 * a full pool of ~1.2M patterns is one statement, not 1.2M round trips.
 *
 * <p>Routing only considers a pattern that has an economics row for <em>today</em>
 * ({@code RoutingService.selectBestEligible}), so a generation with no rows serves no orders at all. That is why
 * this exists before the generator does, and why activation scores first.
 *
 * <p><b>What is and is not in the score.</b> Section 31.3 has seven terms. Two have inputs in this codebase and are
 * computed: margin ({@code net_margin_pct}) and the child-count penalty ({@code component_count}, one child order per
 * component). Provider health, availability, capacity, load balance and failure have no data source yet and
 * contribute nothing; their weights are not even configurable, so nobody tunes a dial that is not connected.
 * <pre>score = w_margin * net_margin_pct - w_childcount * (component_count - 1)</pre>
 *
 * <p><b>Eligibility here is economic only</b>: every component has an active price, and the net margin meets the
 * floor. SKU/provider status and quota are checked at order time by routing, from live data, never frozen here.
 *
 * <p><b>Payment fee</b> is {@code fixed + percent * amount}, both default 0: no MDR exists in this codebase or the
 * PRD. Until the PG contract value is configured, net equals gross and the floor is a gross-margin floor.
 *
 * <p>Money columns are whole Rupiah ({@code NUMERIC(18,0)}); percentages are capped to the column's range so an
 * absurd price cannot make the insert fail.
 */
@Service
public class PatternScoringService {

    private static final String SCORE_SQL = """
            INSERT INTO pattern_economics (pattern_id, snapshot_date, provider_cost_total, gross_profit, gross_margin_pct,
                                           payment_fee, net_contribution, net_margin_pct, score, eligible)
            SELECT x.id, :day,
                   x.cost,
                   x.amount - x.cost,
                   LEAST(GREATEST(CASE WHEN x.priced THEN (x.amount - x.cost) * 100.0 / x.amount ELSE 0 END, -999.9999), 999.9999),
                   x.fee,
                   x.amount - x.cost - x.fee,
                   LEAST(GREATEST(CASE WHEN x.priced THEN (x.amount - x.cost - x.fee) * 100.0 / x.amount ELSE 0 END, -999.9999), 999.9999),
                   CASE WHEN x.priced THEN
                       LEAST(GREATEST(:wMargin * LEAST(GREATEST((x.amount - x.cost - x.fee) * 100.0 / x.amount, -999.9999), 999.9999)
                                      - :wChild * (x.component_count - 1), -999999.9999), 999999.9999)
                   ELSE 0 END,
                   x.priced AND (x.amount - x.cost - x.fee) * 100.0 / x.amount >= :floor
            FROM (
                SELECT p.id, p.parent_amount AS amount, p.component_count,
                       COALESCE(SUM(c.quantity * pr.provider_cost), 0) AS cost,
                       COUNT(*) FILTER (WHERE pr.id IS NULL) = 0 AS priced,
                       ROUND(:feeFixed + p.parent_amount * :feePct / 100.0) AS fee
                FROM decomposition_pattern p
                JOIN decomposition_component c ON c.pattern_id = p.id
                LEFT JOIN provider_price pr ON pr.provider_sku_id = c.provider_sku_id AND pr.effective_until IS NULL
                WHERE p.generation_id = :gen AND p.structural_status = 'VALID'
                GROUP BY p.id, p.parent_amount, p.component_count
            ) x
            """;

    private static final String UPSERT = """
            ON CONFLICT (pattern_id, snapshot_date) DO UPDATE SET
                provider_cost_total = EXCLUDED.provider_cost_total, gross_profit = EXCLUDED.gross_profit,
                gross_margin_pct = EXCLUDED.gross_margin_pct, payment_fee = EXCLUDED.payment_fee,
                net_contribution = EXCLUDED.net_contribution, net_margin_pct = EXCLUDED.net_margin_pct,
                score = EXCLUDED.score, eligible = EXCLUDED.eligible
            """;

    private static final String KEEP = " ON CONFLICT (pattern_id, snapshot_date) DO NOTHING";

    private final NamedParameterJdbcTemplate jdbc;
    private final ConfigurationService config;

    public PatternScoringService(NamedParameterJdbcTemplate jdbc, ConfigurationService config) {
        this.jdbc = jdbc;
        this.config = config;
    }

    /** Recompute every pattern of the generation for {@code day}, overwriting any row that exists (Section 30.2). */
    @Transactional
    public int rescore(long generationId, LocalDate day) {
        return jdbc.update(SCORE_SQL + UPSERT, params(generationId, day));
    }

    /** Fill in only the patterns that have no row for {@code day}; never touches an existing one. */
    @Transactional
    public int ensure(long generationId, LocalDate day) {
        return jdbc.update(SCORE_SQL + KEEP, params(generationId, day));
    }

    private MapSqlParameterSource params(long generationId, LocalDate day) {
        BigDecimal feeFixed = config.get(ConfigKey.PAYMENT_FEE_FIXED);
        return new MapSqlParameterSource()
                .addValue("gen", generationId)
                .addValue("day", day)
                .addValue("wMargin", config.get(ConfigKey.ROUTING_WEIGHT_MARGIN))
                .addValue("wChild", config.get(ConfigKey.ROUTING_WEIGHT_CHILDCOUNT))
                .addValue("feeFixed", feeFixed)
                .addValue("feePct", config.get(ConfigKey.PAYMENT_FEE_PERCENT))
                .addValue("floor", config.get(ConfigKey.MIN_NET_MARGIN_PCT));
    }
}
