package id.ppob2.admin.session;

import id.ppob2.admin.domain.AdminSession;
import id.ppob2.admin.repository.AdminSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.ppob2.admin.domain.AdminUser;
import id.ppob2.admin.repository.AdminUserRepository;
import id.ppob2.admin.security.AdminPrincipal;
import id.ppob2.admin.security.AdminUserDetailsService;
import id.ppob2.audit.AuditService;
import id.ppob2.sharedkernel.error.ApiException;
import id.ppob2.sharedkernel.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationFailureDisabledEvent;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Section 42.3: server-side sessions (idle + absolute timeout, revocable) and TOTP MFA.
 *
 * <h2>The state machine</h2>
 * <pre>
 *   login(password ok) --+-- MFA enrolled ----------> PENDING_MFA    --verifyMfa--> ACTIVE
 *                        +-- role needs MFA, none ---> PENDING_ENROLL --confirm----> ACTIVE
 *                        +-- otherwise --------------> ACTIVE
 * </pre>
 * A pending session may call only the MFA endpoints; the authentication filter gives it no admin
 * authority at all. <b>Promotion rotates the token</b> (a new row, the old one revoked as PROMOTED):
 * the token that existed before the second factor was proven must not become the credential that
 * outlives it (session fixation).
 *
 * <h2>Why failure paths are {@code noRollbackFor ApiException}</h2>
 * A wrong password, a wrong TOTP code and an expired session all end by throwing, and each must leave a
 * write behind - the failure counter, the attempt counter, the revocation. A default
 * {@code @Transactional} would roll those back with the exception, and the lockout would never trigger.
 */
@Service
public class AdminSessionService {

    static final String GENERIC_FAILURE = "Authentication failed.";
    /** BCrypt of a random string, checked when the username is unknown so that "no such user" costs the same as "wrong password". */
    private static final String DUMMY_HASH = "$2a$10$7EqJtq98hPqEX7fNZaFWoOa8vNn9lGz1wI5Zb2c6bXxXq1k9uYdG.";

    private final AdminUserRepository users;
    private final AdminSessionRepository sessions;
    private final AdminUserDetailsService userDetailsService;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final ObjectMapper objectMapper;
    private final AdminProperties props;
    private final Clock clock;
    private final MfaSecretCipher cipher;

    public AdminSessionService(AdminUserRepository users, AdminSessionRepository sessions,
                               AdminUserDetailsService userDetailsService, PasswordEncoder passwordEncoder,
                               AuditService audit, ApplicationEventPublisher events, ObjectMapper objectMapper,
                               AdminProperties props, Clock clock) {
        this.users = users;
        this.sessions = sessions;
        this.userDetailsService = userDetailsService;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.events = events;
        this.objectMapper = objectMapper;
        this.props = props;
        this.clock = clock;
        String key = props.mfa().encryptionKey();
        this.cipher = key == null || key.isBlank() ? null : new MfaSecretCipher(key);
    }

    public boolean mfaAvailable() {
        return cipher != null;
    }

    /** A freshly issued session: the bearer token, the state it is in, and the CSRF value for it. */
    public record Issued(String token, SessionState state, Instant absoluteExpiresAt, String csrfToken) {
    }

    /** A validated session together with the account behind it. */
    public record Resolved(AdminSession session, AdminUser user) {
    }

    // ---- login -----------------------------------------------------------------------------------

