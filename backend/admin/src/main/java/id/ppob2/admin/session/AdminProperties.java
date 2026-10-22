package id.ppob2.admin.session;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Section 42.3 settings. Durations are properties - not constants - so the e2e harness can run a
 * session that idles out in four seconds instead of waiting half an hour to prove the timeout works.
 */
@ConfigurationProperties(prefix = "ppob2.admin")
public record AdminProperties(
        /*
         * HTTP Basic carries no TOTP, so while it is accepted MFA is bypassed for whoever uses it -
         * including the SUPER_ADMIN/FINANCE/RECONCILIATION accounts Section 42.3 makes MFA mandatory
         * for. It exists for the dev seed and the e2e suites (which authenticate with -u), and is OFF
         * unless explicitly turned on. Anything that boots with it on logs a warning.
         */
        @DefaultValue("false") boolean basicAuthEnabled,
        @DefaultValue Session session,
        @DefaultValue Mfa mfa,
        @DefaultValue Login login) {

    public record Session(
            @DefaultValue("PT30M") Duration idleTimeout,
            @DefaultValue("PT8H") Duration absoluteTimeout,
            /** How long a password-only session (MFA still owed) stays usable. */
            @DefaultValue("PT5M") Duration pendingTimeout,
            @DefaultValue("PPOB2_ADMIN_SESSION") String cookieName,
            /** False only for plain-HTTP local runs; browsers refuse to keep a Secure cookie off HTTPS. */
            @DefaultValue("true") boolean cookieSecure) {
    }

    public record Mfa(
            /** Section 42.3: mandatory for these roles. */
            @DefaultValue({"SUPER_ADMIN", "FINANCE", "RECONCILIATION"}) List<String> requiredRoles,
            /** Wrong codes on one session before it is revoked. */
            @DefaultValue("5") int maxAttempts,
            /** base64 of 32 random bytes. Blank = MFA enrolment is unavailable (never stored unencrypted). */
            @DefaultValue("") String encryptionKey,
            @DefaultValue("PPOB2 Admin") String issuer,
            /** Steps accepted either side of "now" (1 = +-30 s) for authenticator clock drift. */
            @DefaultValue("1") int skewSteps) {
    }

    public record Login(
            @DefaultValue("5") int maxFailures,
            @DefaultValue("PT15M") Duration lockout) {
    }
}
