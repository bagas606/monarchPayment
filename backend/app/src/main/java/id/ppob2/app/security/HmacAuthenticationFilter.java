package id.ppob2.app.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.ppob2.partner.domain.ApiClient;
import id.ppob2.partner.repository.ApiClientRepository;
import id.ppob2.sharedkernel.channel.ChannelContext;
import id.ppob2.sharedkernel.channel.ChannelContextHolder;
import id.ppob2.sharedkernel.error.ErrorCode;
import id.ppob2.sharedkernel.error.ErrorResponse;
import id.ppob2.sharedkernel.security.HmacSigner;
import id.ppob2.sharedkernel.security.SecretVault;
import id.ppob2.sharedkernel.security.SecretVaultException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Verifies Client Credential + HMAC-SHA256 signature per PRD Section 23.2 for every
 * {@code /api/v1/**} request, and resolves + publishes the {@link ChannelContext} for the
 * duration of the request. Runs before the security filter chain's authorization decision.
 */
@Component
@Order(1)
public class HmacAuthenticationFilter extends OncePerRequestFilter {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(HmacAuthenticationFilter.class);

    private final Duration signatureWindow;
    private final ApiClientRepository apiClientRepository;
    private final ChannelContextResolver channelContextResolver;
    private final NonceStore nonceStore;
    private final ObjectMapper objectMapper;
    private final SecretVault secretVault;

    /**
     * {@code signature-window-ms} was already declared in {@code application.yml} as "5 minutes,
     * PRD Section 23.2" but nothing read it — the window was a hardcoded constant here, so the
     * property was dead config that looked adjustable and silently wasn't. Bound properly rather
     * than deleting the property, since Section 23.2 states the window as a requirement and an
     * operator needs to be able to widen it if partner clock skew ever demands it.
     */
    public HmacAuthenticationFilter(ApiClientRepository apiClientRepository,
                                     ChannelContextResolver channelContextResolver,
                                     NonceStore nonceStore,
                                     ObjectMapper objectMapper,
                                     SecretVault secretVault,
                                     @org.springframework.beans.factory.annotation.Value(
                                             "${ppob2.security.signature-window-ms:300000}") long signatureWindowMs) {
        this.signatureWindow = Duration.ofMillis(signatureWindowMs);
        this.apiClientRepository = apiClientRepository;
        this.channelContextResolver = channelContextResolver;
        this.nonceStore = nonceStore;
        this.objectMapper = objectMapper;
        this.secretVault = secretVault;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CachedBodyHttpServletRequest cachedRequest = new CachedBodyHttpServletRequest(request);

        try {
            authenticate(cachedRequest);
            chain.doFilter(cachedRequest, response);
        } catch (AuthRejected rejected) {
            writeError(response, rejected.errorCode, rejected.message, request);
        } catch (DataAccessException datastoreDown) {
            // This filter's `api_client` lookup is the FIRST datastore touch of every /api/v1/**
            // request, so a database outage fails here — before DispatcherServlet is ever entered,
            // where no @RestControllerAdvice can see it. Confirmed for real on 2026-09-27 by
            // stopping Postgres under a running app: the request returned Spring Boot's DEFAULT
            // error body ({"timestamp":...,"status":500,...}), not this codebase's Section 50.1
            // envelope, so a partner parsing `error_code` got nothing at all to branch on, and the
            // 500 told them the request was un-retryable when in fact retrying was exactly right.
            // Reported as 503 for the same reason as GlobalExceptionHandler's own handler.
            log.error("Datastore unavailable authenticating {} {}: {}",
                    request.getMethod(), request.getRequestURI(), datastoreDown.toString());
            writeError(response, ErrorCode.SERVICE_UNAVAILABLE, "Service temporarily unavailable, retry later.", request);
        } finally {
            ChannelContextHolder.clear();
        }
    }

    private void authenticate(HttpServletRequest request) {
        String clientId = header(request, "X-Client-Id");
        String timestamp = header(request, "X-Timestamp");
        String nonce = header(request, "X-Nonce");
        String signature = header(request, "X-Signature");

        if (clientId == null || timestamp == null || nonce == null || signature == null) {
            throw new AuthRejected(ErrorCode.VALIDATION_ERROR, "Missing required authentication headers.");
        }

        checkTimestampWindow(timestamp);

        ApiClient apiClient = apiClientRepository.findByClientId(clientId)
                .orElseThrow(() -> new AuthRejected(ErrorCode.SIGNATURE_INVALID, "Unknown client."));

        if (!apiClient.isActive()) {
            throw new AuthRejected(ErrorCode.CLIENT_SUSPENDED, "Client is not active.");
        }

        String bodyHash = HmacSigner.sha256Hex(bodyAsString(request));
        String stringToSign = HmacSigner.buildStringToSign(
                request.getMethod(), request.getRequestURI(), timestamp, nonce, bodyHash);

        // PRD Section 22.3 calls this column a hash, but HMAC verification needs the secret itself, so it
        // is reversibly encrypted (SecretVault) rather than hashed. The row identity is the AAD, so a
        // ciphertext copied from another client does not decrypt here. An unprovisioned, plaintext or
        // undecryptable value is never used as a secret: the request is refused like any bad signature
        // (the partner is not told which), and the cause is logged for the operator.
        String secret = clientSecret(apiClient);
        if (secret == null || !HmacSigner.matches(secret, stringToSign, signature)) {
            throw new AuthRejected(ErrorCode.SIGNATURE_INVALID, "HMAC signature verification failed.");
        }

        // Nonce is only consumed once the signature is proven genuine — otherwise an attacker
        // with no valid secret could burn arbitrary nonces, or a client retrying after a failed
        // signature could be locked out of reusing the same nonce on its next legitimate attempt.
        if (!nonceStore.registerIfAbsent(clientId, nonce, signatureWindow)) {
            throw new AuthRejected(ErrorCode.SIGNATURE_INVALID, "Duplicate nonce.");
        }

        ChannelContext context = channelContextResolver.resolve(apiClient);
        ChannelContextHolder.set(context);
    }

    /** AAD for a client secret; the provisioning endpoint must use the same string. */
    public static String secretAad(String clientId) {
        return "api_client:" + clientId;
    }

    private String clientSecret(ApiClient apiClient) {
        String stored = apiClient.getSecretCiphertext();
        if (stored == null || stored.isBlank()) {
            log.error("api_client '{}' has no provisioned secret; refusing its requests", apiClient.getClientId());
            return null;
        }
        try {
            return secretVault.decrypt(stored, secretAad(apiClient.getClientId()));
        } catch (SecretVaultException e) {
            log.error("api_client '{}' secret cannot be decrypted ({}); refusing its requests",
                    apiClient.getClientId(), e.getMessage());
            return null;
        }
    }

    private void checkTimestampWindow(String timestampHeader) {
        long timestampMs;
        try {
            timestampMs = Long.parseLong(timestampHeader);
        } catch (NumberFormatException e) {
            throw new AuthRejected(ErrorCode.VALIDATION_ERROR, "X-Timestamp must be epoch milliseconds.");
        }
        Instant requestTime = Instant.ofEpochMilli(timestampMs);
        Duration drift = Duration.between(requestTime, Instant.now()).abs();
        if (drift.compareTo(signatureWindow) > 0) {
            throw new AuthRejected(ErrorCode.TIMESTAMP_OUT_OF_RANGE, "Request timestamp outside the allowed window.");
        }
    }

    private String bodyAsString(HttpServletRequest request) throws AuthRejected {
        if (!(request instanceof CachedBodyHttpServletRequest cached)) {
            throw new AuthRejected(ErrorCode.INTERNAL_ERROR, "Request body was not buffered for signing.");
        }
        return new String(cached.getCachedBody(), StandardCharsets.UTF_8);
    }

    private String header(HttpServletRequest request, String name) {
        return Optional.ofNullable(request.getHeader(name)).filter(v -> !v.isBlank()).orElse(null);
    }

    private void writeError(HttpServletResponse response, ErrorCode errorCode, String message, HttpServletRequest request)
            throws IOException {
        String correlationId = Optional.ofNullable(request.getHeader("X-Correlation-Id"))
                .orElse(java.util.UUID.randomUUID().toString());
        response.setStatus(errorCode.httpStatus());
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ErrorResponse.of(errorCode, message, correlationId));
    }

    private static final class AuthRejected extends RuntimeException {
        private final ErrorCode errorCode;
        private final String message;

        private AuthRejected(ErrorCode errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
            this.message = message;
        }
    }
}