    @Transactional(noRollbackFor = ApiException.class)
    public Issued login(String username, String password, String ip) {
        Instant now = clock.instant();
        Optional<AdminUser> account = username == null ? Optional.empty() : users.findByUsername(username);

        if (account.isPresent() && account.get().isLocked(now)) {
            // Not checked against the password: a locked account must not become an oracle that says
            // "that was the right password" to someone who is guessing.
            audit.recordRejectedAdminAttempt("ADMIN_LOGIN_FAILED", "ADMIN_LOGIN",
                    json(Map.of("username", username, "outcome", "REJECTED", "reason", "AccountLocked")), ip);
            throw new ApiException(ErrorCode.AUTHENTICATION_FAILED, GENERIC_FAILURE);
        }

        UsernamePasswordAuthenticationToken attempt = new UsernamePasswordAuthenticationToken(username, password);
        AdminPrincipal principal;
        try {
            UserDetails details;
            try {
                details = userDetailsService.loadUserByUsername(username == null ? "" : username);
            } catch (UsernameNotFoundException e) {
                passwordEncoder.matches(password == null ? "" : password, DUMMY_HASH);
                throw new BadCredentialsException("Bad credentials");
            }
            if (!details.isEnabled()) {
                events.publishEvent(new AuthenticationFailureDisabledEvent(attempt, new DisabledException("Disabled")));
                throw new ApiException(ErrorCode.AUTHENTICATION_FAILED, GENERIC_FAILURE);
            }
            if (password == null || !passwordEncoder.matches(password, details.getPassword())) {
                throw new BadCredentialsException("Bad credentials");
            }
            principal = (AdminPrincipal) details;
        } catch (BadCredentialsException e) {
            // Published so the existing AdminSecurityAuditListener (TC-ADM-002) records it exactly as
            // it did for HTTP Basic: this endpoint does not get a quieter failure path.
            events.publishEvent(new AuthenticationFailureBadCredentialsEvent(attempt, e));
            account.ifPresent(u -> u.recordLoginFailure(now, props.login().maxFailures(), props.login().lockout()));
            throw new ApiException(ErrorCode.AUTHENTICATION_FAILED, GENERIC_FAILURE);
        }

        AdminUser user = account.orElseThrow();
        boolean mfaRequired = isMfaRequired(user);
        SessionState state;
        if (user.isMfaEnabled()) {
            state = SessionState.PENDING_MFA;
        } else if (mfaRequired) {
            state = SessionState.PENDING_ENROLL;
        } else {
            state = SessionState.ACTIVE;
        }
        user.resetFailuresOnly();
        Issued issued = issue(user, state, ip, now);
        if (state == SessionState.ACTIVE) {
            user.recordLoginSuccess(now);
            audit.recordAdminAction(user.getId(), "ADMIN_LOGIN_SUCCESS", "ADMIN_LOGIN", user.getId(), null,
                    json(Map.of("mfa", "NOT_REQUIRED")), ip);
        }
        return issued;
    }

    boolean isMfaRequired(AdminUser user) {
        List<String> required = props.mfa().requiredRoles();
        return users.findRoleCodes(user.getId()).stream().anyMatch(required::contains);
    }

    // ---- resolving a token on every request -------------------------------------------------------

