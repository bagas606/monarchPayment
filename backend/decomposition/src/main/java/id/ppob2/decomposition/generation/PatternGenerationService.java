package id.ppob2.decomposition.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import id.ppob2.configuration.ConfigKey;
import id.ppob2.configuration.ConfigurationService;
import id.ppob2.decomposition.generation.PatternGenerator.Params;
import id.ppob2.decomposition.generation.PatternGenerator.Pattern;
import id.ppob2.decomposition.generation.PatternGenerator.Result;
import id.ppob2.decomposition.generation.PatternGenerator.Sku;
import id.ppob2.decomposition.scoring.PatternScoringService;
import id.ppob2.sharedkernel.error.ApiException;
import id.ppob2.sharedkernel.error.ErrorCode;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Generation lifecycle, PRD Section 40.2:
 * <pre>BUILDING -> VALIDATING (built, checked, scored; waiting) -> ACTIVE -> SUPERSEDED   (or FAILED)</pre>
 * A generation is always FULL here: lookup serves exactly one ACTIVE generation, so a new one has to cover every
 * active product and each of its category's supported amounts. Partial regeneration (Section 30.3) would mean copying
 * the unaffected patterns forward and is not built; a structural catalog change invalidates the affected patterns
 * instead (see the catalog admin), and a full regeneration rebuilds.
 *
 * <p>Activation is never automatic. It is a human decision with a coverage check in front of it, and it is a single
 * transaction that supersedes the old generation before activating the new one, so the one-ACTIVE unique index is never
 * violated and a reader never sees two.
 *
 * <p>Writes are chunked JDBC batches (Section 40.1 prohibits row-at-a-time inserts), and one (product, amount) is
 * generated, written and dropped before the next, so memory is bounded by K, not by the whole pool.
 */
@Service
public class PatternGenerationService {

    private static final Logger log = LoggerFactory.getLogger(PatternGenerationService.class);
    private static final long NODE_BUDGET = 2_000_000;
    private static final int CHUNK = 1000;
    static final String ALGORITHM = "levelwise-bounded-search/1";

