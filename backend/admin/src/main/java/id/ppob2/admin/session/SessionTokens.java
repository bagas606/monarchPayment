package id.ppob2.admin.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Token generation and the two one-way derivations made from a token: its stored hash and its CSRF token. */
public final class SessionTokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private SessionTokens() {
    }

    /** 256 bits from the platform CSPRNG, URL-safe, no padding. */
    public static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** What the database stores. Unsalted is correct here: the input is 256 random bits, not a human secret. */
    public static String hash(String token) {
        return HexFormat.of().formatHex(sha256(token));
    }

    /**
     * Double-submit value for cookie-authenticated, state-changing requests. Derived from the token,
     * so only a holder of the session (not a cross-site page that can merely cause the browser to send
     * the cookie) can produce it; different per session, and it changes when the session is rotated.
     */
    public static String csrf(String token) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sha256("csrf:" + token));
    }

    public static boolean constantTimeEquals(String a, String b) {
        return a != null && b != null
                && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