    /**
     * @return the live session behind {@code token}, or empty if it is unknown, revoked, past its
     *         absolute limit, idle for too long, or its account is disabled. An expiry also records the
     *         revocation so the row says why it died.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public Optional<Resolved> resolve(String token) {
        if (token == null || token.isBlank() || token.length() > 128) {
            return Optional.empty();
        }
        Optional<AdminSession> found = sessions.findByTokenHash(SessionTokens.hash(token));
        if (found.isEmpty() || found.get().isRevoked()) {
            return Optional.empty();
        }
        AdminSession session = found.get();
        Instant now = clock.instant();
        if (!now.isBefore(session.getAbsoluteExpiresAt())) {
            session.revoke(now, "EXPIRED_ABSOLUTE");
            return Optional.empty();
        }
        if (session.getState() == SessionState.ACTIVE
                && !now.isBefore(session.getLastSeenAt().plus(props.session().idleTimeout()))) {
            session.revoke(now, "EXPIRED_IDLE");
            return Optional.empty();
        }
        Optional<AdminUser> user = users.findById(session.getAdminUserId()).filter(AdminUser::isActive);
        if (user.isEmpty()) {
            session.revoke(now, "USER_DISABLED");
            return Optional.empty();
        }
        session.touch(now);
        return Optional.of(new Resolved(session, user.get()));
    }

    // ---- MFA -------------------------------------------------------------------------------------

    @Transactional(noRollbackFor = ApiException.class)
    public Issued verifyMfa(Resolved detached, String code, String ip) {
        Resolved resolved = managed(detached);
        AdminSession session = resolved.session();
        AdminUser user = resolved.user();
        if (session.getState() != SessionState.PENDING_MFA) {
            throw new ApiException(ErrorCode.SESSION_STATE_INVALID, "This session is not waiting for an MFA code.");
        }
        long step = checkCode(session, user, code, "ADMIN_MFA_CHALLENGE_FAILED", ip);
        user.acceptMfaStep(step);
        audit.recordAdminAction(user.getId(), "ADMIN_MFA_CHALLENGE_SUCCESS", "ADMIN_LOGIN", user.getId(), null,
                json(Map.of("outcome", "VERIFIED")), ip);
        return promote(session, user, ip, "verified");
    }

    public record Enrollment(String secret, String otpauthUri) {
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Enrollment startEnrollment(Resolved detached, String ip) {
        Resolved resolved = managed(detached);
        AdminUser user = resolved.user();
        if (user.isMfaEnabled()) {
            throw new ApiException(ErrorCode.MFA_ALREADY_ENROLLED, "MFA is already enabled for this account.");
        }
        if (resolved.session().getState() == SessionState.PENDING_MFA) {
            throw new ApiException(ErrorCode.SESSION_STATE_INVALID, "This session is waiting for an MFA code, not enrolment.");
        }
        if (cipher == null) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE,
                    "MFA enrolment is unavailable: ppob2.admin.mfa.encryption-key is not configured.");
        }
        byte[] secret = Totp.newSecret();
        user.beginMfaEnrollment(cipher.encrypt(secret));
        audit.recordAdminAction(user.getId(), "ADMIN_MFA_ENROLLMENT_STARTED", "ADMIN_LOGIN", user.getId(), null, "{}", ip);
        // Shown once, here. It is not retrievable later: only the ciphertext is stored.
        return new Enrollment(Totp.base32(secret), Totp.otpauthUri(props.mfa().issuer(), user.getUsername(), secret));
    }

    /** @return the ACTIVE replacement session if this completed a mandatory enrolment, otherwise empty. */
    @Transactional(noRollbackFor = ApiException.class)
    public Optional<Issued> confirmEnrollment(Resolved detached, String code, String ip) {
        Resolved resolved = managed(detached);
        AdminSession session = resolved.session();
        AdminUser user = resolved.user();
        if (user.isMfaEnabled()) {
            throw new ApiException(ErrorCode.MFA_ALREADY_ENROLLED, "MFA is already enabled for this account.");
        }
        if (session.getState() == SessionState.PENDING_MFA) {
            throw new ApiException(ErrorCode.SESSION_STATE_INVALID, "This session is waiting for an MFA code, not enrolment.");
        }
        if (user.getMfaSecret() == null) {
            throw new ApiException(ErrorCode.SESSION_STATE_INVALID, "Start enrolment first.");
        }
        long step = checkCode(session, user, code, "ADMIN_MFA_ENROLLMENT_FAILED", ip);
        user.confirmMfa(step);
        audit.recordAdminAction(user.getId(), "ADMIN_MFA_ENROLLED", "ADMIN_LOGIN", user.getId(), null,
                json(Map.of("outcome", "CONFIRMED")), ip);
        if (session.getState() == SessionState.PENDING_ENROLL) {
            return Optional.of(promote(session, user, ip, "enrolled"));
        }
        return Optional.empty();
    }

