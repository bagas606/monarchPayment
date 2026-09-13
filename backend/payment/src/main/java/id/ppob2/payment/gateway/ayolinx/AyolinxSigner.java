package id.ppob2.payment.gateway.ayolinx;

import id.ppob2.sharedkernel.security.HmacSigner;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The three distinct signing schemes documented at doc.ayolinx.id/doc-5866155 — none of them
 * match {@link HmacSigner}'s scheme (HMAC-SHA256, hex, newline-delimited), which is why this is
 * a separate class rather than an extension of it. Only {@link #sha256Hex} is reused from there,
 * since that piece genuinely is the same primitive (plain SHA-256 digest, hex-encoded).
 */
final class AyolinxSigner {

    private AyolinxSigner() {
    }

    /** Access Token B2B's {@code X-SIGNATURE}: RSA-SHA256 over {@code clientKey|timestamp},
     * signed with the merchant's own private key, base64-encoded. */
    static String signAccessTokenRequest(PrivateKey privateKey, String clientKey, String timestamp) {
        String stringToSign = clientKey + "|" + timestamp;
        return rsaSha256(privateKey, stringToSign);
    }

    /** Every other API call's {@code X-SIGNATURE}: HMAC-SHA512 over
     * {@code METHOD:PATH:ACCESS_TOKEN:SHA256_HEX(BODY):TIMESTAMP}, keyed by {@code client_secret},
     * base64-encoded. */
    static String signApiRequest(String clientSecret, String method, String path, String accessToken,
                                  String body, String timestamp) {
        String stringToSign = method + ":" + path + ":" + accessToken + ":" + HmacSigner.sha256Hex(body) + ":" + timestamp;
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(clientSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            byte[] raw = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(raw);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to compute Ayolinx HMAC-SHA512 request signature", e);
        }
    }

    /** Inbound callback {@code X-SIGNATURE} verification: RSA-SHA256 over
     * {@code METHOD:ROUTE:SHA256_HEX(BODY):TIMESTAMP}, verified against Ayolinx's public key.
     * {@code route} is genuinely undocumented (see {@code AyolinxPaymentGateway}'s Javadoc) —
     * this method just verifies whatever route string the caller supplies. */
    static boolean verifyCallback(PublicKey publicKey, String method, String route, String body,
                                   String timestamp, String signatureBase64) {
        String stringToSign = method + ":" + route + ":" + HmacSigner.sha256Hex(body) + ":" + timestamp;
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(publicKey);
            signature.update(stringToSign.getBytes(StandardCharsets.UTF_8));
            return signature.verify(Base64.getDecoder().decode(signatureBase64));
        } catch (Exception e) {
            return false;
        }
    }

    private static String rsaSha256(PrivateKey privateKey, String data) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException("Unable to compute Ayolinx RSA-SHA256 signature", e);
        }
    }

    /** Parses a PKCS8 PEM private key (PEM headers/newlines stripped, base64-decoded) — the
     * format produced by {@code openssl genpkey}, per doc.ayolinx.id's setup instructions. */
    static PrivateKey loadPrivateKey(String pem) {
        try {
            byte[] der = Base64.getDecoder().decode(stripPem(pem));
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            return keyFactory.generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid Ayolinx private key PEM", e);
        }
    }

    /** Parses an X.509 PEM public key (Ayolinx's, used to verify inbound callbacks). */
    static PublicKey loadPublicKey(String pem) {
        try {
            byte[] der = Base64.getDecoder().decode(stripPem(pem));
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            return keyFactory.generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid Ayolinx public key PEM", e);
        }
    }

    private static String stripPem(String pem) {
        return pem
                .replaceAll("-----BEGIN [A-Z ]+-----", "")
                .replaceAll("-----END [A-Z ]+-----", "")
                .replaceAll("\\s", "");
    }
}
