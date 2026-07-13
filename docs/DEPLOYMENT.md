# Deploying Connex (silo & on-prem bundle)

The [`deploy/`](../deploy) bundle turns the released [container images](RELEASE.md) into a
running, single-origin deployment. The **same bundle** serves a Connex-operated **silo** and a
customer-operated **on-prem** install — only the profile and secrets differ (issue #499, epic #502).

## Topology

A single [Caddy](../deploy/Caddyfile) ingress fronts everything on one origin:

- `/api/*` (including the `/api/ws` WebSocket) and `/saml2/*` → the backend
- everything else → the frontend

Single-origin means cookies, WebAuthn (RP = the serving host), and realtime all work without a
browser-facing backend URL. Internally the frontend still talks to `backend:8080` directly.

```
browser ──▶ caddy :80 ─┬─ /api/*, /saml2/*  ─▶ backend:8080 ─▶ db:3306
                       └─ everything else    ─▶ frontend:3000
```

## Prerequisites

- Docker + Docker Compose.
- A released version tag (see [RELEASE.md](RELEASE.md)) for `CONNEX_VERSION`, or build locally with
  the `docker-compose.build.yml` overlay.
- Generated secrets and — for production — a **verified-TLS database** (the app is fail-closed:
  outside dev it requires `sslMode=VERIFY_CA` or `VERIFY_IDENTITY`).

## Configure

Pick the template for your deployment and copy it to `deploy/.env`:

```bash
cd deploy
cp silo.env.example .env      # Connex-operated silo
# or: cp onprem.env.example .env   # customer-operated on-prem
```

Fill every `REPLACE_*` value. Generate secrets, e.g.:

```bash
openssl rand -base64 32   # CONNEX_SECRET_STORE_MASTER_KEY
openssl rand -hex 32      # CONNEX_AUDIT_INTEGRITY_HMAC_SECRET
```

`CONNEX_DEPLOYMENT_PROFILE` drives fail-closed posture enforcement (issue #497): `saas` forbids the
internal-access opt-ins (bootstrap, private SSO issuer hosts, internal AI/SMTP hosts); `silo` and
`on-prem` allow them. Leaving it unset boots with a warning (soft-launch).

### Database TLS

The bundled MySQL is included for convenience. **Production requires TLS to the database.** Either
configure TLS on the bundled MySQL and trust its CA in `CONNEX_DB_URL`, or point `CONNEX_DB_URL` at a
managed database that terminates TLS. On-prem operators commonly use their own database.

## Run

```bash
docker compose pull        # fetch the pinned CONNEX_VERSION images from GHCR
docker compose up -d
curl -s http://localhost/api/version      # {"version":"<tag>",...}
```

Roll forward by bumping `CONNEX_VERSION` and re-running `pull` + `up -d`.

## Local evaluation (not for production)

For a zero-config local trial against the bundled MySQL, build from source and use the eval env,
which activates `SPRING_PROFILES_ACTIVE=dev` to relax the fail-closed transport/secret requirements:

```bash
cd deploy
cp eval.env.example .env
CONNEX_VERSION=dev docker compose -f docker-compose.yml -f docker-compose.build.yml up --build -d
curl -s http://localhost:8088/api/version
```

## CI coverage

[`deploy-smoke.yml`](../.github/workflows/deploy-smoke.yml) validates the compose bundle and boots
the backend under **all three deployment profiles** (`saas`, `silo`, `on-prem`), smoking
`/api/version` + `/api/capabilities` for each so a profile-specific startup regression fails CI. The
profile-boot job runs with dev-relaxed transport to isolate the profile dimension; a full
image-and-Caddy end-to-end bring-up is exercised locally and on release rather than per-PR.
