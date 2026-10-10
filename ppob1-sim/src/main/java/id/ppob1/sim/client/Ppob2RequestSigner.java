package id.ppob1.sim.client;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * PRD Section 23.2's signature scheme, implemented from the specification text:
 * <pre>
 * string_to_sign = HTTP_METHOD + "\n" + PATH + "\n" + TIMESTAMP + "\n" + NONCE + "\n" + SHA256(BODY)
 * signature      = HMAC_SHA256(client_secret, string_to_sign)
 * </pre>
 *
 * <p><b>Why this is written out again instead of reusing PPOB2's {@code HmacSigner}.</b> Depending
 * on the server's own signer would make every signature agree by construction. The tests would
 * then pass whether or not the scheme they exercise is the one Section 23.2 publishes, because both
 * sides would be wrong in the same direction — and the published scheme is what an actual reseller
 * integrates against, having never seen PPOB2's source. Two independent implementations can
 * disagree, and that is the entire reason this one exists. {@code Ppob2RequestSignerTest} pins the
 * output against a hand-computed vector so a change on either side has to be deliberate.
 *
 * <p>Two details the specification leaves ambiguous, resolved here against PPOB2's observed
 * behaviour and recorded because a reseller would otherwise have to discover them by trial:
 * <ul>
 *   <li><b>"hex or base64"</b> — Section 23.2 offers both. PPOB2 accepts <b>lowercase hex</b> only.
 *       Base64 is rejected as {@code SIGNATURE_INVALID}, which is indistinguishable from a wrong
 *       secret, so an integrator picking the other branch of that sentence gets a 401 with no hint
 *       which half of it was wrong.</li>
 *   <li><b>PATH</b> — the query string is <b>not</b> signed. PPOB2 signs
 *       {@code HttpServletRequest.getRequestURI()}, which stops at the {@code ?}. This matters for
 *       the one GET endpoint that takes a parameter ({@code /api/v1/config/supported-amounts
 *       ?product_code=...}): signing the full URL fails, and only there, so it looks like that
 *       endpoint is broken rather than like a signing rule.</li>
 * </ul>
 *
 * <p>The same scheme verifies inbound Section 23.8 webhooks ({@link #matches}), with the partner's
 * webhook secret rather than the API client secret — one scheme, two keys, as Section 23.8 says.
 */
@Component
public class Ppob2RequestSigner {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final SecureRandom random = new SecureRandom();

    public String sha256Hex(String body) {
        try {
            return toHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * @param path request path only — never include the query string, see the class Javadoc.
     * @param timestamp Unix epoch <em>milliseconds</em> as a decimal string. Seconds are rejected:
     *     they fall outside the ±5-minute window by a factor of a thousand, which surfaces as
     *     {@code SIGNATURE_INVALID} rather than as anything mentioning time.
     */
    public String stringToSign(String method, String path, String timestamp, String nonce, String bodyHash) {
        return method + "\n" + path + "\n" + timestamp + "\n" + nonce + "\n" + bodyHash;
    }

    public String sign(String secret, String stringToSign) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return toHex(mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to compute HMAC-SHA256", e);
        }
    }

    /**
     * Constant-time comparison via {@link MessageDigest#isEqual}. A webhook receiver that compared
     * with {@code String.equals} would leak the expected signature a byte at a time to anyone able
     * to time its responses — the one place in a receiver where ordinary string equality is a real
     * defect rather than a style preference.
     *
     * <p>Returns {@code false} rather than throwing on a {@code null} candidate: a caller that
     * omitted {@code X-Signature} entirely is an unsigned request, which is a verification failure
     * and should be recorded as one, not an error that skips the recording.
     */
    public boolean matches(String secret, String stringToSign, String candidateHex) {
        if (candidateHex == null) {
            return false;
        }
        return MessageDigest.isEqual(
                sign(secret, stringToSign).getBytes(StandardCharsets.UTF_8),
                candidateHex.getBytes(StandardCharsets.UTF_8));
    }

    /** 32 lowercase hex characters from 16 random bytes. Section 23.2 only requires per-request
     * uniqueness; PPOB2 additionally rejects a repeat within the signature window. */
    public String newNonce() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return toHex(bytes);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
