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
browser ──▶ caddy :80 ─┬─ /api/*, /saml2/*  ─▶ backend:8080 ───▶ db:3306
                       └─ everything else    ─▶ frontend:3000
                                                    backend ──▶ ocr:8090 (optional profile)
```

The opt-in OCR service is reachable only on the private Compose network. It accepts authenticated raw
JPEG/PNG/WebP bytes from the backend, returns bounded recognized lines, and has no Caddy route.
Paddle models are fetched from pinned BOS artifacts with SHA-256 verification while the image is
built, then baked into the image under an explicit model-cache path; the runtime filesystem is
read-only, and production inference never downloads models or calls an external OCR/AI provider.

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
openssl rand -hex 32      # CONNEX_OCR_SERVICE_TOKEN
```

Business-card scanning is disabled by default so deployments that cannot spare the OCR sidecar's
resources do not start it. Paddle's current CPU wheel also requires an x86-64 host with AVX support;
the sidecar exits cleanly and scanning remains unavailable when that requirement is not met. To
enable it, set `CONNEX_BUSINESS_CARD_SCANNING_ENABLED=true`, keep
`CONNEX_OCR_BASE_URL=http://ocr:8090`, generate a unique 32+ character
`CONNEX_OCR_SERVICE_TOKEN`, and start Compose with the `ocr` profile:

```bash
docker compose --profile ocr pull
docker compose --profile ocr up -d
```

The same token is supplied to the backend through `.env` and to the OCR container by Compose.
Starting the base stack without `--profile ocr` does not interpolate a required OCR secret or start
the sidecar. Disabling the feature or losing private binary-storage readiness disables both card
scanning and import. Losing OCR disables automatic scanning while manual image retention and
reviewed import remain available when private storage is ready. The OCR container is capped at two
CPUs, 2 GiB memory, 128 processes, and one concurrent inference; excess concurrent work receives
`429` instead of entering an unbounded queue. An inference that exceeds the configured deadline
terminates the sidecar so the container restart policy replaces the wedged native worker.

The backend also applies per-user, per-workspace fixed-window limits of 12 scans and 12 imports per
minute by default (`CONNEX_BUSINESS_CARD_MAX_SCANS_PER_MINUTE` and
`CONNEX_BUSINESS_CARD_MAX_IMPORTS_PER_MINUTE`). These limits are maintained in each backend process;
deployments with multiple backend replicas should enforce equivalent aggregate limits at their
trusted ingress or replace this local limiter with a shared admission service.

`CONNEX_DEPLOYMENT_PROFILE` drives fail-closed posture enforcement (issue #497): `saas` forbids the
internal-access opt-ins (bootstrap, private SSO issuer hosts, internal AI/SMTP hosts); `silo` and
`on-prem` allow them. Leaving it unset boots with a warning (soft-launch).

### Database TLS

The bundled MySQL is included for convenience. **Production requires TLS to the database.** Either
configure TLS on the bundled MySQL and trust its CA in `CONNEX_DB_URL`, or point `CONNEX_DB_URL` at a
managed database that terminates TLS. On-prem operators commonly use their own database.

### Private object storage

Connex stores attachments, contact pictures, company logos, user profile pictures, and scanned
business-card source images privately. Downloads pass through authenticated, tenant-authorized
backend endpoints; the object store must not be exposed as a public origin.

The deployment templates use the filesystem provider and mount the Docker `object_data` volume at
`/var/lib/connex/objects`. Include that volume in backups and restores alongside MySQL. To use an
S3-compatible service instead, set:

```dotenv
CONNEX_OBJECT_STORAGE_PROVIDER=s3
CONNEX_OBJECT_STORAGE_S3_BUCKET=REPLACE_PRIVATE_BUCKET
CONNEX_OBJECT_STORAGE_S3_REGION=REPLACE_REGION
# Optional for a non-AWS implementation:
CONNEX_OBJECT_STORAGE_S3_ENDPOINT=https://objects.REPLACE.example.com
CONNEX_OBJECT_STORAGE_S3_PATH_STYLE=false
```

S3 credentials come from the AWS SDK default credential chain, such as an instance/task role or
the standard `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, and optional `AWS_SESSION_TOKEN`
environment variables. Keep the bucket private, disable public ACLs/policies, require TLS, grant
the backend only object read/write/delete/list access for this bucket, and enable the provider's
at-rest encryption and lifecycle controls. A custom endpoint may use HTTP only for an explicitly
trusted local development service; production endpoints should use HTTPS.

The default per-file limit is 25 MiB
(`CONNEX_OBJECT_STORAGE_MAX_UPLOAD_BYTES=26214400`), with a 27 MiB multipart request envelope
(`CONNEX_UPLOAD_MAX_BODY_BYTES=28311552`). Image uploads also default to a 40-million-pixel decode
limit (`CONNEX_OBJECT_STORAGE_MAX_IMAGE_PIXELS=40000000`) and two concurrent full decodes
(`CONNEX_OBJECT_STORAGE_MAX_CONCURRENT_IMAGE_DECODES=2`). Each workspace defaults to 10 GiB and
10,000 tenant-owned objects (`CONNEX_OBJECT_STORAGE_MAX_WORKSPACE_BYTES=10737418240` and
`CONNEX_OBJECT_STORAGE_MAX_WORKSPACE_OBJECTS=10000`). Admission is serialized in the tenant
catalog so concurrent uploads cannot overrun either limit. During the quota migration, legacy
managed contact and company images without stored byte metadata are conservatively charged at the
per-file limit until they are replaced.

Object removal uses durable database queues: tenant-owned objects are recorded in
`object_deletion_queue` in each tenant catalog, while user profile objects are recorded in the
control-plane `user_object_deletion_queue`. The backend retries due rows every 60 seconds. Monitor
backlog age and attempts without selecting the private object keys. Tenant quota is released only
after the provider confirms physical deletion; a failed deletion remains charged and queued:

```sql
SELECT workspace_id, COUNT(*) AS pending, MAX(attempts) AS max_attempts,
       MIN(created_at) AS oldest
FROM object_deletion_queue
GROUP BY workspace_id;

SELECT COUNT(*) AS pending, MAX(attempts) AS max_attempts,
       MIN(created_at) AS oldest
FROM user_object_deletion_queue;
```

A hard process or host failure after an object is written but before its database mutation
commits can leave an unreferenced object that no transaction callback can enqueue. Periodically
inventory objects older than a conservative grace period and compare each opaque URL token with
the `url`, `image_url`, `logo_url`, and `profile_picture_url` values in the appropriate tenant or
control catalog before deleting it. Never delete solely from object age, and never emit object
keys or upload metadata into logs. Existing database rows that reference legacy frontend-local
upload paths are not automatically copied into the private store; preserve and migrate their
source files before retiring an older deployment.

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

To include local OCR, set the three OCR variables described above and add `--profile ocr` before
`up`.

## CI coverage

[`deploy-smoke.yml`](../.github/workflows/deploy-smoke.yml) validates the compose bundle and boots
the backend under **all three deployment profiles** (`saas`, `silo`, `on-prem`), smoking
`/api/version` + `/api/capabilities` for each so a profile-specific startup regression fails CI. The
profile-boot job runs with dev-relaxed transport to isolate the profile dimension; a full
image-and-Caddy end-to-end bring-up is exercised locally and on release rather than per-PR.
