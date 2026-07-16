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

The opt-in OCR service is reachable only on the private Compose network. Docker Engine 28's isolated
gateway mode prevents the OCR-only container from reaching the host or external networks. It accepts
authenticated raw JPEG/PNG/WebP bytes from the backend, returns bounded recognized lines, and has no Caddy route.
Paddle models are fetched from pinned BOS artifacts with SHA-256 verification while the image is
built, then baked into the image under an explicit model-cache path; the runtime filesystem is
read-only, and production inference never downloads models or calls an external OCR/AI provider.

## Prerequisites

- Docker Engine 28 or newer with Docker Compose 2.33.1 or newer. The bundle pins the backend's
  default gateway to the normal application network while keeping OCR on a host-isolated internal
  bridge with no gateway address.
- A Linux AMD64 host for the released image set. The OCR image additionally requires AVX on every
  assigned processor.
- A signed release manifest verified with its exact tag-bound identity, with all three
  `CONNEX_*_IMAGE` values set to the manifest's immutable digests (see
  [RELEASE.md](RELEASE.md)), or a local build through the `docker-compose.build.yml` overlay.
- Generated secrets and — for production — a **verified-TLS database** (the app is fail-closed:
  outside dev it requires `sslMode=VERIFY_CA` or `VERIFY_IDENTITY`).

## Configure

Install the template as a mode-0600 `deploy/.env`:

```bash
cd deploy
umask 077
install -m 0600 silo.env.example .env
# or: install -m 0600 onprem.env.example .env
```

Fill every `REPLACE_*` value. Generate secrets, e.g.:

```bash
openssl rand -base64 32   # CONNEX_SECRET_STORE_MASTER_KEY
openssl rand -hex 32      # CONNEX_AUDIT_INTEGRITY_HMAC_SECRET
openssl rand -hex 32      # CONNEX_OCR_SERVICE_TOKEN
```

Business-card scanning is disabled by default so deployments that cannot spare the OCR sidecar's
resources do not start it. Paddle's current CPU wheel also requires AVX support;
the sidecar exits cleanly and scanning remains unavailable when that requirement is not met. To
enable it, set `CONNEX_BUSINESS_CARD_SCANNING_ENABLED=true`, keep
`CONNEX_OCR_BASE_URL=http://ocr:8090`, set
`CONNEX_OCR_PLAIN_HTTP_PRIVATE_HOST=ocr` to bind plaintext transport to that single-label service
on Compose's isolated `ocr_internal` network, generate a unique 32+ character
`CONNEX_OCR_SERVICE_TOKEN`, and set `COMPOSE_PROFILES=ocr` in `.env`:

```bash
docker compose pull
docker compose up -d
```

The same token is supplied to the backend through `.env` and to the OCR container by Compose.
Leaving `COMPOSE_PROFILES` empty does not start the sidecar. Keeping the profile in `.env` ensures
ordinary `pull`, `up`, `stop`, and migration commands continue to include OCR. Leaving scanning
disabled or losing OCR readiness disables automatic extraction while
manual image retention and reviewed import remain available when private storage is ready. Losing
private binary-storage readiness disables both scanning and import. The OCR container is capped at two
CPUs, 2 GiB memory, 128 processes, one concurrent inference, and eight bounded HTTP handlers by
default (`CONNEX_OCR_MAX_REQUEST_HANDLERS`). Excess concurrent inference receives `429`, excess
connections receive `503`, and a slow request cannot hold the inference slot. The backend uses the bearer-authenticated `/ready`
probe, while Docker's unauthenticated `/health` probe exposes only readiness, active-inference state,
and an opaque per-inference generation. A persistent supervisor continuously probes the worker, terminates native startup that has
not become ready within `CONNEX_OCR_STARTUP_TIMEOUT_SECONDS=180`, and hard-kills an active or
unresponsive worker after `CONNEX_OCR_REQUEST_TIMEOUT_SECONDS=12`. It restarts failed workers with
bounded exponential backoff, resetting to one second only after 30 seconds of stable readiness;
Compose's `unless-stopped` policy restores the supervisor after Docker
daemon and host restarts. Keep that sidecar deadline strictly below the backend's
`CONNEX_OCR_REQUEST_TIMEOUT=15s`; overriding them in the opposite order lets abandoned inference
occupy the only worker after the backend has timed out. Compose derives the sidecar byte, width,
height, and pixel limits from `CONNEX_BUSINESS_CARD_IMAGE_MAX_BYTES`,
`CONNEX_BUSINESS_CARD_IMAGE_MAX_WIDTH`, `CONNEX_BUSINESS_CARD_IMAGE_MAX_HEIGHT`, and
`CONNEX_BUSINESS_CARD_IMAGE_MAX_PIXELS`, so one configured boundary applies before and after the
private service hop.

