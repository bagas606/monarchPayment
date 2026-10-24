# Deploying PPOB2 on a single host

**Staging only.** There is no real `GameProvider` yet, so the `prod` Spring profile refuses to start and
this stack runs the stub provider. See the repository README's "Go-live blockers".

```
Internet :443 -> Caddy (TLS, path allow-list) -> app :8080 -> Postgres (not published)
```

## First deploy

1. A server with Docker + the compose plugin, and a DNS A record for your domain pointing at it
   (Caddy requests the certificate on first start). Ports 80 and 443 open.
2. `cp deploy/.env.example deploy/.env` and fill it in (`PPOB2_DOMAIN`, `PPOB2_DB_PASSWORD`, `PPOB2_ADMIN_MFA_ENCRYPTION_KEY` and the `PPOB2_SECRET_KEYS` / `PPOB2_SECRET_ACTIVE_KEY_ID` pair are required; the app will not boot without them).
3. From `backend/`:
   ```bash
   docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env up -d --build
   docker compose -f deploy/docker-compose.prod.yml logs -f app   # wait for "Started Ppob2Application"
   ```
   Flyway migrates from zero on first boot. HTTP Basic is disabled in this stack: admins sign in at
   `POST /admin/auth/login` (see `backend/README.md`, 2026-10-22). Nothing is seeded: `scripts/e2e/dev-seed.sql` carries dev
   credentials, so create your partner, API client and admin user deliberately instead of loading it.

## The first admin

Nothing is seeded and HTTP Basic is off, so the very first SUPER_ADMIN is created by hand, once. The
password is hashed by Postgres itself (`pgcrypto`'s `bf` is BCrypt, which is what the app checks), so the
plain password never needs to be written into a SQL file. Pick a password of your own; do not reuse one
that appears anywhere in this repository.

```bash
read -rs ADMIN_PW   # type it; not echoed
docker compose -f deploy/docker-compose.prod.yml exec -T postgres \
  psql -U ppob2 -d ppob2 -v ON_ERROR_STOP=1 -v pw="$ADMIN_PW" <<'SQL'
CREATE EXTENSION IF NOT EXISTS pgcrypto;
INSERT INTO admin_user (username, password_hash) VALUES ('admin', crypt(:'pw', gen_salt('bf', 10)));
INSERT INTO admin_user_role (admin_user_id, role_id)
  SELECT u.id, r.id FROM admin_user u, role r WHERE u.username = 'admin' AND r.code = 'SUPER_ADMIN';
SQL
unset ADMIN_PW
```

(The hash form was checked: a `crypt(..., gen_salt('bf', 10))` value verifies under Spring's
`BCryptPasswordEncoder`. The compose invocation itself has not been run - the image was never built.)

Then open `https://<your domain>/backoffice/`, sign in, and the console walks you through enrolling an
authenticator app (SUPER_ADMIN cannot use the console without one). Create further admins and assign their
roles the same way, in SQL: there is no user-management screen. If someone loses their authenticator, an
operator clears their enrolment (`UPDATE admin_user SET mfa_secret = NULL, mfa_enabled = false,
mfa_last_step = NULL WHERE username = '...'`) and they enrol again at next sign-in. **Back up
`PPOB2_ADMIN_MFA_ENCRYPTION_KEY` with the database**: without it every enrolled authenticator is unusable.

## Partner secrets

A partner's HMAC secret and its webhook-signing secret are stored **encrypted** (AES-256-GCM, format
`v1.<keyId>.<ciphertext>`, bound to the row they belong to) under the key ring in `PPOB2_SECRET_KEYS`. The
database alone cannot reproduce a partner's signature. SQL therefore cannot set them: the rows are created
unprovisioned and the secret is set through the admin API by a SUPER_ADMIN.

```sql
-- once per partner / client; no secret in SQL
INSERT INTO partner (code, name, channel_id) SELECT 'ACME', 'Acme', id FROM channel WHERE code = 'RESELLER_API';
INSERT INTO api_client (client_id, partner_id) SELECT 'acme-client', id FROM partner WHERE code = 'ACME';
```
```bash
# TOKEN = a signed-in SUPER_ADMIN session (POST /admin/auth/login, then /admin/auth/mfa/verify), sent as a Bearer token
curl -X PUT https://<domain>/admin/api-clients/acme-client/secret   -H "Authorization: Bearer $TOKEN"   # generates one
curl -X PUT https://<domain>/admin/partners/ACME/webhook-secret      -H "Authorization: Bearer $TOKEN"
```
With no body a 256-bit secret is generated and returned **once**; hand it to the partner over a secure channel.
(`-d '{"secret":"..."}'` stores one the partner chose, and does not echo it.) Every change is audited with a
fingerprint, never the secret. Until provisioned, a client's requests are refused and no webhook is sent to its
partner; a log line says which.

**Key management.** `PPOB2_SECRET_KEYS` is `k1:<base64>[,k2:<base64>]` and `PPOB2_SECRET_ACTIVE_KEY_ID` names the
one new values are written with. To rotate: add `k2`, set the active id to `k2`, restart, then re-run the two `PUT`s
for each partner (the old key must stay listed until every secret has been rewritten). **Back this key ring up
with the database - losing a key makes every secret written under it unrecoverable**, and each partner then
needs a new one. This is a config-held key, not a KMS: anyone who can read both the database and the app's
environment can decrypt. Moving the key behind AWS/GCP KMS or Vault means supplying another `SecretKeyProvider`
bean; stored values and columns do not change.

## What is and is not exposed

Caddy forwards only `/api/v1/*`, `/internal/webhooks/ayolinx`, `/admin/*` and `/backoffice/*`; the bare domain redirects to `/backoffice/`, everything else is a 404.
`/actuator/*` and `/internal/settlement/*` are not reachable from outside. Postgres has no published port.

## Operating

- Update: `git pull`, then rerun the `up -d --build` command.
- Backup: `docker compose -f deploy/docker-compose.prod.yml exec postgres pg_dump -U ppob2 ppob2 > ppob2.sql`
- **Run one `app` replica.** The nonce store is in memory and the sweeps have no leader election.
- Each of `app` and `postgres` is capped at 1 GB (`mem_limit`); the JVM heap is 70% of the app's cap.
  Size the host at 2 vCPU / 4 GB or more.
