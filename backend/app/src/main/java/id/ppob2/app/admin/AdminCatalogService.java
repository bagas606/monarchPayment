package id.ppob2.app.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.ppob2.audit.AuditService;
import id.ppob2.decomposition.generation.PatternGenerationService;
import id.ppob2.decomposition.scoring.PatternScoringService;
import id.ppob2.sharedkernel.error.ApiException;
import id.ppob2.sharedkernel.error.ErrorCode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes for Section 41.3 (catalog) and the supported-amount half of 41.11. Plain SQL on purpose: the {@code catalog} and
 * {@code configuration} entities stay read-only, and every statement here is one an auditor can read.
 *
 * <p><b>Nothing is deleted.</b> Products, providers, SKUs and amounts are switched off, because orders and patterns refer to
 * them (Section 41.3: "enable/disable, not hard delete where referenced").
 *
 * <p><b>Structural changes.</b> Changing a SKU's face value changes what every pattern containing it adds up to, so those
 * patterns are marked {@code INVALIDATED} in the same transaction (Section 30.3: marked, never deleted) and the lost coverage is
 * returned. Disabling a SKU needs no invalidation: routing excludes inactive SKUs from live data. A new SKU or amount changes
 * nothing until the next generation, and the response says so.
 *
 * <p>Every change writes an {@code audit_log} row with before and after, in the same transaction as the change.
 */
