package id.ppob2.sharedkernel.security;

import java.util.Optional;

/**
 * Resolves a key id to 32 bytes of AES-256 key material. This is the only seam a key-management
 * service needs to plug into: an implementation backed by AWS KMS, GCP KMS, Azure Key Vault or
 * Vault unwraps one stored data key per id at boot and caches it, so a stored value never changes
 * shape and an HMAC request never pays a network round trip.
 */
public interface SecretKeyProvider {

    /** The id new values are encrypted under. */
    String activeKeyId();

    /** Empty for an id this deployment does not hold (e.g. a retired key that was not kept). */
    Optional<byte[]> keyFor(String keyId);
}
