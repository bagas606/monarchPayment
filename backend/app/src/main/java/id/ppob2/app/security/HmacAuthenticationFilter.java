package id.ppob2.app.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.ppob2.partner.domain.ApiClient;
import id.ppob2.partner.repository.ApiClientRepository;
import id.ppob2.sharedkernel.channel.ChannelContext;
import id.ppob2.sharedkernel.channel.ChannelContextHolder;
import id.ppob2.sharedkernel.error.ErrorCode;
import id.ppob2.sharedkernel.error.ErrorResponse;
import id.ppob2.sharedkernel.security.HmacSigner;
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

    private static final Duration SIGNATURE_WINDOW = Duration.ofMinutes(5);

    private final ApiClientRepository apiClientRepository;
    private final ChannelContextResolver channelContextResolver;
    private final NonceStore nonceStore;
    private final ObjectMapper objectMapper;

    public HmacAuthenticationFilter(ApiClientRepository apiClientRepository,
                                     ChannelContextResolver channelContextResolver,
                                     NonceStore nonceStore,
                                     ObjectMapper objectMapper) {
        this.apiClientRepository = apiClientRepository;
        this.channelContextResolver = channelContextResolver;
        this.nonceStore = nonceStore;
        this.objectMapper = objectMapper;
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

        // NOTE: PRD Section 22.3 names this column `secret_hash` and says the plaintext secret
        // is "never stored", but HMAC verification requires the server to reproduce the MAC with
        // the same secret the client used — a one-way hash cannot do that. Treating the stored
        // value as the verification secret here is a placeholder; production must clarify with
        // the PRD owner whether this is reversibly encrypted (e.g. via KMS) rather than hashed,
        // per Section 73.3's open-assumptions list.
        if (!HmacSigner.matches(apiClient.getSecretHash(), stringToSign, signature)) {
            throw new AuthRejected(ErrorCode.SIGNATURE_INVALID, "HMAC signature verification failed.");
        }

        // Nonce is only consumed once the signature is proven genuine — otherwise an attacker
        // with no valid secret could burn arbitrary nonces, or a client retrying after a failed
        // signature could be locked out of reusing the same nonce on its next legitimate attempt.
        if (!nonceStore.registerIfAbsent(clientId, nonce, SIGNATURE_WINDOW)) {
            throw new AuthRejected(ErrorCode.SIGNATURE_INVALID, "Duplicate nonce.");
        }

        ChannelContext context = channelContextResolver.resolve(apiClient);
        ChannelContextHolder.set(context);
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
        if (drift.compareTo(SIGNATURE_WINDOW) > 0) {
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