The backend applies a process-wide budget of five scans per minute and a cross-workspace principal
budget of three scans per minute by default (`CONNEX_BUSINESS_CARD_MAX_GLOBAL_SCANS_PER_MINUTE` and
`CONNEX_BUSINESS_CARD_MAX_SCANS_PER_MINUTE`), leaving capacity that one principal cannot consume.
Imports remain limited to 12 per user and workspace per minute
(`CONNEX_BUSINESS_CARD_MAX_IMPORTS_PER_MINUTE`). These limits are maintained in each backend process;
deployments with multiple backend replicas should enforce equivalent aggregate limits at their
trusted ingress or replace this local limiter with a shared admission service.
Before a browser sends private multipart data, it receives a two-minute submission lease
(`CONNEX_BUSINESS_CARD_RESERVATION_LEASE`). Each user may hold at most four unsubmitted leases per
workspace (`CONNEX_BUSINESS_CARD_MAX_OUTSTANDING_RESERVATIONS`); expired leases are reclaimed on the
next reservation and by the scheduled sweep. Completed idempotency claims use a 24-hour replay
horizon (`CONNEX_BUSINESS_CARD_IDEMPOTENCY_RETENTION`). A catalog-aware sweep removes up to 1,000
expired claims per workspace pass by default
(`CONNEX_BUSINESS_CARD_IDEMPOTENCY_CLEANUP_BATCH_SIZE`) without loading private import drafts into
the control plane. Keep the submission lease shorter than the replay horizon.

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
`/var/lib/connex/objects`. Include that volume in backups and restores alongside MySQL. The backend
image uses stable numeric UID/GID `10001:10001`; preserve that ownership when restoring the volume.
The filesystem provider is for a single backend replica because its active-reader leases are
process-local. Use S3-compatible storage before adding backend replicas. The backend
reserves each in-flight write against the volume and stops accepting writes or reporting storage
ready when the configured free-space floor would be crossed. The default floor is 1 GiB; size
`CONNEX_OBJECT_STORAGE_FILESYSTEM_MIN_FREE_BYTES` to leave enough room for operational recovery and
monitor the volume independently. To use an S3-compatible service instead, set:

```dotenv
CONNEX_OBJECT_STORAGE_PROVIDER=s3
CONNEX_OBJECT_STORAGE_S3_BUCKET=REPLACE_PRIVATE_BUCKET
CONNEX_OBJECT_STORAGE_S3_REGION=REPLACE_REGION
# Optional for a non-AWS implementation:
CONNEX_OBJECT_STORAGE_S3_ENDPOINT=https://objects.REPLACE.example.com
CONNEX_OBJECT_STORAGE_S3_PATH_STYLE=false
CONNEX_OBJECT_STORAGE_S3_API_CALL_TIMEOUT=15s
CONNEX_OBJECT_STORAGE_S3_API_CALL_ATTEMPT_TIMEOUT=5s
CONNEX_OBJECT_STORAGE_AMBIGUOUS_WRITE_CLEANUP_DELAY_MS=60000
CONNEX_OBJECT_STORAGE_MAX_CONCURRENT_WRITES=4
CONNEX_OBJECT_STORAGE_MAX_CONCURRENT_READS=32
CONNEX_OBJECT_STORAGE_MAX_CONCURRENT_READS_PER_USER=4
CONNEX_OBJECT_STORAGE_READ_TIMEOUT_MS=30000
CONNEX_OBJECT_STORAGE_MAX_PENDING_TENANT_AMBIGUOUS_WRITE_CLEANUPS=100
```