    /** Verifies a code against the stored secret; counts and records a miss. Returns the matched step. */
    private long checkCode(AdminSession session, AdminUser user, String code, String failureAction, String ip) {
        if (cipher == null || user.getMfaSecret() == null) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "MFA is not available for this account.");
        }
        byte[] secret = cipher.decrypt(user.getMfaSecret());
        long step = Totp.match(secret, code, clock.instant().getEpochSecond(), props.mfa().skewSteps());
        if (step < 0 || !user.canAcceptMfaStep(step)) {
            // A code that verifies but was already used is a miss too: that is the replay case.
            int attempts = session.recordMfaFailure();
            audit.recordAdminAction(user.getId(), failureAction, "ADMIN_LOGIN", user.getId(), null,
                    json(Map.of("outcome", "REJECTED", "attempt", String.valueOf(attempts))), ip);
            if (attempts >= props.mfa().maxAttempts()) {
                session.revoke(clock.instant(), "MFA_ATTEMPTS");
            }
            throw new ApiException(ErrorCode.MFA_CODE_INVALID, "The code is not valid.");
        }
        return step;
    }

    private Issued promote(AdminSession pending, AdminUser user, String ip, String how) {
        Instant now = clock.instant();
        pending.revoke(now, "PROMOTED");
        user.recordLoginSuccess(now);
        Issued issued = issue(user, SessionState.ACTIVE, ip, now);
        audit.recordAdminAction(user.getId(), "ADMIN_LOGIN_SUCCESS", "ADMIN_LOGIN", user.getId(), null,
                json(Map.of("mfa", how)), ip);
        return issued;
    }

    // ---- ending sessions -------------------------------------------------------------------------

    @Transactional
    public void logout(Resolved detached, String ip) {
        Resolved resolved = managed(detached);
        resolved.session().revoke(clock.instant(), "LOGOUT");
        audit.recordAdminAction(resolved.user().getId(), "ADMIN_LOGOUT", "ADMIN_LOGIN", resolved.user().getId(), null, "{}", ip);
    }

    /** Section 42.3's "revocable on administrative action". @return how many live sessions were ended. */
    @Transactional
    public int revokeAllFor(String username, Long actorId, String ip) {
        AdminUser target = users.findByUsername(username)
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_ERROR, "Unknown admin username."));
        Instant now = clock.instant();
        List<AdminSession> live = sessions.findByAdminUserIdAndRevokedAtIsNull(target.getId());
        live.forEach(s -> s.revoke(now, "ADMIN_REVOKED"));
        audit.recordAdminAction(actorId, "ADMIN_SESSIONS_REVOKED", "ADMIN_USER", target.getId(), null,
                json(Map.of("revoked", String.valueOf(live.size()))), ip);
        return live.size();
    }

    /** Removes sessions that can never be used again, a day after they died so recent history stays inspectable. */
    @Transactional
    public int purgeDead() {
        return sessions.deleteDeadBefore(clock.instant().minus(java.time.Duration.ofDays(1)));
    }

    // ---- internals -------------------------------------------------------------------------------

    /**
     * Re-attaches a {@link Resolved} to the CURRENT transaction. The filter resolves the session in its
     * own short transaction and hands the entities on; by the time a controller calls in, they are
     * detached, and with open-in-view off a change to a detached entity is silently not saved. That was
     * not hypothetical: the first live run showed {@code startEnrollment} writing its audit row and
     * dropping the new secret, so the next call said "Start enrolment first". A mocked-repository unit
     * test cannot see this, because a mock hands back the very same instance.
     */
    private Resolved managed(Resolved detached) {
        AdminSession session = sessions.findById(detached.session().getId())
                .orElseThrow(() -> new ApiException(ErrorCode.AUTHENTICATION_FAILED, GENERIC_FAILURE));
        AdminUser user = users.findById(detached.user().getId())
                .orElseThrow(() -> new ApiException(ErrorCode.AUTHENTICATION_FAILED, GENERIC_FAILURE));
        return new Resolved(session, user);
    }

    private Issued issue(AdminUser user, SessionState state, String ip, Instant now) {
        String token = SessionTokens.newToken();
        java.time.Duration life = state == SessionState.ACTIVE
                ? props.session().absoluteTimeout() : props.session().pendingTimeout();
        Instant expires = now.plus(life);
        sessions.save(new AdminSession(SessionTokens.hash(token), user.getId(), state, now, expires, ip));
        return new Issued(token, state, expires, SessionTokens.csrf(token));
    }

    public AdminPrincipal principalFor(AdminUser user) {
        return (AdminPrincipal) userDetailsService.loadUserByUsername(user.getUsername());
    }

    /** The principal behind a just-issued token (used to describe the user in the login response). */
    @Transactional(readOnly = true)
    public AdminPrincipal principalForToken(String token) {
        AdminSession session = sessions.findByTokenHash(SessionTokens.hash(token)).orElseThrow();
        AdminUser user = users.findById(session.getAdminUserId()).orElseThrow();
        return principalFor(user);
    }

    public AdminProperties properties() {
        return props;
    }

    private String json(Map<String, String> detail) {
        try {
            return objectMapper.writeValueAsString(new LinkedHashMap<>(detail));
        } catch (Exception e) {
            return "{}";
        }
    }
}
