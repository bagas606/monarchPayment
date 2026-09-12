package id.ppob2.sharedkernel.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Canonical request signing per PRD Section 23.2:
 * {@code string_to_sign = METHOD + "\n" + PATH + "\n" + TIMESTAMP + "\n" + NONCE + "\n" + SHA256(BODY)}.
 * Shared by inbound Open API auth (Section 23.2) and outbound webhook signing (Section 23.8),
 * which use the identical scheme with different secrets.
 */
public final class HmacSigner {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private HmacSigner() {
    }

    public static String sha256Hex(String body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(body.getBytes(StandardCharsets.UTF_8));
            return toHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public static String buildStringToSign(String method, String path, String timestamp, String nonce, String bodyHash) {
        return method + "\n" + path + "\n" + timestamp + "\n" + nonce + "\n" + bodyHash;
    }

    public static String sign(String secret, String stringToSign) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] raw = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
            return toHex(raw);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to compute HMAC signature", e);
        }
    }

    public static boolean matches(String secret, String stringToSign, String candidateSignatureHex) {
        String expected = sign(secret, stringToSign);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                candidateSignatureHex.getBytes(StandardCharsets.UTF_8));
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
