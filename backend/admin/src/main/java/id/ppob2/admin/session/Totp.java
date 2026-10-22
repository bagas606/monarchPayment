package id.ppob2.admin.session;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * RFC 6238 TOTP (HMAC-SHA1, 30 s step, 6 digits) - the algorithm every authenticator app implements.
 *
 * <p>Written here instead of pulled in as a dependency because it is ~40 lines, has published test
 * vectors (see {@code TotpTest}, RFC 6238 Appendix B), and a payment back-office is a bad place to take
 * an unvetted transitive dependency for something this small. SHA-1 is what the apps expect; HMAC-SHA1
 * is not affected by the collision attacks on bare SHA-1.
 */
public final class Totp {

    public static final int STEP_SECONDS = 30;
    public static final int DIGITS = 6;
    static final int SECRET_BYTES = 20;

    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {
    }

    public static byte[] newSecret() {
        byte[] secret = new byte[SECRET_BYTES];
        RANDOM.nextBytes(secret);
        return secret;
    }

    public static long stepAt(long epochSeconds) {
        return Math.floorDiv(epochSeconds, STEP_SECONDS);
    }

    /** The code for one time step. {@code digits} is a parameter only so the RFC's 8-digit vectors can be checked. */
    public static String code(byte[] secret, long step, int digits) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
            int mod = 1;
            for (int i = 0; i < digits; i++) {
                mod *= 10;
            }
            return String.format("%0" + digits + "d", binary % mod);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 unavailable", e);
        }
    }

    /**
     * Checks {@code submitted} against the steps from {@code now - skew} to {@code now + skew}, and
     * returns the step it matched, or {@code -1}. Every candidate is compared even after a match, with a
     * constant-time comparison, so response time does not reveal which step (or how many digits) matched.
     *
     * <p>The caller must still reject a step that is not greater than the last one it accepted - that is
     * replay protection, and it needs persistent state this class does not have.
     */
    public static long match(byte[] secret, String submitted, long nowEpochSeconds, int skewSteps) {
        if (submitted == null || submitted.length() != DIGITS) {
            return -1;
        }
        long current = stepAt(nowEpochSeconds);
        long matched = -1;
        for (long step = current - skewSteps; step <= current + skewSteps; step++) {
            byte[] expected = code(secret, step, DIGITS).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            if (MessageDigest.isEqual(expected, submitted.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                matched = step;
            }
        }
        return matched;
    }

    public static String base32(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        }
        return out.toString();
    }

    /** The URI authenticator apps import from a QR code or by tapping it. */
    public static String otpauthUri(String issuer, String account, byte[] secret) {
        return "otpauth://totp/" + encode(issuer) + ":" + encode(account) + "?secret=" + base32(secret)
                + "&issuer=" + encode(issuer) + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }
}
