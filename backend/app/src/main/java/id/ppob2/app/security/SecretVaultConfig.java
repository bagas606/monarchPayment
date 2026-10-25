package id.ppob2.app.security;

import id.ppob2.sharedkernel.security.KeyringSecretKeyProvider;
import id.ppob2.sharedkernel.security.SecretKeyProvider;
import id.ppob2.sharedkernel.security.SecretVault;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the {@link SecretVault} that protects partner HMAC and webhook secrets. The key ring comes from
 * configuration today; a KMS-backed {@link SecretKeyProvider} bean replaces {@link #secretKeyProvider}
 * without touching the vault, the stored format or any column.
 *
 * <p>Fail-fast on purpose, unlike the MFA key: this key gates every partner request, so booting without
 * it would mean either no partner can authenticate or, worse, a silent fallback. There is no default.
 */
@Configuration
public class SecretVaultConfig {

    @Bean
    SecretKeyProvider secretKeyProvider(@Value("${ppob2.security.secret-keys:}") String keyring,
                                        @Value("${ppob2.security.active-secret-key-id:}") String activeKeyId) {
        if (keyring.isBlank() || activeKeyId.isBlank()) {
            throw new IllegalStateException("PPOB2_SECRET_KEYS and PPOB2_SECRET_ACTIVE_KEY_ID must be set "
                    + "(e.g. PPOB2_SECRET_KEYS=k1:$(openssl rand -base64 32) PPOB2_SECRET_ACTIVE_KEY_ID=k1): "
                    + "they encrypt partner API and webhook secrets at rest.");
        }
        return new KeyringSecretKeyProvider(keyring, activeKeyId);
    }

    @Bean
    SecretVault secretVault(SecretKeyProvider provider) {
        return new SecretVault(provider);
    }
}
