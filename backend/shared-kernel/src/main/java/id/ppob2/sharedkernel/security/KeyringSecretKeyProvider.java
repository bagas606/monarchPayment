package id.ppob2.sharedkernel.security;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A keyring held in configuration: {@code k1:<base64 of 32 bytes>[,k2:<...>]} plus the id of the
 * key new values are written with. Keeping the previous key listed while the active id moves is
 * how rotation works: old values keep decrypting, new ones use the new key.
 *
 * <p>Error messages name the offending id, never any key material.
 */
public final class KeyringSecretKeyProvider implements SecretKeyProvider {

    static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    private final Map<String, byte[]> keys;
    private final String activeKeyId;

    public KeyringSecretKeyProvider(String keyring, String activeKeyId) {
        if (keyring == null || keyring.isBlank()) {
            throw new IllegalArgumentException("secret keyring is empty");
        }
        Map<String, byte[]> parsed = new HashMap<>();
        for (String entry : keyring.split(",")) {
            String e = entry.trim();
            int colon = e.indexOf(':');
            if (colon < 1) {
                throw new IllegalArgumentException("secret keyring entry must be <keyId>:<base64 key>");
            }
            String id = e.substring(0, colon);
            if (!KEY_ID.matcher(id).matches()) {
                throw new IllegalArgumentException("secret key id must match [A-Za-z0-9_-]{1,32}: " + id);
            }
            byte[] raw;
            try {
                raw = Base64.getDecoder().decode(e.substring(colon + 1).trim());
            } catch (IllegalArgumentException bad) {
                throw new IllegalArgumentException("secret key '" + id + "' is not valid base64");
            }
            if (raw.length != 32) {
                throw new IllegalArgumentException("secret key '" + id + "' must be 32 bytes (openssl rand -base64 32)");
            }
            if (parsed.put(id, raw) != null) {
                throw new IllegalArgumentException("secret key id listed twice: " + id);
            }
        }
        if (activeKeyId == null || !parsed.containsKey(activeKeyId.trim())) {
            throw new IllegalArgumentException("active secret key id is not in the keyring");
        }
        this.keys = Map.copyOf(parsed);
        this.activeKeyId = activeKeyId.trim();
    }

    @Override
    public String activeKeyId() {
        return activeKeyId;
    }

    @Override
    public Optional<byte[]> keyFor(String keyId) {
        return Optional.ofNullable(keys.get(keyId));
    }
}