    private final NamedParameterJdbcTemplate jdbc;
    private final ConfigurationService config;
    private final PatternScoringService scoring;
    private final Clock businessClock;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pattern-generation");
        t.setDaemon(true);
        return t;
    });

    public PatternGenerationService(NamedParameterJdbcTemplate jdbc, ConfigurationService config,
                                    PatternScoringService scoring, Clock businessClock) {
        this.jdbc = jdbc;
        this.config = config;
        this.scoring = scoring;
        this.businessClock = businessClock;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /** Builds {@code id} in the background. Call after {@link #begin} has committed, so the row is visible to the worker. */
    public void launch(long id) {
        executor.submit(() -> build(id));
    }

    /** Records a new BUILDING run, refusing if another is in flight. Public and transactional: call it through the bean. */
    @Transactional
    public long begin(String triggeredBy) {
        // The check-then-insert below must not interleave with another trigger or an activation, so serialise on a lock.
        jdbc.getJdbcTemplate().execute("SELECT pg_advisory_xact_lock(7001)");
        Integer running = jdbc.queryForObject(
                "SELECT count(*) FROM pattern_generation WHERE status = 'BUILDING' OR (status = 'VALIDATING' AND completed_at IS NULL)",
                Map.of(), Integer.class);
        if (running != null && running > 0) {
            throw new ApiException(ErrorCode.GENERATION_IN_PROGRESS, "A pattern generation is already running.");
        }
        ObjectNode params = mapper.createObjectNode();
        params.put("algorithm", ALGORITHM);
        params.put("max_patterns_per_amount", config.getInt(ConfigKey.GENERATION_MAX_PATTERNS));
        params.put("max_total_quantity", config.getInt(ConfigKey.GENERATION_MAX_QUANTITY));
        params.put("extra_quantity", config.getInt(ConfigKey.GENERATION_EXTRA_QUANTITY));
        params.put("node_budget_per_amount", NODE_BUDGET);
        return jdbc.queryForObject("""
                INSERT INTO pattern_generation (status, triggered_by, scope, started_at, params)
                VALUES ('BUILDING', :by, 'FULL', now(), CAST(:params AS jsonb)) RETURNING id
                """, new MapSqlParameterSource("by", triggeredBy).addValue("params", params.toString()), Long.class);
    }

    /** Marks runs a restart interrupted as FAILED, so they stop blocking new ones. */
    public int failInterrupted() {
        return jdbc.update("""
                UPDATE pattern_generation SET status = 'FAILED', completed_at = now(), notes = 'Interrupted by an application restart.'
                WHERE status = 'BUILDING' OR (status = 'VALIDATING' AND completed_at IS NULL)
                """, Map.of());
    }

    /** Builds, validates and scores generation {@code id}. Never throws: any failure becomes status FAILED. */
    public void build(long id) {
        try {
            doBuild(id);
        } catch (Exception e) {
            log.error("Pattern generation {} failed", id, e);
            String why = e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "" : e.getMessage());
            jdbc.update("UPDATE pattern_generation SET status = 'FAILED', completed_at = now(), notes = :n WHERE id = :id",
                    new MapSqlParameterSource("n", why.length() > 500 ? why.substring(0, 500) : why).addValue("id", id));
        }
    }

    private void doBuild(long id) throws Exception {
        Params params = new Params(config.getInt(ConfigKey.GENERATION_MAX_PATTERNS),
                config.getInt(ConfigKey.GENERATION_MAX_QUANTITY), config.getInt(ConfigKey.GENERATION_EXTRA_QUANTITY), NODE_BUDGET);

        List<Map<String, Object>> products = jdbc.queryForList(
                "SELECT id, code, category FROM product WHERE status = 'ACTIVE' ORDER BY id", Map.of());
        ObjectNode report = mapper.createObjectNode();
        ArrayNode productReports = report.putArray("products");
        long total = 0;
        int builtAmounts = 0;
        int emptyAmounts = 0;
        int limited = 0;
        int exhausted = 0;
        StringBuilder fingerprint = new StringBuilder();

        for (Map<String, Object> product : products) {
            long productId = ((Number) product.get("id")).longValue();
            List<Sku> skus = jdbc.query("""
                    SELECT id, face_value, quota_daily FROM provider_sku WHERE product_id = :p AND status = 'ACTIVE' ORDER BY id
                    """, Map.of("p", productId), (rs, i) -> new Sku(rs.getLong(1), rs.getLong(2), (Integer) rs.getObject(3)));
            List<Long> amounts = jdbc.queryForList("""
                    SELECT amount FROM supported_amount WHERE product_category = :c AND status = 'ACTIVE' ORDER BY amount
                    """, Map.of("c", product.get("category")), Long.class);
            skus.forEach(s -> fingerprint.append(s.id()).append(':').append(s.faceValue()).append(':').append(s.cap()).append(';'));
            amounts.forEach(a -> fingerprint.append(productId).append('@').append(a).append(';'));

            ObjectNode pr = productReports.addObject();
            pr.put("product_id", productId);
            pr.put("product_code", String.valueOf(product.get("code")));
            pr.put("active_skus", skus.size());
            ArrayNode amountReports = pr.putArray("amounts");

            for (long amount : amounts) {
                Result r = PatternGenerator.generate(skus, amount, params);
                insertPatterns(id, amount, r.patterns());
                total += r.patterns().size();
                builtAmounts++;
                if (r.patterns().isEmpty()) {
                    emptyAmounts++;
                }
                if (r.hitPatternLimit()) {
                    limited++;
                }
                if (r.budgetExhausted()) {
                    exhausted++;
                }
                ObjectNode ar = amountReports.addObject();
                ar.put("amount", amount);
                ar.put("patterns", r.patterns().size());
                ar.put("infeasible", r.infeasible());
                ar.put("hit_pattern_limit", r.hitPatternLimit());
                ar.put("budget_exhausted", r.budgetExhausted());
            }
        }
        ObjectNode summary = report.putObject("summary");
        summary.put("patterns", total);
        summary.put("amounts", builtAmounts);
        summary.put("amounts_without_patterns", emptyAmounts);
        summary.put("amounts_at_pattern_limit", limited);
        summary.put("amounts_budget_exhausted", exhausted);

        jdbc.update("UPDATE pattern_generation SET status = 'VALIDATING' WHERE id = :id", Map.of("id", id));
        jdbc.update("""
                INSERT INTO decomposition_component (pattern_id, provider_sku_id, quantity, face_value)
                SELECT p.id, (e->>'provider_sku_id')::bigint, (e->>'quantity')::int, (e->>'face_value')::numeric
                FROM decomposition_pattern p CROSS JOIN LATERAL jsonb_array_elements(p.components) e
                WHERE p.generation_id = :g
                """, Map.of("g", id));

        validate(id, total);
        scoring.rescore(id, LocalDate.now(businessClock));

        ObjectNode paramsUsed = (ObjectNode) mapper.readTree(jdbc.queryForObject(
                "SELECT params::text FROM pattern_generation WHERE id = :id", Map.of("id", id), String.class));
        paramsUsed.put("catalog_fingerprint", sha(fingerprint.toString()));
        jdbc.update("""
                UPDATE pattern_generation SET completed_at = now(), pattern_count = :n, report = CAST(:r AS jsonb),
                       params = CAST(:p AS jsonb), notes = :notes WHERE id = :id
                """, new MapSqlParameterSource("n", total).addValue("r", report.toString()).addValue("p", paramsUsed.toString())
                .addValue("notes", "Built and validated; waiting for activation.").addValue("id", id));
        log.info("Pattern generation {} built: {} patterns over {} amounts", id, total, builtAmounts);
    }

    private void insertPatterns(long generationId, long amount, List<Pattern> patterns) throws Exception {
        List<SqlParameterSource> batch = new ArrayList<>(CHUNK);
        for (Pattern p : patterns) {
            ArrayNode comps = mapper.createArrayNode();
            p.components().forEach(c -> comps.addObject().put("provider_sku_id", c.skuId()).put("quantity", c.quantity())
                    .put("face_value", c.faceValue()));
            batch.add(new MapSqlParameterSource("g", generationId).addValue("a", amount).addValue("c", comps.toString())
                    .addValue("cc", p.componentCount()).addValue("tq", p.totalQuantity()).addValue("h", p.hash()));
            if (batch.size() == CHUNK) {
                flush(batch);
            }
        }
        flush(batch);
    }

    private void flush(List<SqlParameterSource> batch) {
        if (batch.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT INTO decomposition_pattern (generation_id, parent_amount, components, component_count, total_quantity, pattern_hash)
                VALUES (:g, :a, CAST(:c AS jsonb), :cc, :tq, :h)
                """, batch.toArray(new SqlParameterSource[0]));
        batch.clear();
    }

    /** Section 28.2 re-checked in SQL against what was actually written, not against what the generator believes. */
    private void validate(long id, long expectedPatterns) {
        Long stored = jdbc.queryForObject("SELECT count(*) FROM decomposition_pattern WHERE generation_id = :g", Map.of("g", id), Long.class);
        if (stored == null || stored != expectedPatterns) {
            throw new IllegalStateException("pattern count mismatch: generated " + expectedPatterns + ", stored " + stored);
        }
        Long broken = jdbc.queryForObject("""
                SELECT count(*) FROM (
                    SELECT p.id FROM decomposition_pattern p JOIN decomposition_component c ON c.pattern_id = p.id
                    WHERE p.generation_id = :g
                    GROUP BY p.id, p.parent_amount, p.component_count, p.total_quantity
                    HAVING SUM(c.quantity * c.face_value) <> p.parent_amount OR COUNT(*) <> p.component_count
                        OR SUM(c.quantity) <> p.total_quantity) x
                """, Map.of("g", id), Long.class);
        if (broken != null && broken > 0) {
            throw new IllegalStateException(broken + " pattern(s) violate the Section 28.2 sum invariant or their own counts");
        }
        Long withoutComponents = jdbc.queryForObject("""
                SELECT count(*) FROM decomposition_pattern p WHERE p.generation_id = :g
                  AND NOT EXISTS (SELECT 1 FROM decomposition_component c WHERE c.pattern_id = p.id)
                """, Map.of("g", id), Long.class);
        if (withoutComponents != null && withoutComponents > 0) {
            throw new IllegalStateException(withoutComponents + " pattern(s) have no components");
        }
        Long drift = jdbc.queryForObject("""
                SELECT count(*) FROM decomposition_component c JOIN decomposition_pattern p ON p.id = c.pattern_id
                JOIN provider_sku s ON s.id = c.provider_sku_id
                WHERE p.generation_id = :g AND s.face_value <> c.face_value
                """, Map.of("g", id), Long.class);
        if (drift != null && drift > 0) {
            throw new IllegalStateException("a SKU face value changed while the generation was being built (" + drift + " components)");
        }
    }

    /** Eligible (product_id, amount) pairs a generation serves for {@code day}: the unit routing needs at least one of. */
    public Set<List<Long>> coverage(long generationId, LocalDate day) {
        Set<List<Long>> out = new HashSet<>();
        jdbc.query("""
                SELECT DISTINCT s.product_id, p.parent_amount::bigint
                FROM decomposition_pattern p
                JOIN pattern_economics e ON e.pattern_id = p.id AND e.snapshot_date = :d AND e.eligible
                JOIN decomposition_component c ON c.pattern_id = p.id
                JOIN provider_sku s ON s.id = c.provider_sku_id
                WHERE p.generation_id = :g AND p.structural_status = 'VALID'
                """, new MapSqlParameterSource("g", generationId).addValue("d", day),
                rs -> { out.add(List.of(rs.getLong(1), rs.getLong(2))); });
        return out;
    }

    public record Activation(long activated, Long superseded, List<List<Long>> lostCoverage, List<List<Long>> gainedCoverage) {
    }

    /**
     * Makes {@code id} the ACTIVE generation (also how a rollback re-activates a SUPERSEDED one). It re-scores the target
     * for today first, because routing ignores any pattern without today's economics, then refuses if some
     * (product, amount) the current generation can serve would be left with no eligible pattern, unless {@code reason}
     * explicitly accepts that.
     */
    @Transactional
    public Activation activate(long id, String reason) {
        jdbc.getJdbcTemplate().execute("SELECT pg_advisory_xact_lock(7001)");
        Map<String, Object> target = jdbc.queryForList("SELECT id, status, completed_at FROM pattern_generation WHERE id = :id FOR UPDATE",
                Map.of("id", id)).stream().findFirst().orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No such generation."));
        String status = (String) target.get("status");
        boolean ready = "VALIDATING".equals(status) && target.get("completed_at") != null;
        if (!ready && !"SUPERSEDED".equals(status)) {
            throw new ApiException(ErrorCode.GENERATION_NOT_ACTIVATABLE,
                    "Only a built-and-validated or a superseded generation can be activated (this one is " + status + ").");
        }
        Long current = jdbc.queryForList("SELECT id FROM pattern_generation WHERE status = 'ACTIVE' FOR UPDATE", Map.of(), Long.class)
                .stream().findFirst().orElse(null);

        LocalDate today = LocalDate.now(businessClock);
        scoring.rescore(id, today);
        if (current != null) {
            scoring.ensure(current, today);
        }

        Set<List<Long>> after = coverage(id, today);
        Set<List<Long>> before = current == null ? Set.of() : coverage(current, today);
        List<List<Long>> lost = new TreeSet<>(PAIR_ORDER) {{ before.stream().filter(p -> !after.contains(p)).forEach(this::add); }}.stream().toList();
        List<List<Long>> gained = new TreeSet<>(PAIR_ORDER) {{ after.stream().filter(p -> !before.contains(p)).forEach(this::add); }}.stream().toList();
        if (!lost.isEmpty() && (reason == null || reason.isBlank())) {
            throw new ApiException(ErrorCode.COVERAGE_REGRESSION, lost.size() + " (product, amount) pair(s) the active generation serves "
                    + "would have no eligible pattern; give a reason to activate anyway.");
        }

        if (current != null) {
            jdbc.update("UPDATE pattern_generation SET status = 'SUPERSEDED' WHERE id = :id", Map.of("id", current));
        }
        jdbc.update("UPDATE pattern_generation SET status = 'ACTIVE', activated_at = now() WHERE id = :id", Map.of("id", id));
        return new Activation(id, current, lost, gained);
    }

    private static final java.util.Comparator<List<Long>> PAIR_ORDER =
            java.util.Comparator.<List<Long>>comparingLong(p -> p.get(0)).thenComparingLong(p -> p.get(1));

    /** Throws a built generation away without ever serving it. */
    @Transactional
    public void discard(long id) {
        int n = jdbc.update("""
                UPDATE pattern_generation SET status = 'FAILED', notes = 'Discarded by an admin before activation.'
                WHERE id = :id AND status = 'VALIDATING' AND completed_at IS NOT NULL
                """, Map.of("id", id));
        if (n == 0) {
            throw new ApiException(ErrorCode.GENERATION_NOT_ACTIVATABLE, "Only a built generation that has not been activated can be discarded.");
        }
    }

    private static String sha(String s) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

}
