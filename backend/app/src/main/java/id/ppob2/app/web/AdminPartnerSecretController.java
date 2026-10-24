package id.ppob2.app.web;

import id.ppob2.admin.security.AdminPrincipal;
import id.ppob2.app.security.HmacAuthenticationFilter;
import id.ppob2.app.webhook.WebhookDeliveryTargetResolver;
import id.ppob2.audit.AuditService;
import id.ppob2.partner.domain.ApiClient;
import id.ppob2.partner.domain.Partner;
import id.ppob2.partner.repository.ApiClientRepository;
import id.ppob2.partner.repository.PartnerRepository;
import id.ppob2.sharedkernel.error.ApiException;
import id.ppob2.sharedkernel.error.ErrorCode;
import id.ppob2.sharedkernel.security.SecretVault;
import jakarta.servlet.http.HttpServletRequest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The only way to put a partner secret into the database. Since V28 the stored value is ciphertext under
 * a key that lives outside the database, so SQL can no longer provision one.
 *
 * <p>Both endpoints take an optional {@code secret}. Omitted, a 256-bit random one is generated and returned
 * <em>once</em> in the response; supplied (the partner chose it, or a dev fixture needs a known value), it is
 * stored and not echoed. Either way the audit row records that the secret changed, the key id it was written
 * under and a SHA-256 fingerprint prefix, never the secret or its ciphertext. The fingerprint lets an operator
 * confirm which secret is live without being able to recover it.
 */
@RestController
public class AdminPartnerSecretController {

    private static final int MIN_SUPPLIED_LENGTH = 8;
    private static final int MAX_SUPPLIED_LENGTH = 200;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApiClientRepository apiClients;
    private final PartnerRepository partners;
    private final SecretVault vault;
    private final AuditService auditService;

    public AdminPartnerSecretController(ApiClientRepository apiClients, PartnerRepository partners,
                                        SecretVault vault, AuditService auditService) {
        this.apiClients = apiClients;
        this.partners = partners;
        this.vault = vault;
        this.auditService = auditService;
    }

    @PutMapping("/admin/api-clients/{clientId}/secret")
    @PreAuthorize("hasAuthority('partner_secret:manage')")
    @Transactional
    public ResponseEntity<?> setClientSecret(@PathVariable String clientId,
                                             @RequestBody(required = false) SecretBody body,
                                             @AuthenticationPrincipal AdminPrincipal principal,
                                             HttpServletRequest request) {
        ApiClient client = apiClients.findByClientId(clientId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No such api client."));
        boolean hadSecret = client.getSecretCiphertext() != null;
        Chosen chosen = choose(body);

        client.setSecretCiphertext(vault.encrypt(chosen.secret(), HmacAuthenticationFilter.secretAad(clientId)));
        apiClients.save(client);

        auditService.recordAdminAction(principal.getAdminUserId(), "API_CLIENT_SECRET_SET", "API_CLIENT", client.getId(),
                "{\"had_secret\":" + hadSecret + "}", afterState(chosen), request.getRemoteAddr());
        return response(chosen, "client_id", clientId);
    }

    @PutMapping("/admin/partners/{code}/webhook-secret")
    @PreAuthorize("hasAuthority('partner_secret:manage')")
    @Transactional
    public ResponseEntity<?> setWebhookSecret(@PathVariable String code,
                                              @RequestBody(required = false) SecretBody body,
                                              @AuthenticationPrincipal AdminPrincipal principal,
                                              HttpServletRequest request) {
        Partner partner = partners.findByCode(code)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No such partner."));
        boolean hadSecret = partner.getWebhookSecretCiphertext() != null;
        Chosen chosen = choose(body);

        partner.setWebhookSecretCiphertext(vault.encrypt(chosen.secret(), WebhookDeliveryTargetResolver.secretAad(code)));
        partners.save(partner);

        auditService.recordAdminAction(principal.getAdminUserId(), "PARTNER_WEBHOOK_SECRET_SET", "PARTNER", partner.getId(),
                "{\"had_secret\":" + hadSecret + "}", afterState(chosen), request.getRemoteAddr());
        return response(chosen, "partner_code", code);
    }

    private Chosen choose(SecretBody body) {
        if (body == null || body.secret() == null) {
            byte[] raw = new byte[32];
            RANDOM.nextBytes(raw);
            return new Chosen(Base64.getUrlEncoder().withoutPadding().encodeToString(raw), true);
        }
        String supplied = body.secret();
        if (supplied.length() < MIN_SUPPLIED_LENGTH || supplied.length() > MAX_SUPPLIED_LENGTH
                || !supplied.equals(supplied.strip())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "A supplied secret must be " + MIN_SUPPLIED_LENGTH + "-"
                    + MAX_SUPPLIED_LENGTH + " characters with no leading or trailing whitespace; omit it to have one generated.");
        }
        return new Chosen(supplied, false);
    }

    private String afterState(Chosen chosen) {
        return "{\"generated\":" + chosen.generated() + ",\"key_id\":\"" + vault.activeKeyId()
                + "\",\"fingerprint\":\"" + fingerprint(chosen.secret()) + "\"}";
    }

    private ResponseEntity<?> response(Chosen chosen, String idField, String idValue) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(idField, idValue);
        out.put("key_id", vault.activeKeyId());
        out.put("fingerprint", fingerprint(chosen.secret()));
        if (chosen.generated()) {
            out.put("secret", chosen.secret());
            out.put("note", "Shown once. It is stored encrypted and cannot be read back.");
        }
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(out);
    }

    /** First 8 hex chars of SHA-256: enough to tell two secrets apart, useless for recovering one. */
    private static String fingerprint(String secret) {
        return id.ppob2.sharedkernel.security.HmacSigner.sha256Hex(secret).substring(0, 8);
    }

    public record SecretBody(String secret) {
    }

    private record Chosen(String secret, boolean generated) {
    }
}