S3 credentials come from the AWS SDK default credential chain, such as an instance/task role or
the standard `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, and optional `AWS_SESSION_TOKEN`
environment variables. Keep the bucket private, disable public ACLs/policies, require TLS, grant
the backend object read/write/delete plus bucket-head and bucket-versioning-read access, including
the reserved `connex-readiness/` probe prefix, and enable the provider's at-rest encryption. Storage
readiness writes a unique probe, checksum-reads it, and deletes it; denial or corruption at any stage
keeps uploads and business-card import unavailable. Bucket versioning must never have been enabled, including a
suspended state: an unversioned delete otherwise retains recoverable noncurrent PII while the quota
ledger records physical deletion. The backend verifies this before every write/delete and reports
storage unavailable if the provider cannot prove the state. A custom endpoint may use HTTP only for an explicitly
trusted service under the Spring `dev` profile; startup rejects plain HTTP in every other profile.
The attempt timeout must be positive and no greater than the total API-call timeout. The ambiguous
write cleanup delay must exceed the total call timeout so a timed-out write cannot finish after its
compensating delete; the default waits 60 seconds before the first of two successful delete passes.
The provider selection is immutable after the first managed write. Do not switch between
`filesystem` and `s3` by configuration or copy the filesystem tree verbatim: filesystem files have
an implementation suffix that S3 keys do not. A provider cutover requires a checksummed,
key-translating migration, read verification, rollback point, and retained source copy.

The default per-file limit is 25 MiB
(`CONNEX_OBJECT_STORAGE_MAX_UPLOAD_BYTES=26214400`), with a 27 MiB multipart request envelope
(`CONNEX_UPLOAD_MAX_BODY_BYTES=28311552`). Image uploads also default to a 40-million-pixel decode
limit (`CONNEX_OBJECT_STORAGE_MAX_IMAGE_PIXELS=40000000`), a shared 256 MiB estimated decode-memory
budget (`CONNEX_OBJECT_STORAGE_MAX_IMAGE_WORKING_BYTES=268435456`), and two concurrent decode slots
(`CONNEX_OBJECT_STORAGE_MAX_CONCURRENT_IMAGE_DECODES=2`) across business cards and managed images.
Images with more than four channels, samples deeper than eight bits, or a working-set estimate above
that shared budget are rejected before full decode. Each workspace defaults to 10 GiB and
10,000 tenant-owned objects (`CONNEX_OBJECT_STORAGE_MAX_WORKSPACE_BYTES=10737418240` and
`CONNEX_OBJECT_STORAGE_MAX_WORKSPACE_OBJECTS=10000`). Admission is serialized in the tenant
catalog so concurrent uploads cannot overrun either limit. Legacy public-upload paths are not
treated as managed objects; the maintenance migration reserves their exact validated byte sizes
when it moves them into private storage.

Managed downloads hold one global and one authenticated-user admission lease until close, with
defaults of 32 global reads, four reads per user, and a 30-second hard stream deadline
(`CONNEX_OBJECT_STORAGE_MAX_CONCURRENT_READS`,
`CONNEX_OBJECT_STORAGE_MAX_CONCURRENT_READS_PER_USER`, and
`CONNEX_OBJECT_STORAGE_READ_TIMEOUT_MS`). The MVC streaming executor is bounded to the same global
limit. Filesystem deletion waits for active readers, so quota is not released while deleted bytes
remain held by an open descriptor.

Object removal uses durable database queues. Tenant-owned objects are recorded in that tenant
catalog's `object_deletion_queue`; control-plane user-profile objects use
`user_object_deletion_queue`. The metadata update and deletion intent commit atomically. The backend
retries due rows every 60 seconds. Before every provider write, Connex commits a delayed two-pass
tombstone in an isolated transaction; the metadata transaction cancels it only after the write is
confirmed. A rollback, process exit, database cancellation failure, or ambiguous provider response
therefore leaves durable cleanup intent. The first successful delete is rescheduled for confirmation,
and only the second successful delete finalizes the row. Provider writes are admitted without waiting,
so saturated storage cannot accumulate transactions holding database connections; a workspace stops
starting new writes when its ambiguous-write cleanup backlog reaches the configured hard ceiling.
Profile-image replacements are limited to 12 per user per hour per
backend process and stop while two prior objects for that user remain queued; the shared backlog cap
keeps storage bounded across replicas during deletion degradation. Monitor backlog age and attempts
without selecting the private object keys. Tenant quota is released only after the provider confirms
physical deletion; a failed deletion remains charged and queued:

```sql
SELECT workspace_id, COUNT(*) AS pending, MAX(attempts) AS max_attempts,
       MAX(delete_passes_remaining) AS max_delete_passes,
       MIN(created_at) AS oldest
FROM object_deletion_queue
GROUP BY workspace_id;

SELECT COUNT(*) AS pending, MAX(attempts) AS max_attempts,
       MAX(delete_passes_remaining) AS max_delete_passes,
       MIN(created_at) AS oldest
