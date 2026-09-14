package id.ppob2.payment.gateway.ayolinx;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.ppob2.payment.gateway.PaymentCreateRequest;
import id.ppob2.payment.gateway.PaymentCreateResult;
import id.ppob2.payment.gateway.PaymentGateway;
import id.ppob2.payment.gateway.PaymentInquiryResult;
import id.ppob2.payment.gateway.RefundRequest;
import id.ppob2.payment.gateway.RefundResult;
import java.security.PublicKey;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Real integration against doc.ayolinx.id's public QRIS API (Generate/Query/Cancel QRIS,
 * Access Token B2B — see backend/README.md for the endpoints consulted). Originally built entirely
 * from that public documentation with no sandbox credential to round-trip it against — sandbox
 * merchant registration turned out to need no KYB at all (self-service signup at
 * merchant.ayolinx.id), and {@code createDynamicQris} has since been confirmed against the real
 * sandbox: a genuine EMV/QRIS payload carrying real BNC acquirer data came back for a real
 * {@code POST /api/v1/orders} call, with zero warnings across the RSA-signed token request and the
 * HMAC-SHA512-signed generate-QR request (see README's "Real sandbox round-trip" entry). The
 * inbound callback path is registered but not yet round-tripped by a real payment. Activated by setting
 * {@code ppob2.payment.gateway=ayolinx}; the default stays {@link
 * id.ppob2.payment.gateway.StubQrisPaymentGateway} ({@code stub}, dev default) — a
 * {@code @Profile} guard was deliberately replaced with this property so the real gateway can
 * actually be booted in dev to exercise its HTTP call path (and fail loudly there) rather than
 * never running until a `prod` profile flip.
 *
 * <h2>Reference identity — read before touching {@code pgReference}</h2>
 * Generate QRIS's response carries no Ayolinx-side reference (only {@code qrContent},
 * {@code partnerReferenceNo}, {@code expiredDate}), and both Query QRIS and Cancel QRIS key on
 * {@code originalPartnerReferenceNo} — i.e. on *our* reference, not theirs. So unlike the stub
 * (which invents a synthetic {@code STUB-<uuid>}), this gateway returns {@code request.orderNo()}
 * itself as {@code pgReference}. That is what ends up in {@code payment.pg_reference} despite the
 * column's PRD Section 22.17 comment calling it an "Ayolinx transaction ref" — it is our own
 * order number, chosen deliberately so {@link #inquire} and the inbound callback's {@code
 * findByPgReference} lookup both key on the same value Ayolinx round-trips back to us.
 *
 * <h2>Known unknowns — must be verified against the real contract before production use</h2>
 * <ul>
 *   <li>{@code expiredDate} in Generate QRIS's response is documented only as "QR expiration
 *       time as seconds timestamp", ambiguous against every other timestamp field's ISO-with-
 *       offset format elsewhere in the same API. Rather than parse it, this echoes back the
 *       caller's own requested {@code request.expiresAt()} — same as the stub already did.</li>
 *   <li>The inbound callback signature's {@code ROUTE} component (in
 *       {@code METHOD:ROUTE:SHA256(BODY):TIMESTAMP}) is not defined in the public docs found —
 *       neither "Callback description" (doc-5923355) nor the QRIS Payment Notify page state
 *       whether it is Ayolinx's documented notify path ({@code /v1/qr/qr-mpm-notify}) or our
 *       registered callback URL's own path. Made a config property
 *       ({@code ppob2.payment.ayolinx.callback-route}) defaulting to Ayolinx's documented path,
 *       rather than hardcoding a guess.</li>
 *   <li>No refund API appears in the public docs. Cancel QRIS ({@code qr-mpm-cancel}) voids an
 *       *unpaid* QR — not a refund of a settled payment (status {@code 04 Refunded} exists in the
 *       callback's status table, so the mechanism exists, just not documented as a callable
 *       endpoint here). {@link #refund} stays unimplemented rather than silently mapping onto
 *       cancel, which would be a different operation wearing this one's name.</li>
 *   <li>{@code X-EXTERNAL-ID} must be "numeric, unique within the same day" per the docs; this
 *       uses the current epoch millisecond as a cheap satisfier of both constraints.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "ppob2.payment.gateway", havingValue = "ayolinx")
