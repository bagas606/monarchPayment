package id.ppob2.payment.gateway.ayolinx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.ppob2.payment.gateway.PaymentCreateRequest;
import id.ppob2.payment.gateway.PaymentCreateResult;
import id.ppob2.payment.gateway.RefundRequest;
import id.ppob2.sharedkernel.money.Money;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * No sandbox credential exists (see AyolinxPaymentGateway's Javadoc), so these point at an
 * unreachable local port instead of the real doc.ayolinx.id sandbox — the point isn't to prove
 * the sandbox integration works (that needs merchant registration first), it's to prove this
 * gateway *fails gracefully* (a returned failure result, not an uncaught exception bubbling into
 * PaymentService/OrderApplicationService) when the HTTP call can't succeed, same as any real
 * "PG unavailable" condition Section 25.2 describes.
 */
class AyolinxPaymentGatewayTest {

    private static final String UNREACHABLE_BASE_URL = "http://127.0.0.1:1";

    @Test
    void createDynamicQrisReturnsAFailureResultRatherThanThrowingWhenTheGatewayIsUnreachable() {
        AyolinxPaymentGateway gateway = gatewayWithUnreachableBaseUrl();

        PaymentCreateResult result = gateway.createDynamicQris(
                new PaymentCreateRequest("ORD-1", Money.of(15000L), Instant.now().plusSeconds(600)));

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).isNotBlank();
    }

    @Test
    void inquireDoesNotSilentlySwallowATransportFailure() {
        AyolinxPaymentGateway gateway = gatewayWithUnreachableBaseUrl();

        assertThatThrownBy(() -> gateway.inquire("ORD-1")).isInstanceOf(RuntimeException.class);
    }

    @Test
    void refundIsExplicitlyUnimplementedRatherThanSilentlyAliasedToCancel() {
        AyolinxPaymentGateway gateway = gatewayWithUnreachableBaseUrl();

        assertThatThrownBy(() -> gateway.refund(new RefundRequest("ORD-1", Money.of(15000L), "test")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void verifyCallbackSignatureRejectsEverythingWhenNoPublicKeyIsConfigured() {
        AyolinxPaymentGateway gateway = new AyolinxPaymentGateway(
                new AyolinxTokenService(UNREACHABLE_BASE_URL, "client-key", ""),
                UNREACHABLE_BASE_URL, "client-key", "client-secret", "BNC_QRIS", "", "/v1/qr/qr-mpm-notify",
                "Asia/Jakarta", "");

        assertThat(gateway.verifyCallbackSignature("{}", Map.of("X-SIGNATURE", "anything", "X-TIMESTAMP", "ts"))).isFalse();
    }

    @Test
    void verifyCallbackSignatureAcceptsAGenuineAyolinxSignedCallback() throws Exception {
        KeyPair keyPair = generateRsaKeyPair();
        String publicPem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(keyPair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
        AyolinxPaymentGateway gateway = new AyolinxPaymentGateway(
                new AyolinxTokenService(UNREACHABLE_BASE_URL, "client-key", ""),
                UNREACHABLE_BASE_URL, "client-key", "client-secret", "BNC_QRIS", "", "/v1/qr/qr-mpm-notify",
                "Asia/Jakarta", publicPem);

        String body = "{\"latestTransactionStatus\":\"00\"}";
        String timestamp = "2024-09-12T12:55:00+07:00";
        String signature = signFor(keyPair, body, timestamp);

        assertThat(gateway.verifyCallbackSignature(body, Map.of("X-SIGNATURE", signature, "X-TIMESTAMP", timestamp))).isTrue();
        assertThat(gateway.verifyCallbackSignature("{\"tampered\":true}", Map.of("X-SIGNATURE", signature, "X-TIMESTAMP", timestamp))).isFalse();
    }

    private static AyolinxPaymentGateway gatewayWithUnreachableBaseUrl() {
        return new AyolinxPaymentGateway(
                new AyolinxTokenService(UNREACHABLE_BASE_URL, "client-key", generatePrivateKeyPem()),
                UNREACHABLE_BASE_URL, "client-key", "client-secret", "BNC_QRIS", "", "/v1/qr/qr-mpm-notify",
                "Asia/Jakarta", "");
    }

    private static String generatePrivateKeyPem() {
        KeyPair keyPair = generateRsaKeyPair();
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
    }

    private static KeyPair generateRsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String signFor(KeyPair keyPair, String body, String timestamp) {
        try {
            String bodyHash = id.ppob2.sharedkernel.security.HmacSigner.sha256Hex(body);
            String stringToSign = "POST:/v1/qr/qr-mpm-notify:" + bodyHash + ":" + timestamp;
            java.security.Signature signature = java.security.Signature.getInstance("SHA256withRSA");
            signature.initSign(keyPair.getPrivate());
            signature.update(stringToSign.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