@Service
public class AdminCatalogService {

    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_\\-]{1,63}");
    private static final Set<String> ITEM_STATUS = Set.of("ACTIVE", "INACTIVE");
    private static final Set<String> PROVIDER_STATUS = Set.of("ACTIVE", "DEGRADED", "DISABLED");
    private static final long MAX_FACE = 10_000_000_000L;

    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService audit;
    private final ObjectMapper mapper;
    private final PatternGenerationService generations;
    private final PatternScoringService scoring;
    private final Clock businessClock;

    public AdminCatalogService(NamedParameterJdbcTemplate jdbc, AuditService audit, ObjectMapper mapper,
                               PatternGenerationService generations, PatternScoringService scoring, Clock businessClock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.mapper = mapper;
        this.generations = generations;
        this.scoring = scoring;
        this.businessClock = businessClock;
    }

    // ---- reads ---------------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Map<String, Object>> products() {
        return jdbc.queryForList("""
                SELECT p.id, p.code, p.name, p.category, p.status,
                       (SELECT count(*) FROM provider_sku s WHERE s.product_id = p.id AND s.status = 'ACTIVE') AS active_skus
                FROM product p ORDER BY p.id""", Map.of());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> providers() {
        return jdbc.queryForList("""
                SELECT v.id, v.code, v.name, v.status, v.timeout_ms, v.rate_limit_per_min,
                       (SELECT count(*) FROM provider_sku s WHERE s.provider_id = v.id AND s.status = 'ACTIVE') AS active_skus
                FROM provider v ORDER BY v.id""", Map.of());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> skus(Long productId, Long providerId, String status) {
        SqlFilter f = new SqlFilter().and("s.product_id = :product", "product", productId)
                .and("s.provider_id = :provider", "provider", providerId).and("s.status = :status", "status", status);
        return jdbc.queryForList("""
                SELECT s.id, s.provider_id, v.code AS provider_code, s.product_id, p.code AS product_code, s.provider_sku_code,
                       s.face_value::bigint AS face_value, s.status, s.quota_daily,
                       pr.provider_cost::bigint AS current_cost, pr.pricing_version AS current_pricing_version
                FROM provider_sku s JOIN provider v ON v.id = s.provider_id JOIN product p ON p.id = s.product_id
                LEFT JOIN provider_price pr ON pr.provider_sku_id = s.id AND pr.effective_until IS NULL
                """ + f.where() + " ORDER BY s.product_id, s.face_value, s.id", f.params());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> priceHistory(long skuId) {
        return jdbc.queryForList("""
                SELECT id, pricing_version, provider_cost::bigint AS provider_cost, effective_from, effective_until
                FROM provider_price WHERE provider_sku_id = :id ORDER BY pricing_version DESC""", Map.of("id", skuId));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> supportedAmounts(String category) {
        Long gen = jdbc.queryForList("SELECT id FROM pattern_generation WHERE status = 'ACTIVE'", Map.of(), Long.class)
                .stream().findFirst().orElse(-1L);
        SqlFilter f = new SqlFilter().and("a.product_category = :category", "category", category);
        MapSqlParameterSource params = f.params().addValue("gen", gen);
        return jdbc.queryForList("""
                SELECT a.id, a.product_category, a.amount::bigint AS amount, a.status, a.effective_from,
                       (SELECT count(*) FROM decomposition_pattern p WHERE p.generation_id = :gen AND p.parent_amount = a.amount
                          AND p.structural_status = 'VALID') AS active_patterns
                FROM supported_amount a
                """ + f.where() + " ORDER BY a.product_category, a.amount", params);
    }

    // ---- products / providers -----------------------------------------------------------------------------------------

    @Transactional
    public Map<String, Object> createProduct(long admin, String ip, String code, String name, String category) {
        requireCode(code, "code");
        requireText(name, "name", 255);
        requireCode(category, "category");
        long id = insert("""
                INSERT INTO product (code, name, category, status) VALUES (:code, :name, :category, 'ACTIVE') RETURNING id""",
                new MapSqlParameterSource("code", code).addValue("name", name.trim()).addValue("category", category), "product code");
        audit("PRODUCT_CREATE", "PRODUCT", id, admin, ip, null, Map.of("code", code, "name", name.trim(), "category", category));
        return Map.of("id", id, "note", generationNote("product"));
    }

    @Transactional
    public Map<String, Object> updateProduct(long admin, String ip, long id, String name, String status) {
        Map<String, Object> before = one("SELECT id, code, name, status FROM product WHERE id = :id FOR UPDATE", id, "product");
        if (name != null) {
            requireText(name, "name", 255);
        }
        if (status != null && !ITEM_STATUS.contains(status)) {
            throw bad("'status' must be one of ACTIVE, INACTIVE.");
        }
        jdbc.update("UPDATE product SET name = COALESCE(:name, name), status = COALESCE(:status, status) WHERE id = :id",
                new MapSqlParameterSource("name", name == null ? null : name.trim()).addValue("status", status).addValue("id", id));
        audit("PRODUCT_UPDATE", "PRODUCT", id, admin, ip, before, Map.of("name", name == null ? before.get("name") : name.trim(),
                "status", status == null ? before.get("status") : status));
        return Map.of("id", id);
    }

    @Transactional
    public Map<String, Object> createProvider(long admin, String ip, String code, String name, Integer timeoutMs, Integer rateLimit) {
        requireCode(code, "code");
        requireText(name, "name", 255);
        int timeout = timeoutMs == null ? 5000 : timeoutMs;
        if (timeout < 100 || timeout > 120_000) {
            throw bad("'timeout_ms' must be between 100 and 120000.");
        }
        if (rateLimit != null && (rateLimit < 1 || rateLimit > 1_000_000)) {
            throw bad("'rate_limit_per_min' must be between 1 and 1000000.");
        }
        long id = insert("""
                INSERT INTO provider (code, name, status, timeout_ms, rate_limit_per_min) VALUES (:code, :name, 'ACTIVE', :t, :r) RETURNING id""",
                new MapSqlParameterSource("code", code).addValue("name", name.trim()).addValue("t", timeout).addValue("r", rateLimit),
                "provider code");
        audit("PROVIDER_CREATE", "PROVIDER", id, admin, ip, null, Map.of("code", code, "name", name.trim(), "timeout_ms", timeout));
        return Map.of("id", id);
    }

    @Transactional
    public Map<String, Object> updateProvider(long admin, String ip, long id, String name, String status, Integer timeoutMs, Integer rateLimit) {
        Map<String, Object> before = one("SELECT id, code, name, status, timeout_ms, rate_limit_per_min FROM provider WHERE id = :id FOR UPDATE",
                id, "provider");
        if (name != null) {
            requireText(name, "name", 255);
        }
        if (status != null && !PROVIDER_STATUS.contains(status)) {
            throw bad("'status' must be one of ACTIVE, DEGRADED, DISABLED.");
        }
        if (timeoutMs != null && (timeoutMs < 100 || timeoutMs > 120_000)) {
            throw bad("'timeout_ms' must be between 100 and 120000.");
        }
        if (rateLimit != null && (rateLimit < 1 || rateLimit > 1_000_000)) {
            throw bad("'rate_limit_per_min' must be between 1 and 1000000.");
        }
        jdbc.update("""
                UPDATE provider SET name = COALESCE(:name, name), status = COALESCE(:status, status),
                       timeout_ms = COALESCE(:t, timeout_ms), rate_limit_per_min = COALESCE(:r, rate_limit_per_min) WHERE id = :id""",
                new MapSqlParameterSource("name", name == null ? null : name.trim()).addValue("status", status)
                        .addValue("t", timeoutMs).addValue("r", rateLimit).addValue("id", id));
        Map<String, Object> after = new LinkedHashMap<>(before);
        if (name != null) after.put("name", name.trim());
        if (status != null) after.put("status", status);
        if (timeoutMs != null) after.put("timeout_ms", timeoutMs);
        if (rateLimit != null) after.put("rate_limit_per_min", rateLimit);
        audit("PROVIDER_UPDATE", "PROVIDER", id, admin, ip, before, after);
        return Map.of("id", id);
    }

    // ---- SKUs ----------------------------------------------------------------------------------------------------------

    @Transactional
    public Map<String, Object> createSku(long admin, String ip, long providerId, long productId, String code, long faceValue, Integer quota) {
        requireCode(code, "provider_sku_code");
        if (faceValue < 1 || faceValue > MAX_FACE) {
            throw bad("'face_value' must be between 1 and " + MAX_FACE + ".");
        }
        if (quota != null && quota < 1) {
            throw bad("'quota_daily' must be at least 1 (or omitted for no limit).");
        }
        one("SELECT id FROM provider WHERE id = :id", providerId, "provider");
        one("SELECT id FROM product WHERE id = :id", productId, "product");
        long id = insert("""
                INSERT INTO provider_sku (provider_id, product_id, provider_sku_code, face_value, status, quota_daily)
                VALUES (:provider, :product, :code, :face, 'ACTIVE', :quota) RETURNING id""",
                new MapSqlParameterSource("provider", providerId).addValue("product", productId).addValue("code", code)
                        .addValue("face", faceValue).addValue("quota", quota), "SKU");
        audit("SKU_CREATE", "PROVIDER_SKU", id, admin, ip, null,
                Map.of("provider_id", providerId, "product_id", productId, "provider_sku_code", code, "face_value", faceValue));
        return Map.of("id", id, "note", "It has no price yet, so no pattern using it is eligible, and " + generationNote("SKU"));
    }

    @Transactional
    public Map<String, Object> updateSku(long admin, String ip, long id, String status, Integer quota, Long faceValue, String reason) {
        Map<String, Object> before = one("""
                SELECT id, provider_sku_code, face_value::bigint AS face_value, status, quota_daily FROM provider_sku WHERE id = :id FOR UPDATE""",
                id, "SKU");
        if (status != null && !ITEM_STATUS.contains(status)) {
            throw bad("'status' must be one of ACTIVE, INACTIVE.");
        }
        if (quota != null && quota < 1) {
            throw bad("'quota_daily' must be at least 1.");
        }
        boolean faceChanged = faceValue != null && faceValue != ((Number) before.get("face_value")).longValue();
        if (faceValue != null && (faceValue < 1 || faceValue > MAX_FACE)) {
            throw bad("'face_value' must be between 1 and " + MAX_FACE + ".");
        }
        if (faceChanged && (reason == null || reason.isBlank())) {
            throw bad("Changing a face value invalidates every pattern that contains the SKU; a 'reason' is required.");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        int invalidated = 0;
        List<List<Long>> lost = List.of();
        Long gen = jdbc.queryForList("SELECT id FROM pattern_generation WHERE status = 'ACTIVE'", Map.of(), Long.class)
                .stream().findFirst().orElse(null);
        LocalDate today = LocalDate.now(businessClock);
        var beforeCoverage = faceChanged && gen != null ? generations.coverage(gen, today) : Set.<List<Long>>of();

        jdbc.update("""
                UPDATE provider_sku SET status = COALESCE(:status, status), quota_daily = COALESCE(:quota, quota_daily),
                       face_value = COALESCE(:face, face_value) WHERE id = :id""",
                new MapSqlParameterSource("status", status).addValue("quota", quota).addValue("face", faceChanged ? faceValue : null)
                        .addValue("id", id));
        if (faceChanged) {
            invalidated = jdbc.update("""
                    UPDATE decomposition_pattern SET structural_status = 'INVALIDATED'
                    WHERE structural_status = 'VALID' AND id IN (SELECT pattern_id FROM decomposition_component WHERE provider_sku_id = :id)""",
                    Map.of("id", id));
            if (gen != null) {
                var after = generations.coverage(gen, today);
                lost = beforeCoverage.stream().filter(p -> !after.contains(p)).sorted(
                        java.util.Comparator.<List<Long>>comparingLong(p -> p.get(0)).thenComparingLong(p -> p.get(1))).toList();
            }
            out.put("patterns_invalidated", invalidated);
            out.put("lost_coverage", lost);
            out.put("note", "Those (product, amount) pairs have no valid pattern until a new generation is activated.");
        }
        Map<String, Object> afterState = new LinkedHashMap<>(before);
        if (status != null) afterState.put("status", status);
        if (quota != null) afterState.put("quota_daily", quota);
        if (faceChanged) {
            afterState.put("face_value", faceValue);
            afterState.put("patterns_invalidated", invalidated);
            afterState.put("reason", reason.trim());
        }
        audit("SKU_UPDATE", "PROVIDER_SKU", id, admin, ip, before, afterState);
        return out;
    }

    // ---- prices ---------------------------------------------------------------------------------------------------------

    /** New provider_price version (never an overwrite, Section 40.3), then a re-score of exactly the patterns containing the SKU. */
    @Transactional
    public Map<String, Object> setPrice(long admin, String ip, long skuId, long cost, String reason, id.ppob2.pricing.ProviderPriceService prices) {
        Map<String, Object> sku = one("SELECT id, provider_sku_code, face_value::bigint AS face_value FROM provider_sku WHERE id = :id", skuId, "SKU");
        if (cost < 1 || cost > MAX_FACE) {
            throw bad("'provider_cost' must be between 1 and " + MAX_FACE + ".");
        }
        if (reason == null || reason.isBlank()) {
            throw bad("A 'reason' is required for a price change (Section 40.3 / 43).");
        }
        Long previous = prices.getActiveCost(skuId).map(id.ppob2.sharedkernel.money.Money::toLong).orElse(null);
        var saved = prices.setPrice(skuId, id.ppob2.sharedkernel.money.Money.of(cost), java.time.Instant.now());
        Long gen = jdbc.queryForList("SELECT id FROM pattern_generation WHERE status = 'ACTIVE'", Map.of(), Long.class)
                .stream().findFirst().orElse(null);
        LocalDate today = LocalDate.now(businessClock);
        int rescored = gen == null ? 0 : scoring.rescoreAffected(gen, today, List.of(skuId));
        if (gen != null) {
            scoring.rescoreAffected(gen, today.plusDays(1), List.of(skuId)); // so the next day starts with the new price too
        }
        Map<String, Object> beforeState = new LinkedHashMap<>();
        beforeState.put("provider_cost", previous);
        Map<String, Object> afterState = new LinkedHashMap<>();
        afterState.put("provider_cost", cost);
        afterState.put("pricing_version", saved.getPricingVersion());
        afterState.put("patterns_rescored", rescored);
        afterState.put("reason", reason.trim());
        audit("PROVIDER_PRICE_SET", "PROVIDER_SKU", skuId, admin, ip, beforeState, afterState);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provider_sku_id", skuId);
        out.put("pricing_version", saved.getPricingVersion());
        out.put("previous_cost", previous);
        out.put("provider_cost", cost);
        out.put("patterns_rescored", rescored);
        out.put("at_or_above_face_value", cost >= ((Number) sku.get("face_value")).longValue());
        return out;
    }

    // ---- supported amounts -----------------------------------------------------------------------------------------------

    @Transactional
    public Map<String, Object> createAmount(long admin, String ip, String category, long amount) {
        requireCode(category, "product_category");
        if (amount < 1 || amount > 10_000_000) {
            throw bad("'amount' must be between 1 and 10,000,000 (Section 29.3).");
        }
        long id = insert("""
                INSERT INTO supported_amount (product_category, amount, status, effective_from) VALUES (:c, :a, 'ACTIVE', now()) RETURNING id""",
                new MapSqlParameterSource("c", category).addValue("a", amount), "supported amount");
        audit("SUPPORTED_AMOUNT_CREATE", "SUPPORTED_AMOUNT", id, admin, ip, null, Map.of("product_category", category, "amount", amount));
        return Map.of("id", id, "note", "Orders for this amount are accepted now but cannot be fulfilled until a generation that includes it is activated.");
    }

    @Transactional
    public Map<String, Object> setAmountStatus(long admin, String ip, long id, String status) {
        if (!ITEM_STATUS.contains(status)) {
            throw bad("'status' must be one of ACTIVE, INACTIVE.");
        }
        Map<String, Object> before = one("SELECT id, product_category, amount::bigint AS amount, status FROM supported_amount WHERE id = :id FOR UPDATE",
                id, "supported amount");
        jdbc.update("UPDATE supported_amount SET status = :s WHERE id = :id", Map.of("s", status, "id", id));
        audit("SUPPORTED_AMOUNT_UPDATE", "SUPPORTED_AMOUNT", id, admin, ip, before, Map.of("status", status));
        return Map.of("id", id);
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------------

    private long insert(String sql, MapSqlParameterSource params, String what) {
        try {
            Long id = jdbc.queryForObject(sql, params, Long.class);
            return id == null ? 0 : id;
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.CATALOG_CONFLICT, "That " + what + " already exists.");
        }
    }

    private Map<String, Object> one(String sql, long id, String what) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, Map.of("id", id));
        if (rows.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "No such " + what + ".");
        }
        return rows.get(0);
    }

    private void audit(String action, String targetType, long targetId, long admin, String ip, Object before, Object after) {
        try {
            audit.recordAdminAction(admin, action, targetType, targetId, before == null ? "{}" : mapper.writeValueAsString(before),
                    mapper.writeValueAsString(after), ip);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("audit serialisation failed", e);
        }
    }

    private static String generationNote(String what) {
        return "A new " + what + " takes part in routing only after the next generation is built and activated.";
    }

    private static void requireCode(String v, String field) {
        if (v == null || !CODE.matcher(v).matches()) {
            throw bad("'" + field + "' must be 2-64 characters: letters, digits, '_' or '-'.");
        }
    }

    private static void requireText(String v, String field, int max) {
        if (v == null || v.isBlank() || v.length() > max) {
            throw bad("'" + field + "' is required (at most " + max + " characters).");
        }
    }

    private static ApiException bad(String message) {
        return new ApiException(ErrorCode.VALIDATION_ERROR, message);
    }
}
