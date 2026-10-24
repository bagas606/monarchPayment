package id.ppob2.sharedkernel.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Reversible encryption for secrets PPOB2 must be able to read back: the per-client HMAC secret
 * (PRD Section 23.2) and the per-partner webhook signing secret (Section 23.8). A one-way hash
 * cannot serve either, because the server has to reproduce the MAC the other side computed.
 *
 * <p>Stored form is {@code v1.<keyId>.<base64(iv || ciphertext+tag)>}: self-describing, so a value
 * says which key decrypts it and rotation needs no schema change. AES-256-GCM with a fresh random
 * 96-bit IV per encryption.
 *
 * <p>The caller passes a row identity as {@code aad} (e.g. {@code api_client:ppob1-client}). GCM
 * authenticates it without storing it, so a ciphertext copied onto another row fails to decrypt
 * instead of silently authenticating as that row.
 *
 * <p>Anything not in this format is rejected, never used as-is: there is deliberately no "plaintext
 * accepted" fallback, because that would keep open the hole this class exists to close.
 */
public final class SecretVault {

    private static final String PREFIX = "v1";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeyProvider keys;

    public SecretVault(SecretKeyProvider keys) {
        this.keys = keys;
    }

    public String activeKeyId() {
        return keys.activeKeyId();
    }

    public String encrypt(String plaintext, String aad) {
        String keyId = keys.activeKeyId();
        byte[] key = keys.keyFor(keyId).orElseThrow(() -> new SecretVaultException("active key is not available"));
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            String body = Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
            return PREFIX + "." + keyId + "." + body;
        } catch (GeneralSecurityException e) {
            throw new SecretVaultException("encryption failed", e);
        }
    }

    /** @throws SecretVaultException for anything that is not a value this vault produced for {@code aad} */
    public String decrypt(String stored, String aad) {
        String[] parts = stored == null ? new String[0] : stored.split("\\.", 3);
        if (parts.length != 3 || !PREFIX.equals(parts[0])) {
            throw new SecretVaultException("value is not in the encrypted secret format");
        }
        byte[] key = keys.keyFor(parts[1]).orElseThrow(
                () -> new SecretVaultException("no key '" + parts[1] + "' in this deployment's keyring"));
        try {
            byte[] all = Base64.getDecoder().decode(parts[2]);
            if (all.length <= IV_BYTES) {
                throw new SecretVaultException("encrypted value is truncated");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new SecretVaultException("value could not be decrypted (wrong key, other row, or modified)", e);
        }
    }

    /** Key id a stored value was written under, or null if it is not in the encrypted format. */
    public static String keyIdOf(String stored) {
        String[] parts = stored == null ? new String[0] : stored.split("\\.", 3);
        return parts.length == 3 && PREFIX.equals(parts[0]) ? parts[1] : null;
    }
}
