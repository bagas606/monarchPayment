package id.ppob2.sharedkernel.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class SecretVaultTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    private static String randomKey() {
        byte[] k = new byte[32];
        RANDOM.nextBytes(k);
        return Base64.getEncoder().encodeToString(k);
    }

    private static SecretVault vault(String keyring, String active) {
        return new SecretVault(new KeyringSecretKeyProvider(keyring, active));
    }

    @Test
    void roundTripsAndNeverStoresThePlaintext() {
        SecretVault v = vault("k1:" + randomKey(), "k1");
        String stored = v.encrypt("dev-secret", "api_client:ppob1-client");

        assertThat(stored).startsWith("v1.k1.").doesNotContain("dev-secret");
        assertThat(v.decrypt(stored, "api_client:ppob1-client")).isEqualTo("dev-secret");
    }

    @Test
    void sameSecretEncryptsDifferentlyEachTime() {
        SecretVault v = vault("k1:" + randomKey(), "k1");
        assertThat(v.encrypt("s", "a")).isNotEqualTo(v.encrypt("s", "a"));
    }

    @Test
    void aValueCopiedOntoAnotherRowDoesNotDecrypt() {
        SecretVault v = vault("k1:" + randomKey(), "k1");
        String stored = v.encrypt("dev-secret", "api_client:alice");

        assertThatThrownBy(() -> v.decrypt(stored, "api_client:mallory")).isInstanceOf(SecretVaultException.class);
    }

    @Test
    void aModifiedValueDoesNotDecrypt() {
        SecretVault v = vault("k1:" + randomKey(), "k1");
        String stored = v.encrypt("dev-secret", "x");
        byte[] raw = Base64.getDecoder().decode(stored.substring("v1.k1.".length()));
        raw[raw.length - 1] ^= 0x01;
        String tampered = "v1.k1." + Base64.getEncoder().encodeToString(raw);

        assertThatThrownBy(() -> v.decrypt(tampered, "x")).isInstanceOf(SecretVaultException.class);
    }

    @Test
    void aDifferentKeyUnderTheSameIdDoesNotDecrypt() {
        String stored = vault("k1:" + randomKey(), "k1").encrypt("s", "x");
        SecretVault other = vault("k1:" + randomKey(), "k1");

        assertThatThrownBy(() -> other.decrypt(stored, "x")).isInstanceOf(SecretVaultException.class);
    }

    @Test
    void rotationKeepsOldValuesReadableAndWritesWithTheNewKey() {
        String k1 = randomKey();
        String k2 = randomKey();
        String oldValue = vault("k1:" + k1, "k1").encrypt("s", "x");

        SecretVault rotated = vault("k1:" + k1 + ",k2:" + k2, "k2");
        assertThat(rotated.decrypt(oldValue, "x")).isEqualTo("s");
        assertThat(rotated.encrypt("s", "x")).startsWith("v1.k2.");
        assertThat(SecretVault.keyIdOf(oldValue)).isEqualTo("k1");
    }

    @Test
    void aRetiredKeyThatWasDroppedFailsInsteadOfGuessing() {
        String oldValue = vault("k1:" + randomKey(), "k1").encrypt("s", "x");
        SecretVault only2 = vault("k2:" + randomKey(), "k2");

        assertThatThrownBy(() -> only2.decrypt(oldValue, "x"))
                .isInstanceOf(SecretVaultException.class).hasMessageContaining("k1");
    }

    @Test
    void plaintextAndMalformedValuesAreRejectedNeverUsedAsIs() {
        SecretVault v = vault("k1:" + randomKey(), "k1");
        for (String bad : new String[] {"dev-secret", "", "v1.k1.", "v1.k1.AAAA", "v2.k1.AAAAAAAAAAAAAAAAAAAAAAAA", null}) {
            assertThatThrownBy(() -> v.decrypt(bad, "x")).as("value: %s", bad).isInstanceOf(SecretVaultException.class);
        }
        assertThat(SecretVault.keyIdOf("dev-secret")).isNull();
    }

    @Test
    void keyringRejectsBadConfigurationWithoutEchoingKeyMaterial() {
        String good = randomKey();
        assertThatThrownBy(() -> new KeyringSecretKeyProvider("", "k1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KeyringSecretKeyProvider("k1:" + good, "k2")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KeyringSecretKeyProvider("k1:" + good + ",k1:" + good, "k1")).hasMessageContaining("twice");
        assertThatThrownBy(() -> new KeyringSecretKeyProvider("k1:not-base64!!", "k1")).hasMessageNotContaining("not-base64!!");
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> new KeyringSecretKeyProvider("k1:" + shortKey, "k1")).hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> new KeyringSecretKeyProvider("bad.id:" + good, "bad.id")).isInstanceOf(IllegalArgumentException.class);
    }
}
