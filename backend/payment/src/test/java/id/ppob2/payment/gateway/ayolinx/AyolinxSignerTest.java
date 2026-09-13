package id.ppob2.payment.gateway.ayolinx;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * No sandbox credential exists to verify these signatures against a real Ayolinx request/response
 * (see AyolinxPaymentGateway's Javadoc) — these are known-answer/round-trip tests against a
 * throwaway RSA keypair generated in-test, not a real credential.
 */
class AyolinxSignerTest {

    @Test
    void signApiRequestIsHmacSha512Base64OfTheDocumentedColonDelimitedString() {
        // Independently computed: HMAC-SHA512("secret", "POST:/v1.0/qr/qr-mpm-generate:tok:" + SHA256("body") + ":2024-09-12T12:55:00+07:00")
        String bodyHash = id.ppob2.sharedkernel.security.HmacSigner.sha256Hex("body");
        String stringToSign = "POST:/v1.0/qr/qr-mpm-generate:tok:" + bodyHash + ":2024-09-12T12:55:00+07:00";
        String expected = hmacSha512Base64("secret", stringToSign);

        String actual = AyolinxSigner.signApiRequest("secret", "POST", "/v1.0/qr/qr-mpm-generate", "tok", "body", "2024-09-12T12:55:00+07:00");

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void signApiRequestChangesWhenAnyComponentChanges() {
        String base = AyolinxSigner.signApiRequest("secret", "POST", "/path", "tok", "body", "ts");
        assertThat(AyolinxSigner.signApiRequest("other-secret", "POST", "/path", "tok", "body", "ts")).isNotEqualTo(base);
        assertThat(AyolinxSigner.signApiRequest("secret", "GET", "/path", "tok", "body", "ts")).isNotEqualTo(base);
        assertThat(AyolinxSigner.signApiRequest("secret", "POST", "/other", "tok", "body", "ts")).isNotEqualTo(base);
        assertThat(AyolinxSigner.signApiRequest("secret", "POST", "/path", "other-tok", "body", "ts")).isNotEqualTo(base);
        assertThat(AyolinxSigner.signApiRequest("secret", "POST", "/path", "tok", "other-body", "ts")).isNotEqualTo(base);
        assertThat(AyolinxSigner.signApiRequest("secret", "POST", "/path", "tok", "body", "other-ts")).isNotEqualTo(base);
    }

    @Test
    void accessTokenSignatureVerifiesAgainstItsOwnKeypairAndRejectsAWrongOne() throws Exception {
        KeyPair keyPair = generateRsaKeyPair();
        KeyPair otherKeyPair = generateRsaKeyPair();

        String signature = AyolinxSigner.signAccessTokenRequest(keyPair.getPrivate(), "clientKey", "2024-09-18T06:36:36+00:00");

        assertThat(verifyRsaSha256(keyPair.getPublic(), "clientKey|2024-09-18T06:36:36+00:00", signature)).isTrue();
        assertThat(verifyRsaSha256(otherKeyPair.getPublic(), "clientKey|2024-09-18T06:36:36+00:00", signature)).isFalse();
    }

    @Test
    void verifyCallbackAcceptsAGenuineSignatureAndRejectsTampering() {
        KeyPair keyPair = generateRsaKeyPair();
        String route = "/v1/qr/qr-mpm-notify";
        String body = "{\"latestTransactionStatus\":\"00\"}";
        String timestamp = "2024-09-12T12:55:00+07:00";

        String signature = signForCallback(keyPair.getPrivate(), "POST", route, body, timestamp);

        assertThat(AyolinxSigner.verifyCallback(keyPair.getPublic(), "POST", route, body, timestamp, signature)).isTrue();
        assertThat(AyolinxSigner.verifyCallback(keyPair.getPublic(), "POST", route, "{\"tampered\":true}", timestamp, signature)).isFalse();
        assertThat(AyolinxSigner.verifyCallback(generateRsaKeyPair().getPublic(), "POST", route, body, timestamp, signature)).isFalse();
    }

    @Test
    void verifyCallbackRejectsGarbageSignatureRatherThanThrowing() {
        KeyPair keyPair = generateRsaKeyPair();
        boolean result = AyolinxSigner.verifyCallback(keyPair.getPublic(), "POST", "/route", "{}", "ts", "not-base64!!");
        assertThat(result).isFalse();
    }

    @Test
    void loadPrivateAndPublicKeyRoundTripThroughPemEncoding() throws Exception {
        KeyPair keyPair = generateRsaKeyPair();
        String privatePem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        String publicPem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(keyPair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";

        PrivateKey loadedPrivate = AyolinxSigner.loadPrivateKey(privatePem);
        PublicKey loadedPublic = AyolinxSigner.loadPublicKey(publicPem);

        String signature = AyolinxSigner.signAccessTokenRequest(loadedPrivate, "ck", "ts");
        assertThat(verifyRsaSha256(loadedPublic, "ck|ts", signature)).isTrue();
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

    private static String hmacSha512Base64(String secret, String data) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA512");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA512"));
            return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String signForCallback(PrivateKey privateKey, String method, String route, String body, String timestamp) {
        String bodyHash = id.ppob2.sharedkernel.security.HmacSigner.sha256Hex(body);
        String stringToSign = method + ":" + route + ":" + bodyHash + ":" + timestamp;
        try {
            java.security.Signature signature = java.security.Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(stringToSign.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean verifyRsaSha256(PublicKey publicKey, String data, String signatureBase64) {
        try {
            java.security.Signature signature = java.security.Signature.getInstance("SHA256withRSA");
            signature.initVerify(publicKey);
            signature.update(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return signature.verify(Base64.getDecoder().decode(signatureBase64));
        } catch (Exception e) {
            return false;
        }
    }
}