FROM user_object_deletion_queue;
```

For defense in depth, periodic object inventories may compare opaque tokens with the `url`,
`image_url`, `logo_url`, and `profile_picture_url` values in the appropriate tenant or control
catalog. Never delete solely from object age, and never emit object keys or upload metadata into
logs. Existing database rows that reference legacy frontend-local upload paths require the one-shot
migration below.

#### Migrating legacy public uploads

Older releases wrote `attachments`, `contact-pictures`, `company-logos`, and `profile-pictures`
under the frontend's `CONNEX_UPLOADS_DIR` (or `/app/public` by default). Historical Compose bundles
did not mount those directories on a durable volume. Copy them out of the still-running old
frontend container before pulling it down; if that container has already been recreated, the files
may already be gone and the legacy database references must remain unchanged until the files are
recovered from backup.

Run the migration during a maintenance window with all HTTP writers stopped. The command never
deletes its source files, routes every workspace through its active catalog placement, verifies the
stored byte length and SHA-256 before changing a database reference, and is safe to rerun after an
interruption. Do not set migration mode permanently in `deploy/.env` and do not run more than one
migrator at a time.

1. Stage the four directories on the host. If the old deployment used a custom or mounted
   `CONNEX_UPLOADS_DIR`, copy from that location instead. After staging and checksum verification,
   verify the target release manifest and put its three exact `CONNEX_*_IMAGE` digest references in
   the mode-0600 `.env`, but do not run `up`; pulling the target images does not replace the
   still-running old containers.

   ```bash
   export MIGRATION_ID="$(date -u +%Y%m%dT%H%M%SZ)-$(openssl rand -hex 4)"
   export LEGACY_UPLOADS="/srv/connex-legacy-uploads-${MIGRATION_ID}"
   export LEGACY_VOLUME="connex_legacy_uploads_${MIGRATION_ID}"
   test ! -e "$LEGACY_UPLOADS"
   install -d -m 0700 "$LEGACY_UPLOADS"
   OLD_FRONTEND=$(docker compose ps -q frontend)
   test -n "$OLD_FRONTEND"
   for directory in attachments contact-pictures company-logos profile-pictures; do
     install -d -m 0700 "$LEGACY_UPLOADS/$directory"
     docker cp "$OLD_FRONTEND:/app/public/$directory/." "$LEGACY_UPLOADS/$directory/"
   done
   (cd "$LEGACY_UPLOADS" && find . -type f ! -path ./SHA256SUMS -print0 \
     | sort -z | xargs -0 -r sha256sum > SHA256SUMS)
   (cd "$LEGACY_UPLOADS" && sha256sum -c SHA256SUMS)
   docker compose pull
   ```

2. Stop ingress and application writers, confirm only MySQL remains, then back up MySQL, the staged
   legacy directory, and the private object store as one recovery point. Keep the database running
   for the migration. The complete target image set was pulled before this cutover. Copy the staged
   tree into the uniquely named Docker volume and set ownership inside Docker's
   user namespace. This works with rootless and user-namespace-remapped daemons without assuming
   that the container UID is also a valid host UID. On SELinux hosts, the temporary source bind uses
   a private relabel; the original staged backup remains the recovery source.

   ```bash
   docker compose stop caddy frontend backend ocr
   test "$(docker compose ps --services --status running)" = "db"
   (cd "$LEGACY_UPLOADS" && sha256sum -c SHA256SUMS)
   BACKEND_UID=$(docker compose run --rm --no-deps --entrypoint id backend -u)
   BACKEND_GID=$(docker compose run --rm --no-deps --entrypoint id backend -g)
   SOURCE_MOUNT_MODE=ro
   if command -v getenforce >/dev/null && [ "$(getenforce)" = Enforcing ]; then
     SOURCE_MOUNT_MODE=ro,Z
   fi
   docker volume create "$LEGACY_VOLUME"
   docker compose run --rm --no-deps --user 0 --entrypoint sh \
     -e TARGET_UID="$BACKEND_UID" \
     -e TARGET_GID="$BACKEND_GID" \
     -v "$LEGACY_UPLOADS:/source:$SOURCE_MOUNT_MODE" \
     -v "$LEGACY_VOLUME:/legacy" \
     backend \
     -c 'cp -a /source/. /legacy/ && chown -R "$TARGET_UID:$TARGET_GID" /legacy && find /legacy -type d -exec chmod 0700 {} + && find /legacy -type f -exec chmod 0600 {} +'
   ```

3. Run the metadata dry run. Flyway still applies any pending forward schema migrations before the
   maintenance runner starts, which is why the database backup and maintenance window are required.
   The runner itself validates paths, source bytes, image decoding, deterministic target collisions,
   storage readiness, and projected workspace quotas without changing media, quota, or record
   metadata. Maintenance mode disables schedulers, asynchronous execution, bootstrap provisioning,
   and secret-rewrap startup runners.

   ```bash
   docker compose run --rm --no-deps \
     -e SPRING_MAIN_WEB_APPLICATION_TYPE=none \
     -e CONNEX_MAINTENANCE_MODE=legacy-upload-migration \
     -e CONNEX_OBJECT_STORAGE_LEGACY_MIGRATION_MODE=DRY_RUN \
     -e CONNEX_OBJECT_STORAGE_LEGACY_UPLOADS_ROOT=/legacy \
     -v "$LEGACY_VOLUME:/legacy:ro" \
     backend
   ```

   The retired image routes accepted GIF and AVIF based only on browser-provided metadata. The new
   private image pipeline fully decodes JPEG, PNG, and WebP only. A dry-run failure for a legacy GIF
   or AVIF must be resolved by converting the staged copy to a supported static format, or by
   replacing the affected image manually; the migrator never silently preserves an unverified
   image.

4. After the dry run reports zero failures, run the confirmed migration.

   ```bash
   docker compose run --rm --no-deps \
     -e SPRING_MAIN_WEB_APPLICATION_TYPE=none \
     -e CONNEX_MAINTENANCE_MODE=legacy-upload-migration \
     -e CONNEX_OBJECT_STORAGE_LEGACY_MIGRATION_MODE=MIGRATE \
     -e CONNEX_OBJECT_STORAGE_LEGACY_MIGRATION_APPLY_CONFIRMATION=MIGRATE_LEGACY_UPLOADS \
     -e CONNEX_OBJECT_STORAGE_LEGACY_UPLOADS_ROOT=/legacy \
     -v "$LEGACY_VOLUME:/legacy:ro" \
     backend
   ```

5. Rerun the dry-run command and require `failed=0` and `remaining=0`. Start the stack, then use an
   authenticated account in each affected workspace to download an attachment and render a contact
   image, company logo, and user profile picture through their new `/api` URLs.

   ```bash
   docker compose up -d
   ```

6. Only after those checks and a retained backup may the migration volume, staged legacy files, and
   any old public upload mount be removed. Leaving files below a served `public` directory keeps the
   old URLs unauthenticated.

   ```bash
   docker volume rm "$LEGACY_VOLUME"
   ```

## Run

```bash
docker compose pull        # fetch the verified CONNEX_*_DIGEST image set from GHCR
docker compose up -d
curl -s http://localhost/api/version      # {"version":"<tag>",...}
```

Production `deploy/.env` must set `CONNEX_BACKEND_DIGEST`, `CONNEX_FRONTEND_DIGEST`, and
`CONNEX_OCR_DIGEST` to the 64 lowercase hexadecimal characters after `sha256:` in the verified
signed release manifest. The production Compose bundle fixes the registry, image names, and digest
algorithm, so these variables cannot substitute a tag or alternate repository. Roll forward by
verifying a new manifest, replacing the complete three-digest set, and re-running `pull` plus
`up -d`.

## Local evaluation (not for production)

For a zero-config local trial against the bundled MySQL, build from source and use the eval env,
which activates `SPRING_PROFILES_ACTIVE=dev` to relax the fail-closed transport/secret requirements:

```bash
cd deploy
cp eval.env.example .env
CONNEX_VERSION=dev docker compose -f docker-compose.yml -f docker-compose.build.yml up --build -d
curl -s http://localhost:8088/api/version
```

To include local OCR, set the OCR token and timeout variables described above and set
`COMPOSE_PROFILES=ocr` in `.env` before starting the stack.

## CI coverage

[`deploy-smoke.yml`](../.github/workflows/deploy-smoke.yml) validates the compose bundle and boots
the backend under **all three deployment profiles** (`saas`, `silo`, `on-prem`), smoking
`/api/version` + `/api/capabilities` for each so a profile-specific startup regression fails CI. The
profile-boot job runs with dev-relaxed transport to isolate the profile dimension; a full
image-and-Caddy end-to-end bring-up is exercised locally and on release rather than per-PR.
