package id.ppob2.sharedkernel.security;

/** A stored secret could not be turned back into plaintext. The message never contains key material or the value. */
public class SecretVaultException extends RuntimeException {

    public SecretVaultException(String message) {
        super(message);
    }

    public SecretVaultException(String message, Throwable cause) {
        super(message, cause);
    }
}
