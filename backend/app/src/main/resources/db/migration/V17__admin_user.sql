-- PRD Section 22.26. Only admin_user is built in this slice — role/permission/admin_user_role/
-- role_permission (Section 42's RBAC) are not: every authenticated admin_user can drive every
-- admin action this codebase exposes, with no per-permission gating. Flagged in the README, not
-- silently narrowed.
CREATE TABLE admin_user (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username       VARCHAR(64)  NOT NULL,
    password_hash  VARCHAR(255) NOT NULL,
    mfa_enabled    BOOLEAN      NOT NULL DEFAULT false,
    status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    last_login_at  TIMESTAMPTZ,
    CONSTRAINT admin_user_username_uk UNIQUE (username)
);
