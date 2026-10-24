package id.ppob2.admin.session;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM for the TOTP secret at rest. Output is {@code base64(iv || ciphertext+tag)}; a fresh
 * random 96-bit IV per encryption, and GCM's tag means a modified column fails to decrypt instead of
 * yielding a different (wrong) secret.
 *
 * <p>The key is configuration ({@code ppob2.admin.mfa-encryption-key}), deliberately not derived from
 * anything in the database: the point is that a database dump alone does not contain the second factor.
 */
public final class MfaSecretCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    /** @param base64Key exactly 32 bytes, base64-encoded ({@code openssl rand -base64 32}) */
    public MfaSecretCipher(String base64Key) {
        byte[] raw = Base64.getDecoder().decode(base64Key.trim());
        if (raw.length != 32) {
            throw new IllegalArgumentException("MFA encryption key must be 32 bytes (base64 of 32 random bytes)");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public String encrypt(byte[] plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext);
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("MFA secret encryption failed", e);
        }
    }

    public byte[] decrypt(String encoded) {
        try {
            byte[] all = Base64.getDecoder().decode(encoded);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            return cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("MFA secret could not be decrypted (wrong key or modified value)", e);
        }
    }
}
