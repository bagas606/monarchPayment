-- Section 42.3: server-side sessions with idle + absolute timeout, revocation, and TOTP MFA.
--
-- Sessions are OPAQUE TOKENS, not JWTs, and only the SHA-256 of a token is stored. Reason for the
-- table over a stateless token: 42.3 requires sessions to be "revocable on logout/administrative
-- action", and a JWT cannot be revoked without keeping a server-side list anyway - at which point
-- the list IS the session store. Hashing means a database read (backup, replica, SQL injection
-- elsewhere) does not yield usable credentials.

ALTER TABLE admin_user
    -- AES-GCM ciphertext (base64) of the TOTP secret. Never the secret itself: unlike a password it
    -- must be recoverable to verify a code, so it is encrypted with a key that lives outside the
    -- database (ppob2.admin.mfa-encryption-key). NULL = no enrolment started.
    ADD COLUMN mfa_secret      TEXT,
    -- Highest TOTP time-step already accepted. A code is valid for ~30s (more with clock-skew
    -- tolerance), so without this a code seen once - over a shoulder, in a proxy log - could be
    -- replayed inside its window. A step must be strictly greater than this to be accepted.
    ADD COLUMN mfa_last_step   BIGINT,
    -- Password brute-force guard: consecutive failures, and the time until which login is refused.
    ADD COLUMN failed_logins   INT         NOT NULL DEFAULT 0,
    ADD COLUMN locked_until    TIMESTAMPTZ;

CREATE TABLE admin_session (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    token_hash           VARCHAR(64) NOT NULL,
    admin_user_id        BIGINT      NOT NULL REFERENCES admin_user(id),
    -- PENDING_MFA: password accepted, a TOTP code is still owed.
    -- PENDING_ENROLL: the role requires MFA and none is enrolled; the only thing this session may do
    --   is enrol. ACTIVE: fully authenticated.
    state                VARCHAR(16) NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL,
    last_seen_at         TIMESTAMPTZ NOT NULL,
    -- Hard ceiling, set once at creation and NEVER extended by activity. (Idle timeout is separate and
    -- slides; this is what stops a session that is used every minute from living forever.)
    absolute_expires_at  TIMESTAMPTZ NOT NULL,
    -- Wrong TOTP codes presented on THIS session. A 6-digit code is brute-forceable (10^6, and ~3 live
    -- at once), so the session is killed after a handful rather than allowing unlimited guesses.
    mfa_attempts         INT         NOT NULL DEFAULT 0,
    revoked_at           TIMESTAMPTZ,
    revoked_reason       VARCHAR(32),
    ip_address           VARCHAR(64),
    CONSTRAINT admin_session_token_uk UNIQUE (token_hash)
);

CREATE INDEX admin_session_user_idx ON admin_session (admin_user_id) WHERE revoked_at IS NULL;

-- Administrative revocation of someone else's sessions. V19's standing instruction: the SUPER_ADMIN
-- grant is explicit. Nothing else gets it - ending another person's session is a SUPER_ADMIN act.
INSERT INTO permission (code) VALUES ('session:revoke');
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM permission p, role r WHERE r.code = 'SUPER_ADMIN' AND p.code = 'session:revoke';
