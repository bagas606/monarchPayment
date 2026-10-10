package id.ppob1.sim.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pins {@link Ppob2RequestSigner} against vectors computed <b>outside Java</b>, with
 * {@code openssl dgst}:
 * <pre>
 * EMPTY=$(printf '' | openssl dgst -sha256 -hex | sed 's/^.*= //')
 * printf 'GET\n/api/v1/config/supported-amounts\n1700000000000\nabc123\n%s' "$EMPTY" \
 *   | openssl dgst -sha256 -hmac 'dev-secret' -hex
 * </pre>
 *
 * <p>That provenance is the whole value of this file. A test that asserted {@code sign(...)} equals
 * {@code sign(...)}, or that compared against PPOB2's own {@code HmacSigner}, would pass no matter
 * what either implementation did — it would only prove the two agree, not that either matches the
 * scheme a third-party reseller would implement from PRD Section 23.2. These constants were
 * produced by a tool that has never seen this codebase, so if a future change to the scheme on
 * either side is not deliberate, this is what fails.
 */
class Ppob2RequestSignerTest {

    private static final String SECRET = "dev-secret";
    private static final String TIMESTAMP = "1700000000000";
    private static final String EMPTY_BODY_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private final Ppob2RequestSigner signer = new Ppob2RequestSigner();

    @Test
    void sha256OfTheEmptyBodyIsTheWellKnownValue() {
        // A bodyless GET/POST still contributes a hash: PPOB2 hashes whatever arrived, and for no
        // body that is the empty string. Omitting the component entirely is the classic way to get
        // a 401 on every GET while POSTs work.
        assertThat(signer.sha256Hex("")).isEqualTo(EMPTY_BODY_SHA256);
    }

    @Test
    void stringToSignIsNewlineSeparatedInSpecOrder() {
        assertThat(signer.stringToSign("GET", "/api/v1/orders/ORD-1", TIMESTAMP, "abc123", EMPTY_BODY_SHA256))
                .isEqualTo("GET\n/api/v1/orders/ORD-1\n" + TIMESTAMP + "\nabc123\n" + EMPTY_BODY_SHA256);
    }

    @Test
    void signsABodylessGetToTheOpensslComputedVector() {
        String signature = signer.sign(SECRET, signer.stringToSign(
                "GET", "/api/v1/config/supported-amounts", TIMESTAMP, "abc123", signer.sha256Hex("")));

        assertThat(signature).isEqualTo("5dfc145d5d5a43903d6d650c66d9a9213d9a41af6a6e4f1ae0d5e7accf9c854d");
    }

    @Test
    void signsAJsonPostToTheOpensslComputedVector() {
        String body = "{\"product_code\":\"MOBILE_LEGENDS\",\"parent_amount\":20000}";

        assertThat(signer.sha256Hex(body))
                .isEqualTo("ff5227ae6c9aff353bd40915c907e81736a705b8f4ce1e73f8ab5a61eb8c6551");
        assertThat(signer.sign(SECRET, signer.stringToSign(
                "POST", "/api/v1/orders", TIMESTAMP, "nonce-1", signer.sha256Hex(body))))
                .isEqualTo("5a298807a54fdb5beae1b569dc913f5e1dacd8656c8029e668a44de6b42c4b42");
    }

    @Test
    void signatureIsLowercaseHexAndNotBase64() {
        // Section 23.2 says "hex or base64". PPOB2 accepts hex only, and picking the other branch
        // yields SIGNATURE_INVALID -- indistinguishable from a wrong secret. Asserted so the
        // ambiguity is resolved in code rather than rediscovered against a 401.
        String signature = signer.sign(SECRET, "anything");

        assertThat(signature).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void theQueryStringIsNotPartOfTheSignedPath() {
        // PPOB2 signs HttpServletRequest.getRequestURI(), which stops at the '?'. Signing the full
        // URL fails on exactly one endpoint -- the only one with a query parameter -- so it reads
        // as that endpoint being broken rather than as a signing rule.
        String signedWithoutQuery = signer.sign(SECRET, signer.stringToSign(
                "GET", "/api/v1/config/supported-amounts", TIMESTAMP, "abc123", EMPTY_BODY_SHA256));
        String signedWithQuery = signer.sign(SECRET, signer.stringToSign(
                "GET", "/api/v1/config/supported-amounts?product_code=MOBILE_LEGENDS",
                TIMESTAMP, "abc123", EMPTY_BODY_SHA256));

        assertThat(signedWithoutQuery)
                .isEqualTo("5dfc145d5d5a43903d6d650c66d9a9213d9a41af6a6e4f1ae0d5e7accf9c854d")
                .isNotEqualTo(signedWithQuery);
    }

    @Test
    void verifiesAnInboundWebhookSignedWithTheWebhookSecret() {
        // One scheme, two keys (Section 23.8). This vector uses the webhook secret and the
        // callback path, and is the shape PPOB2's OutboundWebhookSender actually produces.
        String body = "{\"event_type\":\"ORDER_STATUS_CHANGED\",\"order_id\":\"ORD-1\","
                + "\"state\":\"SUCCESS\",\"timestamp\":\"2026-10-10T00:00:00Z\",\"nonce\":\"wh-nonce-1\"}";
        String stringToSign = signer.stringToSign("POST", "/webhooks/ppob2", TIMESTAMP,
                "wh-nonce-1", signer.sha256Hex(body));

        assertThat(signer.matches("ppob1-webhook-secret", stringToSign,
                "93a5a0400179f77ffe8fcfd04df455ca0be678e181c3bb432db1dc9900f95d1b")).isTrue();
    }

    @Test
    void rejectsTheRightSignatureUnderTheWrongSecret() {
        String stringToSign = signer.stringToSign("POST", "/webhooks/ppob2", TIMESTAMP,
                "wh-nonce-1", signer.sha256Hex("{}"));

        assertThat(signer.matches("not-the-secret", stringToSign, signer.sign("ppob1-webhook-secret", stringToSign)))
                .isFalse();
    }

    @Test
    void treatsAMissingSignatureAsAFailureRatherThanThrowing() {
        // An unsigned request must be recordable as a verification failure. Throwing here would
        // skip the very row that proves someone tried.
        assertThat(signer.matches("secret", "anything", null)).isFalse();
    }

    @Test
    void noncesAreUniqueAnd32HexChars() {
        assertThat(signer.newNonce()).hasSize(32).matches("[0-9a-f]{32}");
        assertThat(signer.newNonce()).isNotEqualTo(signer.newNonce());
    }
}
