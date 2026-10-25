package id.ppob2.app.web;

import id.ppob2.admin.security.AdminPrincipal;
import id.ppob2.audit.AuditService;
import id.ppob2.configuration.ConfigKey;
import id.ppob2.configuration.ConfigurationService;
import id.ppob2.sharedkernel.error.ApiException;
import id.ppob2.sharedkernel.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Section 41.11: the configuration values an admin may change without a deployment. Only keys in {@link ConfigKey} exist, each
 * with a type and range; an unknown key is a 404, not a new setting. Weights and the fee take effect at the next
 * (re-)score, not retroactively; the response says so rather than letting an admin assume prices moved instantly.
 */
@RestController
public class AdminConfigurationController {

    private final ConfigurationService config;
    private final AuditService auditService;

    public AdminConfigurationController(ConfigurationService config, AuditService auditService) {
        this.config = config;
        this.auditService = auditService;
    }

    @GetMapping("/admin/configuration")
    @PreAuthorize("hasAuthority('catalog:view')")
    public ResponseEntity<?> list() {
        List<Map<String, Object>> items = new ArrayList<>();
        config.all().forEach((k, v) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", k.key());
            m.put("type", k.type().name());
            m.put("value", v.value());
            m.put("default", k.defaultValue());
            m.put("is_default", v.isDefault());
            m.put("min", k.min());
            m.put("max", k.max());
            m.put("description", k.description());
            items.add(m);
        });
        return ResponseEntity.ok(Map.of("items", items));
    }

    @PutMapping("/admin/configuration/{key}")
    @PreAuthorize("hasAuthority('config:edit')")
    public ResponseEntity<?> set(@PathVariable("key") String key, @RequestBody(required = false) ValueBody body,
                                 @AuthenticationPrincipal AdminPrincipal principal, HttpServletRequest request) {
        ConfigKey k = ConfigKey.byKey(key).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Unknown configuration key."));
        if (body == null || body.value() == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "A 'value' is required.");
        }
        BigDecimal[] change;
        try {
            change = config.set(k, body.value(), principal.getAdminUserId());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, e.getMessage());
        }
        auditService.recordAdminAction(principal.getAdminUserId(), "CONFIGURATION_CHANGE", "CONFIGURATION", null,
                "{\"key\":\"" + k.key() + "\",\"value\":" + change[0].toPlainString() + "}",
                "{\"key\":\"" + k.key() + "\",\"value\":" + change[1].toPlainString() + "}", request.getRemoteAddr());
        return ResponseEntity.ok(Map.of("key", k.key(), "previous", change[0], "value", change[1],
                "note", "Takes effect at the next score or generation, not retroactively."));
    }

    public record ValueBody(BigDecimal value) {
    }
}
