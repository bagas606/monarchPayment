package id.ppob2.payment.gateway.ayolinx;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.PrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * doc.ayolinx.id/api-255569332 (Access Token B2B). Caches the token in memory — {@code
 * expiresIn} is a few thousand seconds, and a scheduled refresher or distributed cache would be
 * over-engineering for a single-instance token with a cheap re-fetch path. Not thread-pool-safe
 * under heavy concurrent expiry races beyond the {@code synchronized} guard below, which is an
 * acceptable trade for this call volume (one token fetch per ~hour, not per request).
 */
@Component
@ConditionalOnProperty(name = "ppob2.payment.gateway", havingValue = "ayolinx")
class AyolinxTokenService {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Duration EXPIRY_SAFETY_MARGIN = Duration.ofSeconds(30);
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    private static final ZoneId ZONE = ZoneId.of("Asia/Jakarta");

    private final WebClient webClient = WebClient.builder().build();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String baseUrl;
    private final String clientKey;
    private final PrivateKey privateKey;

    private volatile String cachedToken;
    private volatile Instant cachedExpiry = Instant.EPOCH;

    AyolinxTokenService(@Value("${ppob2.payment.ayolinx.base-url}") String baseUrl,
                         @Value("${ppob2.payment.ayolinx.client-key}") String clientKey,
                         @Value("${ppob2.payment.ayolinx.private-key-pem:}") String privateKeyPem) {
        this.baseUrl = baseUrl;
        this.clientKey = clientKey;
        this.privateKey = privateKeyPem.isBlank() ? null : AyolinxSigner.loadPrivateKey(privateKeyPem);
    }

    synchronized String getAccessToken() {
        Instant now = Instant.now();
        if (cachedToken != null && now.isBefore(cachedExpiry)) {
            return cachedToken;
        }
        if (privateKey == null) {
            throw new IllegalStateException(
                    "ppob2.payment.ayolinx.private-key-pem is not configured — cannot sign the B2B token request");
        }

        String timestamp = OffsetDateTime.now(ZONE).format(TIMESTAMP_FORMAT);
        String signature = AyolinxSigner.signAccessTokenRequest(privateKey, clientKey, timestamp);

        String responseBody = webClient.post()
                .uri(baseUrl + "/v1.0/access-token/b2b")
                .header("Content-Type", "application/json")
                .header("X-TIMESTAMP", timestamp)
                .header("X-CLIENT-KEY", clientKey)
                .header("X-SIGNATURE", signature)
                .bodyValue("{\"grantType\":\"client_credentials\"}")
                .retrieve()
                .bodyToMono(String.class)
                .block(TIMEOUT);

        TokenResponse response;
        try {
            response = objectMapper.readValue(responseBody, TokenResponse.class);
        } catch (Exception e) {
            throw new IllegalStateException("Malformed Ayolinx B2B token response: " + responseBody, e);
        }
        if (response.accessToken() == null || response.accessToken().isBlank()) {
            throw new IllegalStateException("Ayolinx B2B token response had no accessToken: " + responseBody);
        }

        long expiresInSeconds = Long.parseLong(response.expiresIn());
        this.cachedToken = response.accessToken();
        this.cachedExpiry = now.plusSeconds(expiresInSeconds).minus(EXPIRY_SAFETY_MARGIN);
        return cachedToken;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenResponse(String responseCode, String responseMessage, String accessToken,
                                  String tokenType, String expiresIn) {
    }
}
