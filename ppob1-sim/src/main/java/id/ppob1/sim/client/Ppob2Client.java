package id.ppob1.sim.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * PPOB1's client for PPOB2's Open API (PRD Section 23.3–23.7), written the way a reseller would
 * write it: against the published contract, over HTTP, with no access to PPOB2's internals.
 *
 * <p><b>Every response is returned, not thrown on.</b> {@code 4xx} answers from this API are
 * routine business outcomes — {@code 422 UNSUPPORTED_AMOUNT}, {@code 409
 * IDEMPOTENCY_KEY_CONFLICT}, {@code 409 ORDER_NOT_CANCELLABLE} — and PPOB1 has to record them
 * against the transaction, so turning them into exceptions would mean catching an exception to
 * reach the normal path. The one thing that <em>is</em> surfaced as a failure is not getting an
 * answer at all (timeout, connection refused), because that is the case where PPOB1 genuinely does
 * not know what happened upstream and must leave the transaction for reconciliation.
 *
 * <p><b>Timeouts are short and explicit.</b> A reseller blocking indefinitely on an upstream call
 * is how a retail till stops serving customers. The default here is well under a human's patience
 * and the resulting {@link Ppob2Response#noAnswer} is a recoverable state, not a lost sale — the
 * {@code reseller_txn_id} doubles as the Idempotency-Key precisely so that re-submitting after a
 * timeout cannot double-charge.
 */
@Component
public class Ppob2Client {

    private static final Logger log = LoggerFactory.getLogger(Ppob2Client.class);

    private final Ppob2RequestSigner signer;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final String baseUrl;
    private final String clientId;
    private final String clientSecret;
    private final String callbackUrl;

    public Ppob2Client(Ppob2RequestSigner signer,
                        ObjectMapper objectMapper,
                        @Value("${ppob1.ppob2.base-url}") String baseUrl,
                        @Value("${ppob1.ppob2.client-id}") String clientId,
                        @Value("${ppob1.ppob2.client-secret}") String clientSecret,
                        @Value("${ppob1.callback-url}") String callbackUrl,
                        @Value("${ppob1.ppob2.connect-timeout-ms:3000}") int connectTimeoutMs,
                        @Value("${ppob1.ppob2.read-timeout-ms:10000}") int readTimeoutMs) {
        this.signer = signer;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.callbackUrl = callbackUrl;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    /** Section 23.3. The only endpoint with a query string, and so the only one that would break
     * if the signature covered it — see {@link Ppob2RequestSigner}. */
    public Ppob2Response supportedAmounts(String productCode) {
        return call("GET", "/api/v1/config/supported-amounts",
                "?product_code=" + productCode, null, null);
    }

    /**
     * Section 23.4. {@code resellerTxnId} is used both as the {@code Idempotency-Key} and as
     * {@code metadata.reseller_txn_id}: the header makes a retry safe upstream, and the metadata
     * field is what lets a human on PPOB2's side tie an order back to PPOB1's own reference
     * without PPOB1 having to be asked.
     *
     * <p>{@code callback_url} is sent on every order. PPOB2 only delivers a webhook when the order
     * carries one, so omitting it would silently turn the whole integration into polling.
     */
    public Ppob2Response createOrder(String resellerTxnId, String productCode, long amount, String customerReference) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("product_code", productCode);
        body.put("parent_amount", amount);
        body.put("customer_reference", customerReference);
        body.put("callback_url", callbackUrl);
        body.put("metadata", Map.of("reseller_txn_id", resellerTxnId));

        String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize create-order body", e);
        }
        return call("POST", "/api/v1/orders", "", json, resellerTxnId);
    }

    /** Section 23.5. */
    public Ppob2Response getOrder(String orderId) {
        return call("GET", "/api/v1/orders/" + orderId, "", null, null);
    }

    /** Section 23.6. */
    public Ppob2Response getPayment(String orderId) {
        return call("GET", "/api/v1/orders/" + orderId + "/payment", "", null, null);
    }

    /** Section 23.7. */
    public Ppob2Response cancelOrder(String orderId) {
        // Signed over an empty body, whose SHA-256 is the well-known hash of the empty string --
        // not omitted. PPOB2 hashes whatever body arrived, and for a bodyless POST that is "".
        return call("POST", "/api/v1/orders/" + orderId + "/cancel", "", "", "cancel-" + orderId);
    }

    /**
     * @param pathForSignature the request path WITHOUT the query string — PPOB2 signs
     *     {@code getRequestURI()}. Passing the query separately is what makes that impossible to
     *     get wrong at a call site.
     */
    private Ppob2Response call(String method, String pathForSignature, String query,
                                String body, String idempotencyKey) {
        String timestamp = String.valueOf(Instant.now().toEpochMilli());
        String nonce = signer.newNonce();
        String payload = body == null ? "" : body;
        String bodyHash = signer.sha256Hex(payload);
        String signature = signer.sign(clientSecret,
                signer.stringToSign(method, pathForSignature, timestamp, nonce, bodyHash));

        URI uri = URI.create(baseUrl + pathForSignature + query);

        try {
            RestClient.RequestBodySpec request = restClient.method(
                            org.springframework.http.HttpMethod.valueOf(method))
                    .uri(uri)
                    .header("X-Client-Id", clientId)
                    .header("X-Timestamp", timestamp)
                    .header("X-Nonce", nonce)
                    .header("X-Signature", signature);

            if (idempotencyKey != null) {
                request.header("Idempotency-Key", idempotencyKey);
            }
            if (body != null) {
                request.contentType(MediaType.APPLICATION_JSON).body(body);
            }

            // onStatus(status -> true, noop) disables RestClient's default 4xx/5xx exception
            // handling: see the class Javadoc -- a 422 is a business answer PPOB1 must record,
            // not an exception.
            var response = request.retrieve()
                    .onStatus(status -> true, (req, res) -> { })
                    .toEntity(String.class);

            HttpStatusCode status = response.getStatusCode();
            return new Ppob2Response(status.value(), response.getBody(), null);
        } catch (Exception e) {
            // No answer. Distinct from an error answer: PPOB1 does not know whether PPOB2 acted.
            log.warn("No answer from PPOB2 for {} {}: {}", method, pathForSignature, e.toString());
            return Ppob2Response.noAnswer(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
