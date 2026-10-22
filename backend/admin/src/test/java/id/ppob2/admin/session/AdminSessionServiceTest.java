package id.ppob2.admin.session;

import id.ppob2.admin.domain.AdminSession;
import id.ppob2.admin.repository.AdminSessionRepository;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.ppob2.admin.domain.AdminUser;
import id.ppob2.admin.repository.AdminUserRepository;
import id.ppob2.admin.security.AdminPrincipal;
import id.ppob2.admin.security.AdminUserDetailsService;
import id.ppob2.audit.AuditService;
import id.ppob2.sharedkernel.error.ApiException;
import id.ppob2.sharedkernel.error.ErrorCode;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * The session and MFA state machine, with a clock the test moves. Each case pins one sentence of
 * Section 42.3 or one decision recorded in V27 / {@link AdminSessionService}.
 */
class AdminSessionServiceTest {

    static final class MovableClock extends Clock {
        Instant now = Instant.parse("2026-10-22T09:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    private final MovableClock clock = new MovableClock();
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final AdminUserRepository users = mock(AdminUserRepository.class);
    private final AdminSessionRepository sessions = mock(AdminSessionRepository.class);
    private final AdminUserDetailsService details = mock(AdminUserDetailsService.class);
    private final AuditService audit = mock(AuditService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final Map<String, AdminSession> store = new HashMap<>();
    private final List<AdminSession> all = new ArrayList<>();
    private final Map<String, AdminUser> byName = new HashMap<>();

    private AdminProperties props = props(5);
    private AdminSessionService service;

    private static AdminProperties props(int maxAttempts) {
        return new AdminProperties(false,
                new AdminProperties.Session(Duration.ofMinutes(30), Duration.ofHours(8), Duration.ofMinutes(5),
                        "S", true),
                new AdminProperties.Mfa(List.of("SUPER_ADMIN", "FINANCE", "RECONCILIATION"), maxAttempts,
                        Base64.getEncoder().encodeToString(new byte[32]), "PPOB2", 1),
                new AdminProperties.Login(3, Duration.ofMinutes(15)));
    }

    @BeforeEach
    void wire() {
        build(props);
        given(sessions.save(any(AdminSession.class))).willAnswer(inv -> {
            AdminSession s = inv.getArgument(0);
            if (s.getId() == null) {
                setId(s, all.size() + 1L);
            }
            store.put(s.getTokenHash(), s);
            all.add(s);
            return s;
        });
        given(sessions.findById(anyLong())).willAnswer(inv ->
                all.stream().filter(s -> s.getId().equals(inv.getArgument(0))).findFirst());
        given(sessions.findByTokenHash(any())).willAnswer(inv -> Optional.ofNullable(store.get((String) inv.getArgument(0))));
        given(sessions.findByAdminUserIdAndRevokedAtIsNull(anyLong())).willAnswer(inv ->
                all.stream().filter(s -> s.getAdminUserId().equals(inv.getArgument(0)) && !s.isRevoked()).toList());
        given(details.loadUserByUsername(any())).willAnswer(inv -> {
            AdminUser u = byName.get((String) inv.getArgument(0));
            if (u == null) {
                throw new UsernameNotFoundException("x");
            }
            return new AdminPrincipal(u, List.of("order:view"));
        });
    }

    private static void setId(Object target, long id) {
        try {
            Field f = target.getClass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(target, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private void build(AdminProperties p) {
        this.props = p;
        this.service = new AdminSessionService(users, sessions, details, encoder, audit, events,
                new ObjectMapper(), p, clock);
    }

    private AdminUser user(long id, String name, String... roles) {
        AdminUser u = new AdminUser(name, encoder.encode("pw"));
        try {
            Field f = AdminUser.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(u, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        byName.put(name, u);
        given(users.findByUsername(name)).willReturn(Optional.of(u));
        given(users.findById(id)).willReturn(Optional.of(u));
        given(users.findRoleCodes(id)).willReturn(List.of(roles));
        return u;
    }

    private AdminSessionService.Resolved resolve(AdminSessionService.Issued issued) {
        return service.resolve(issued.token()).orElseThrow();
    }

    private String codeNow(AdminUser u) {
        // reads the secret back out of the stored ciphertext, as an authenticator app would have it
        byte[] secret = new MfaSecretCipher(props.mfa().encryptionKey()).decrypt(u.getMfaSecret());
        return Totp.code(secret, Totp.stepAt(clock.instant().getEpochSecond()), 6);
    }

    private void enrol(AdminUser u) {
        AdminSessionService.Issued s = service.login(u.getUsername(), "pw", "1.1.1.1");
        service.startEnrollment(resolve(s), "1.1.1.1");
        service.confirmEnrollment(resolve(s), codeNow(u), "1.1.1.1");
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    void aRoleWithoutMandatoryMfaGetsAnActiveSessionStraightAway() {
        AdminUser u = user(1, "ops", "OPERATIONS");
        AdminSessionService.Issued issued = service.login("ops", "pw", "1.1.1.1");
        assertThat(issued.state()).isEqualTo(SessionState.ACTIVE);
        assertThat(issued.csrfToken()).isNotBlank().isNotEqualTo(issued.token());
        assertThat(issued.absoluteExpiresAt()).isEqualTo(clock.instant().plus(Duration.ofHours(8)));
        verify(audit).recordAdminAction(eq(1L), eq("ADMIN_LOGIN_SUCCESS"), any(), any(), any(), any(), eq("1.1.1.1"));
    }

    @Test
    void onlyAHashOfTheTokenIsStored() {
        user(1, "ops", "OPERATIONS");
        AdminSessionService.Issued issued = service.login("ops", "pw", null);
        assertThat(store).containsOnlyKeys(SessionTokens.hash(issued.token()));
        assertThat(store.keySet().iterator().next()).isNotEqualTo(issued.token()).hasSize(64);
    }

    @Test
    void aMandatoryMfaRoleWithNoEnrolmentGetsAnEnrolOnlySessionUntilItConfirms() {
        AdminUser u = user(2, "boss", "SUPER_ADMIN");
        AdminSessionService.Issued pending = service.login("boss", "pw", "1.1.1.1");
        assertThat(pending.state()).isEqualTo(SessionState.PENDING_ENROLL);
        assertThat(pending.absoluteExpiresAt()).as("pending sessions are short-lived")
                .isEqualTo(clock.instant().plus(Duration.ofMinutes(5)));

        AdminSessionService.Enrollment enrollment = service.startEnrollment(resolve(pending), "1.1.1.1");
        assertThat(enrollment.otpauthUri()).startsWith("otpauth://totp/").contains(enrollment.secret());
        assertThat(u.isMfaEnabled()).as("not protected until a code is confirmed").isFalse();
        assertThat(u.getMfaSecret()).as("stored encrypted, not the base32 secret").doesNotContain(enrollment.secret());

        AdminSessionService.Issued active = service.confirmEnrollment(resolve(pending), codeNow(u), "1.1.1.1").orElseThrow();
        assertThat(active.state()).isEqualTo(SessionState.ACTIVE);
        assertThat(u.isMfaEnabled()).isTrue();
        assertThat(active.token()).as("rotated on promotion").isNotEqualTo(pending.token());
        assertThat(service.resolve(pending.token())).as("the pre-MFA token dies at promotion").isEmpty();
        assertThat(service.resolve(active.token())).isPresent();
    }

    @Test
    void aWrongEnrolmentCodeDoesNotEnableMfa() {
        AdminUser u = user(2, "boss", "SUPER_ADMIN");
        AdminSessionService.Issued pending = service.login("boss", "pw", null);
        service.startEnrollment(resolve(pending), null);
        assertThatThrownBy(() -> service.confirmEnrollment(resolve(pending), "000000", null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.MFA_CODE_INVALID));
        assertThat(u.isMfaEnabled()).isFalse();
    }

    @Test
    void anEnrolledAccountMustPresentACodeAndThePasswordAloneBuysNothing() {
        AdminUser u = user(3, "fin", "FINANCE");
        enrol(u);
        clock.advance(Duration.ofSeconds(31)); // next time-step, so the enrolment code is not replayed

        AdminSessionService.Issued pending = service.login("fin", "pw", null);
        assertThat(pending.state()).isEqualTo(SessionState.PENDING_MFA);

        AdminSessionService.Issued active = service.verifyMfa(resolve(pending), codeNow(u), null);
        assertThat(active.state()).isEqualTo(SessionState.ACTIVE);
        assertThat(service.resolve(pending.token())).isEmpty();
    }

    @Test
    void aCodeThatWasAlreadyUsedIsRefused() {
        AdminUser u = user(3, "fin", "FINANCE");
        enrol(u);
        clock.advance(Duration.ofSeconds(31));
        AdminSessionService.Issued first = service.login("fin", "pw", null);
        String code = codeNow(u);
        service.verifyMfa(resolve(first), code, null);

        AdminSessionService.Issued second = service.login("fin", "pw", null);
        assertThatThrownBy(() -> service.verifyMfa(resolve(second), code, null))
                .as("same code, same time-step: a replay")
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.MFA_CODE_INVALID));

        clock.advance(Duration.ofSeconds(31));
        assertThat(service.verifyMfa(resolve(second), codeNow(u), null).state()).isEqualTo(SessionState.ACTIVE);
    }

    @Test
    void theSessionIsRevokedAfterTooManyWrongCodes() {
        build(props(3));
        AdminUser u = user(3, "fin", "FINANCE");
        enrol(u);
        clock.advance(Duration.ofSeconds(31));
        AdminSessionService.Issued pending = service.login("fin", "pw", null);

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> service.verifyMfa(resolve(pending), "111111", null)).isInstanceOf(ApiException.class);
            if (i < 2) {
                assertThat(service.resolve(pending.token())).as("still alive after miss %d", i + 1).isPresent();
            }
        }
        assertThat(service.resolve(pending.token())).as("revoked at the third miss").isEmpty();
    }

    @Test
    void anActiveSessionIsEndedByIdleTimeoutButActivityKeepsItAlive() {
        user(1, "ops", "OPERATIONS");
        AdminSessionService.Issued issued = service.login("ops", "pw", null);

        clock.advance(Duration.ofMinutes(29));
        assertThat(service.resolve(issued.token())).as("29 min idle: alive, and the touch resets the clock").isPresent();
        clock.advance(Duration.ofMinutes(29));
        assertThat(service.resolve(issued.token())).as("29 min since the last touch: still alive").isPresent();
        clock.advance(Duration.ofMinutes(31));
        assertThat(service.resolve(issued.token())).as("31 min idle: expired").isEmpty();
        assertThat(store.get(SessionTokens.hash(issued.token())).isRevoked()).isTrue();
    }

    @Test
    void theAbsoluteLimitIsNeverExtendedByActivity() {
        user(1, "ops", "OPERATIONS");
        AdminSessionService.Issued issued = service.login("ops", "pw", null);
        // Used every 20 minutes for just under 8 hours: never idle, still alive.
        for (int i = 0; i < 23; i++) {
            clock.advance(Duration.ofMinutes(20));
            assertThat(service.resolve(issued.token())).as("minute %d", (i + 1) * 20).isPresent();
        }
        clock.advance(Duration.ofMinutes(25)); // 8h05m after login
        assertThat(service.resolve(issued.token())).as("past 8h, despite never being idle").isEmpty();
        assertThat(all.get(0).isRevoked()).isTrue();
    }

    @Test
    void aPasswordOnlySessionExpiresQuicklyAndIsNotSubjectToTheIdleRule() {
        user(2, "boss", "SUPER_ADMIN");
        AdminSessionService.Issued pending = service.login("boss", "pw", null);
        clock.advance(Duration.ofMinutes(4));
        assertThat(service.resolve(pending.token())).isPresent();
        clock.advance(Duration.ofMinutes(2));
        assertThat(service.resolve(pending.token())).as("6 min: past the 5 min pending limit").isEmpty();
    }

    @Test
    void repeatedWrongPasswordsLockTheAccountAndEvenTheRightPasswordIsThenRefused() {
        AdminUser u = user(1, "ops", "OPERATIONS");
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> service.login("ops", "WRONG", "9.9.9.9")).isInstanceOf(ApiException.class);
        }
        assertThat(u.isLocked(clock.instant())).isTrue();
        assertThatThrownBy(() -> service.login("ops", "pw", null))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.AUTHENTICATION_FAILED);
                    assertThat(e.getMessage()).as("no hint that it is a lockout").isEqualTo("Authentication failed.");
                });
        clock.advance(Duration.ofMinutes(16));
        assertThat(service.login("ops", "pw", null).state()).isEqualTo(SessionState.ACTIVE);
    }

