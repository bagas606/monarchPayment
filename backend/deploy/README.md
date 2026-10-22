# Deploying PPOB2 on a single host

**Staging only.** There is no real `GameProvider` yet, so the `prod` Spring profile refuses to start and
this stack runs the stub provider. See the repository README's "Go-live blockers".

```
Internet :443 -> Caddy (TLS, path allow-list) -> app :8080 -> Postgres (not published)
```

## First deploy

1. A server with Docker + the compose plugin, and a DNS A record for your domain pointing at it
   (Caddy requests the certificate on first start). Ports 80 and 443 open.
2. `cp deploy/.env.example deploy/.env` and fill it in (`PPOB2_DOMAIN`, `PPOB2_DB_PASSWORD` and `PPOB2_ADMIN_MFA_ENCRYPTION_KEY` are required).
3. From `backend/`:
   ```bash
   docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env up -d --build
   docker compose -f deploy/docker-compose.prod.yml logs -f app   # wait for "Started Ppob2Application"
   ```
   Flyway migrates from zero on first boot. HTTP Basic is disabled in this stack: admins sign in at
   `POST /admin/auth/login` (see `backend/README.md`, 2026-10-22). Nothing is seeded: `scripts/e2e/dev-seed.sql` carries dev
   credentials, so create your partner, API client and admin user deliberately instead of loading it.

## What is and is not exposed

Caddy forwards only `/api/v1/*`, `/internal/webhooks/ayolinx`, `/admin/*` and `/backoffice/*`; the bare domain redirects to `/backoffice/`, everything else is a 404.
`/actuator/*` and `/internal/settlement/*` are not reachable from outside. Postgres has no published port.

## Operating

- Update: `git pull`, then rerun the `up -d --build` command.
- Backup: `docker compose -f deploy/docker-compose.prod.yml exec postgres pg_dump -U ppob2 ppob2 > ppob2.sql`
- **Run one `app` replica.** The nonce store is in memory and the sweeps have no leader election.
- Each of `app` and `postgres` is capped at 1 GB (`mem_limit`); the JVM heap is 70% of the app's cap.
  Size the host at 2 vCPU / 4 GB or more.