public class AyolinxPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(AyolinxPaymentGateway.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private final WebClient webClient = WebClient.builder().build();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AyolinxTokenService tokenService;
    private final String baseUrl;
    private final String clientId;
    private final String clientSecret;
    private final String channel;
    private final String notificationUrl;
    private final String callbackRoute;
    private final ZoneId zone;
    private final PublicKey ayolinxPublicKey;

    AyolinxPaymentGateway(AyolinxTokenService tokenService,
                          @Value("${ppob2.payment.ayolinx.base-url}") String baseUrl,
                          @Value("${ppob2.payment.ayolinx.client-key}") String clientId,
                          @Value("${ppob2.payment.ayolinx.client-secret}") String clientSecret,
                          @Value("${ppob2.payment.ayolinx.channel:BNC_QRIS}") String channel,
                          @Value("${ppob2.payment.ayolinx.notification-url:}") String notificationUrl,
                          @Value("${ppob2.payment.ayolinx.callback-route:/v1/qr/qr-mpm-notify}") String callbackRoute,
                          @Value("${ppob2.payment.ayolinx.timezone:Asia/Jakarta}") String timezone,
                          @Value("${ppob2.payment.ayolinx.public-key-pem:}") String publicKeyPem) {
        this.tokenService = tokenService;
        this.baseUrl = baseUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.channel = channel;
        this.notificationUrl = notificationUrl;
        this.callbackRoute = callbackRoute;
        this.zone = ZoneId.of(timezone);
        this.ayolinxPublicKey = publicKeyPem.isBlank() ? null : AyolinxSigner.loadPublicKey(publicKeyPem);
    }

    @Override
    public PaymentCreateResult createDynamicQris(PaymentCreateRequest request) {
        String path = "/v1.0/qr/qr-mpm-generate";
        String body = writeJson(Map.of(
                "partnerReferenceNo", request.orderNo(),
                "amount", Map.of("currency", "IDR", "value", rupiahToAyolinxAmount(request.amount())),
                "additionalInfo", Map.of("channel", channel),
                "urlParams", notificationUrl.isBlank() ? List.of() : List.of(Map.of("type", "NOTIFICATION", "url", notificationUrl)),
                "validUpTo", OffsetDateTime.ofInstant(request.expiresAt(), zone).format(TIMESTAMP_FORMAT)
        ));

        try {
            String responseBody = post(path, body);
            GenerateQrisResponse response = objectMapper.readValue(responseBody, GenerateQrisResponse.class);
            if (!isSuccessCode(response.responseCode())) {
                return PaymentCreateResult.failure(response.responseCode() + ": " + response.responseMessage());
            }
            // See class Javadoc: pgReference is our own orderNo, not an Ayolinx-issued reference,
            // and expiresAt is echoed back rather than parsed from the ambiguous `expiredDate`.
            return PaymentCreateResult.success(request.orderNo(), response.qrContent(), request.expiresAt());
        } catch (Exception e) {
            log.warn("Ayolinx createDynamicQris failed for order {}: {}", request.orderNo(), e.toString());
            return PaymentCreateResult.failure(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    @Override
    public PaymentInquiryResult inquire(String pgReference) {
        String path = "/v1.0/qr/qr-mpm-query";
        String body = writeJson(Map.of(
                "originalPartnerReferenceNo", pgReference,
                "additionalInfo", Map.of("channel", channel)
        ));

        String responseBody = post(path, body);
        QueryQrisResponse response;
        try {
            response = objectMapper.readValue(responseBody, QueryQrisResponse.class);
        } catch (Exception e) {
            throw new IllegalStateException("Malformed Ayolinx qr-mpm-query response: " + responseBody, e);
        }
        return new PaymentInquiryResult(pgReference, mapStatusCode(response.latestTransactionStatus()),
                parseTimestamp(response.finishedTime()));
    }

    /** No refund endpoint exists in the public docs — see class Javadoc. */
    @Override
    public RefundResult refund(RefundRequest request) {
        throw new UnsupportedOperationException(
                "Ayolinx's public API has no refund endpoint; qr-mpm-cancel voids an unpaid QR, "
                        + "it does not refund a settled payment. Verify against the real contract "
                        + "(post-merchant-registration) before implementing this.");
    }

    /**
     * See class Javadoc's "Known unknowns" — {@code callbackRoute} is a best-effort default
     * ({@code /v1/qr/qr-mpm-notify}, Ayolinx's documented QRIS notify path), not a confirmed
     * value for what they actually sign against when calling *our* registered URL.
     */
    @Override
    public boolean verifyCallbackSignature(String rawBody, Map<String, String> headers) {
        if (ayolinxPublicKey == null) {
            log.error("ppob2.payment.ayolinx.public-key-pem is not configured — rejecting all Ayolinx callbacks");
            return false;
        }
        String signature = headers.get("X-SIGNATURE");
        String timestamp = headers.get("X-TIMESTAMP");
        if (signature == null || signature.isBlank() || timestamp == null || timestamp.isBlank()) {
            return false;
        }
        return AyolinxSigner.verifyCallback(ayolinxPublicKey, "POST", callbackRoute, rawBody, timestamp, signature);
    }

    private String post(String path, String body) {
        String accessToken = tokenService.getAccessToken();
        String timestamp = OffsetDateTime.now(zone).format(TIMESTAMP_FORMAT);
        String signature = AyolinxSigner.signApiRequest(clientSecret, "POST", path, accessToken, body, timestamp);

        try {
            return webClient.post()
                    .uri(baseUrl + path)
                    .header("Content-Type", "application/json")
                    .header("X-TIMESTAMP", timestamp)
                    .header("X-SIGNATURE", signature)
                    .header("X-PARTNER-ID", clientId)
                    .header("X-EXTERNAL-ID", String.valueOf(System.currentTimeMillis()))
                    .header("Authorization", "Bearer " + accessToken)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(TIMEOUT);
        } catch (WebClientResponseException e) {
            throw new IllegalStateException("Ayolinx " + path + " returned HTTP " + e.getStatusCode() + ": " + e.getResponseBodyAsString(), e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize Ayolinx request body", e);
        }
    }

    /** Ayolinx amounts are decimal strings (SNAP convention); {@link id.ppob2.sharedkernel.money.Money}
     * is whole-Rupiah only (PRD Section 21.1), so this always emits ".00". */
    private static String rupiahToAyolinxAmount(id.ppob2.sharedkernel.money.Money amount) {
        return amount.toBigInteger() + ".00";
    }

    private static boolean isSuccessCode(String responseCode) {
        return responseCode != null && responseCode.startsWith("200");
    }

    /** doc-5923355's documented status table: 00 Success, 01 Initiated, 02 Paying, 03 Pending,
     * 04 Refunded, 05 Canceled, 06 Failed, 07 Not found. Returned as-is for {@link
     * PaymentInquiryResult#pgStatus()} since no caller currently switches on this value. */
    private static String mapStatusCode(String code) {
        return code;
    }

    private static java.time.Instant parseTimestamp(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return OffsetDateTime.parse(value).toInstant();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GenerateQrisResponse(String responseCode, String responseMessage, String qrContent,
                                         String partnerReferenceNo, String expiredDate) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record QueryQrisResponse(String responseCode, String responseMessage, String originalReferenceNo,
                                      String originalPartnerReferenceNo, String latestTransactionStatus,
                                      String finishedTime) {
    }
}
