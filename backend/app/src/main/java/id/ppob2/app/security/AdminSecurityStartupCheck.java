package id.ppob2.app.security;

import id.ppob2.admin.session.AdminProperties;
import id.ppob2.admin.session.AdminSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Says out loud, at boot, the two configurations that weaken Section 42.3, and purges dead sessions. */
@Component
public class AdminSecurityStartupCheck {

    private static final Logger log = LoggerFactory.getLogger(AdminSecurityStartupCheck.class);

    private final AdminProperties props;
    private final AdminSessionService sessions;

    public AdminSecurityStartupCheck(AdminProperties props, AdminSessionService sessions) {
        this.props = props;
        this.sessions = sessions;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warn() {
        if (props.basicAuthEnabled()) {
            log.warn("ppob2.admin.basic-auth-enabled=true: HTTP Basic is accepted on /admin/** and carries NO MFA. "
                    + "SUPER_ADMIN, FINANCE and RECONCILIATION (MFA-mandatory under Section 42.3) can bypass it. "
                    + "Dev/e2e only - never in a deployment that handles real money.");
        }
        if (!sessions.mfaAvailable()) {
            log.warn("ppob2.admin.mfa.encryption-key is not set: MFA enrolment is unavailable, so accounts in an "
                    + "MFA-mandatory role cannot sign in through /admin/auth/login. Generate one with "
                    + "`openssl rand -base64 32`.");
        }
    }

    @Scheduled(fixedDelayString = "${ppob2.admin.session.purge-interval-ms:3600000}", initialDelay = 60_000)
    public void purge() {
        int removed = sessions.purgeDead();
        if (removed > 0) {
            log.info("Purged {} dead admin sessions", removed);
        }
    }
}