    @Test
    void anUnknownUserAndAWrongPasswordAreIndistinguishableAndBothAreAudited() {
        user(1, "ops", "OPERATIONS");
        for (String name : new String[]{"nobody", "ops"}) {
            assertThatThrownBy(() -> service.login(name, "WRONG", null))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.AUTHENTICATION_FAILED);
                        assertThat(e.getMessage()).isEqualTo("Authentication failed.");
                    });
        }
        // Routed through the same Spring Security failure event TC-ADM-002's audit listener already hears.
        verify(events, org.mockito.Mockito.times(2)).publishEvent(any(
                org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent.class));
    }

    @Test
    void revokingAUsersSessionsEndsEveryLiveOneAndIsAudited() {
        user(1, "ops", "OPERATIONS");
        user(9, "root", "SUPER_ADMIN");
        AdminSessionService.Issued a = service.login("ops", "pw", null);
        AdminSessionService.Issued b = service.login("ops", "pw", null);

        assertThat(service.revokeAllFor("ops", 9L, "2.2.2.2")).isEqualTo(2);
        assertThat(service.resolve(a.token())).isEmpty();
        assertThat(service.resolve(b.token())).isEmpty();
        verify(audit).recordAdminAction(eq(9L), eq("ADMIN_SESSIONS_REVOKED"), eq("ADMIN_USER"), eq(1L), any(), any(), eq("2.2.2.2"));
    }

    @Test
    void anEnrolledAccountCannotReEnrolFromALiveSession() {
        AdminUser u = user(3, "fin", "FINANCE");
        enrol(u);
        clock.advance(Duration.ofSeconds(31));
        AdminSessionService.Issued pending = service.login("fin", "pw", null);
        AdminSessionService.Issued active = service.verifyMfa(resolve(pending), codeNow(u), null);
        assertThatThrownBy(() -> service.startEnrollment(resolve(active), null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.MFA_ALREADY_ENROLLED));
    }

    @Test
    void junkTokensResolveToNothing() {
        assertThat(service.resolve(null)).isEmpty();
        assertThat(service.resolve("")).isEmpty();
        assertThat(service.resolve("x".repeat(500))).isEmpty();
        assertThat(service.resolve("not-a-token")).isEmpty();
    }
}
