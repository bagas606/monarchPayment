package id.ppob2.admin.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class TotpTest {

    /** RFC 6238 Appendix B, SHA-1: secret is the ASCII string "12345678901234567890", 8 digits. */
    private static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    void matchesEveryRfc6238Sha1TestVector() {
        long[][] vectors = {
                {59L, 94287082L}, {1111111109L, 7081804L}, {1111111111L, 14050471L},
                {1234567890L, 89005924L}, {2000000000L, 69279037L}, {20000000000L, 65353130L}};
        for (long[] v : vectors) {
            String expected = String.format("%08d", v[1]);
            assertThat(Totp.code(RFC_SECRET, Totp.stepAt(v[0]), 8))
                    .as("T=%d", v[0]).isEqualTo(expected);
        }
    }

    @Test
    void sixDigitCodeIsTheLastSixDigitsOfTheEightDigitOne() {
        // 94287082 at T=59 -> 287082: the RFC vectors therefore also pin the 6-digit mode.
        assertThat(Totp.code(RFC_SECRET, Totp.stepAt(59), 6)).isEqualTo("287082");
    }

    @Test
    void acceptsTheAdjacentStepsAndReportsWhichOneMatched() {
        long now = 1_700_000_000L;
        long step = Totp.stepAt(now);
        String previous = Totp.code(RFC_SECRET, step - 1, 6);
        String next = Totp.code(RFC_SECRET, step + 1, 6);
        String farAway = Totp.code(RFC_SECRET, step + 5, 6);

        assertThat(Totp.match(RFC_SECRET, Totp.code(RFC_SECRET, step, 6), now, 1)).isEqualTo(step);
        assertThat(Totp.match(RFC_SECRET, previous, now, 1)).isEqualTo(step - 1);
        assertThat(Totp.match(RFC_SECRET, next, now, 1)).isEqualTo(step + 1);
        assertThat(Totp.match(RFC_SECRET, farAway, now, 1)).isEqualTo(-1);
    }

    @Test
    void rejectsMalformedInputWithoutThrowing() {
        for (String bad : new String[]{null, "", "12345", "1234567", "abcdef", "12 456"}) {
            assertThat(Totp.match(RFC_SECRET, bad, 1_700_000_000L, 1)).as("input %s", bad).isEqualTo(-1);
        }
    }

    @Test
    void base32MatchesRfc4648Vectors() {
        assertThat(Totp.base32("f".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MY");
        assertThat(Totp.base32("fo".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXQ");
        assertThat(Totp.base32("foobar".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6YTBOI");
        assertThat(Totp.base32(RFC_SECRET)).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
    }

    @Test
    void otpauthUriCarriesTheSecretAndIsUrlSafe() {
        String uri = Totp.otpauthUri("PPOB2 Admin", "ops@corp", RFC_SECRET);
        assertThat(uri).startsWith("otpauth://totp/PPOB2%20Admin:ops%40corp?secret=GEZDGNBVGY3TQOJQ")
                .contains("&issuer=PPOB2%20Admin").contains("&digits=6").contains("&period=30");
    }

    @Test
    void newSecretsAreTwentyBytesAndDiffer() {
        assertThat(Totp.newSecret()).hasSize(20).isNotEqualTo(Totp.newSecret());
    }

    // ---- the at-rest cipher ----------------------------------------------------------------

    private static String key() {
        return Base64.getEncoder().encodeToString(new byte[32]);
    }

    @Test
    void cipherRoundTripsAndNeverRepeatsACiphertext() {
        MfaSecretCipher cipher = new MfaSecretCipher(key());
        String a = cipher.encrypt(RFC_SECRET);
        assertThat(cipher.decrypt(a)).isEqualTo(RFC_SECRET);
        assertThat(cipher.encrypt(RFC_SECRET)).as("fresh IV each time").isNotEqualTo(a);
        assertThat(a).doesNotContain(Totp.base32(RFC_SECRET));
    }

    @Test
    void cipherRefusesATamperedValueAndTheWrongKey() {
        MfaSecretCipher cipher = new MfaSecretCipher(key());
        byte[] raw = Base64.getDecoder().decode(cipher.encrypt(RFC_SECRET));
        raw[raw.length - 1] ^= 1;
        String tampered = Base64.getEncoder().encodeToString(raw);
        assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(IllegalStateException.class);

        byte[] otherKey = new byte[32];
        otherKey[0] = 1;
        MfaSecretCipher other = new MfaSecretCipher(Base64.getEncoder().encodeToString(otherKey));
        String valid = cipher.encrypt(RFC_SECRET);
        assertThatThrownBy(() -> other.decrypt(valid)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cipherRejectsAKeyOfTheWrongLength() {
        assertThatThrownBy(() -> new MfaSecretCipher(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
