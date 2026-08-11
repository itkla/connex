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
                                                    backend ──▶ ocr:8090 (default profile)
```

The default OCR service is reachable only on the private Compose network. Docker Engine 28's isolated
gateway mode prevents the OCR-only container from reaching the host or external networks. It accepts
authenticated raw JPEG/PNG/WebP bytes from the backend, returns bounded recognized lines, and has no Caddy route.
Paddle models are fetched from pinned BOS artifacts with SHA-256 verification while the image is
built, then baked into the image under an explicit model-cache path; the runtime filesystem is
read-only, and the Paddle runtime never downloads models or calls an external OCR/AI provider.

## Prerequisites

- Docker Engine 28 or newer with Docker Compose 2.33.1 or newer. The bundle pins the backend's
  default gateway to the normal application network while keeping OCR on a host-isolated internal
  bridge with no gateway address.
- A Linux AMD64 host for the released image set. The OCR image additionally requires AVX on every
  assigned processor.
- A signed release manifest verified with its exact tag-bound identity, with
  `CONNEX_BACKEND_DIGEST`, `CONNEX_FRONTEND_DIGEST`, and `CONNEX_OCR_DIGEST` set to the
  manifest's corresponding 64-character lowercase digests without the `sha256:` prefix (see
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

Business-card scanning and the private OCR sidecar are enabled by default in every supported
deployment template. Keep `CONNEX_OCR_BASE_URL=http://ocr:8090`, set
`CONNEX_OCR_PLAIN_HTTP_PRIVATE_HOST=ocr` to bind plaintext transport to that single-label service
on Compose's isolated `ocr_internal` network, generate a unique 32+ character
`CONNEX_OCR_SERVICE_TOKEN`, and leave `COMPOSE_PROFILES=ocr` in `.env`:

```bash
docker compose pull
docker compose up -d
```

The same token is supplied to the backend through `.env` and to the OCR container by Compose.
AI remains instance-disabled unless `CONNEX_AI_ENABLED=true`. When the master switch is enabled,
operators may independently disable deal briefs, deal-risk rationales, introduction rationales,
report narratives, or provider-backed business-card extraction with
`CONNEX_AI_FEATURES_DEAL_BRIEF=false`, `CONNEX_AI_FEATURES_DEAL_RISK_RATIONALE=false`,
`CONNEX_AI_FEATURES_INTRO_RATIONALE=false`, `CONNEX_AI_FEATURES_REPORT_NARRATIVE=false`, or
`CONNEX_AI_FEATURES_BUSINESS_CARD_EXTRACTION=false`. An absent per-feature setting defaults on, but
the master switch, `AI_USE`, and organization-provider readiness remain mandatory.
Cache-miss text generation defaults to 300 admitted provider attempts per organization in a rolling
10-minute window (`CONNEX_AI_INVOCATION_QUOTA_ATTEMPTS_PER_ORG` and
`CONNEX_AI_INVOCATION_QUOTA_WINDOW`), with 30 seconds between forced refresh attempts for the same
cache identity (`CONNEX_AI_INVOCATION_REFRESH_THROTTLE`). Quota organizations, refresh identities,
and active flights are each bounded by `CONNEX_AI_INVOCATION_QUOTA_MAX_ORGANIZATIONS`,
`CONNEX_AI_INVOCATION_REFRESH_MAX_IDENTITIES`, and `CONNEX_AI_INVOCATION_MAX_ACTIVE_FLIGHTS`, all
defaulting to 10,000. The 300-attempt quota, refresh throttle, and single-flight registries are per
JVM backend replica, so the effective organization quota multiplies across replicas; multi-replica
deployments need a shared coordinator for cluster-wide enforcement.
Paddle's current CPU wheel requires AVX support, and the default sidecar reserves up to two CPUs and
2 GiB of memory. A deployment that lacks AVX or cannot spare those resources must set both
`COMPOSE_PROFILES=` and `CONNEX_BUSINESS_CARD_SCANNING_ENABLED=false`; the empty profile omits the
sidecar, while the disabled feature flag prevents the backend from waiting for it. Keep the default
profile in `.env` otherwise so ordinary `pull`, `up`, `stop`, and migration commands continue to
include OCR. Using the explicit opt-out or losing OCR readiness moves eligible users to the
configured-provider fallback; without an enabled organization provider and `AI_USE`, automatic
extraction remains unavailable while manual image retention and reviewed import remain available
when private storage is ready. Losing
private binary-storage readiness disables both scanning and import. The OCR container is capped at two
CPUs, 2 GiB memory, 128 processes, one concurrent inference, and eight bounded HTTP handlers by
default (`CONNEX_OCR_MAX_REQUEST_HANDLERS`). Excess concurrent inference receives `429`, excess
connections receive `503`, and a slow request cannot hold the inference slot. If Paddle is unavailable,
an authorized member may use the organization's enabled, no-training-attested AI provider as a fallback
when instance AI is enabled. Before permitting external fallback, a scan joins or starts the local
readiness probe for up to `CONNEX_OCR_LOCAL_FIRST_WAIT` (2 seconds by default); availability polling
remains non-blocking. The fallback sends only the metadata-free canonical JPEG, accepts no remote image
URL, limits it to 3.5 MB and 4096 pixels per dimension, and returns review-only structured fields.
Readiness accepts only the explicit image-capable targets supported by each adapter: the maintained
Bedrock Claude allowlist; exact, currently supported Vertex Claude and Gemini model/location pairs;
and maintained OpenAI-compatible Chat Completions GPT, o-series, Gemini, and multimodal Gemma aliases
or snapshots. Vertex global and multi-region location routes are not supported. Gemini 3.5 Flash is
excluded because its ordinary PayGo routes require those unsupported endpoint forms, while its
single-region routes require provider-side Provisioned Throughput that Connex configuration cannot
verify. Provider-specific text/audio-only, retired, grandfathered, Responses-only,
sampling-incompatible, unknown, and differently cased ids are excluded. Azure OpenAI image fallback
remains disabled because an arbitrary deployment alias cannot be verified against the separately
configured model id. The exact resolved provider/model/location snapshot is checked again before
egress; unknown, text-only, retired, or location-incompatible targets degrade to manual entry without
sending pixels. Embedded-media provider calls default to two concurrent requests globally, one per
organization, and an exact shared 64 MiB estimated expansion budget
(`CONNEX_AI_MAX_CONCURRENT_MEDIA_REQUESTS`, `CONNEX_AI_MAX_CONCURRENT_MEDIA_REQUESTS_PER_ORG`, and
`CONNEX_AI_MAX_MEDIA_WORKING_BYTES`) held through provider response parsing and released before the
terminal audit write. OpenAI-compatible runtime readiness performs structural URL checks without DNS;
configuration-time address validation is separately deadline- and concurrency-bounded.
OpenAI-compatible, Bedrock, Vertex, and Google OAuth production transports pin the final validated
address and enforce `CONNEX_AI_REQUEST_TIMEOUT_MS` as one hard wall-clock deadline covering bounded
DNS resolution and the HTTP exchange, in addition to socket inactivity limits; Vertex token exchange
and model invocation share that same deadline. Resolver pools are intentionally capped and fail
closed when saturated. If native resolver calls never return, the affected fixed-host or
organization-configured pool remains unavailable until resolution recovers or the backend restarts,
without retaining the request's media lease past its deadline. Bedrock retries at most once under
that same deadline, using bounded full-jitter delay for transient `500`, `503`, `429`, and retryable
transport failures. The backend uses the bearer-authenticated `/ready`
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

Worker and supervisor startup failures emit only the allowlisted component, reason code, and
exception type chain. Operators can distinguish `unsupported_cpu_architecture`,
`cpu_capabilities_unreadable`, `avx_unavailable`, `models_unavailable`,
`runtime_dependency_unavailable`, `invalid_configuration`, `engine_initialization_failed`,
`server_initialization_failed`, and `worker_launch_failed` in OCR container logs without exposing
exception messages or configuration values.

The backend applies a process-wide budget of five scans per minute and a cross-workspace principal
budget of three scans per minute by default (`CONNEX_BUSINESS_CARD_MAX_GLOBAL_SCANS_PER_MINUTE` and
`CONNEX_BUSINESS_CARD_MAX_SCANS_PER_MINUTE`), leaving capacity that one principal cannot consume.
Imports remain limited to 12 per authenticated principal per minute before multipart parsing and
again to 12 per user and workspace in the import service
(`CONNEX_BUSINESS_CARD_MAX_IMPORTS_PER_MINUTE`). Malformed `Idempotency-Key` values and principals
over the pre-multipart window are rejected before the card body is parsed. Reservation and status
lookups have independent principal windows that run before database access. These limits are maintained in each backend process;
deployments with multiple backend replicas should enforce equivalent aggregate limits at their
trusted ingress or replace this local limiter with a shared admission service.
Before a browser sends private multipart data, it receives a two-minute submission lease
(`CONNEX_BUSINESS_CARD_RESERVATION_LEASE`). Each user may hold at most four unsubmitted leases per
workspace (`CONNEX_BUSINESS_CARD_MAX_OUTSTANDING_RESERVATIONS`); expired leases are reclaimed on the
next reservation and by the scheduled sweep. Completed idempotency claims use a 24-hour replay
horizon (`CONNEX_BUSINESS_CARD_IDEMPOTENCY_RETENTION`). Every minute by default, a catalog-aware
sweep gives each selected workspace its own bounded batch of up to 100 expired claims
(`CONNEX_BUSINESS_CARD_IDEMPOTENCY_CLEANUP_DELAY=1m` and
`CONNEX_BUSINESS_CARD_IDEMPOTENCY_CLEANUP_PER_WORKSPACE_BATCH_SIZE=100`) without loading private
import drafts into the control plane. Keep the submission lease shorter than the replay horizon.

`CONNEX_DEPLOYMENT_PROFILE` drives fail-closed posture enforcement (issues #497, #856) and is
**mandatory**. Set it to `saas`, `silo`, or `on-prem`; leaving it unset or blank now **fails
startup** rather than warning:

```text
CONNEX_DEPLOYMENT_PROFILE must be set to saas, silo, or on-prem outside dev/test/seeder
```

Only the `dev`, `test`, and `seeder` profiles are exempt (seeder runs in fact require it unset).
The deployment templates `deploy/silo.env.example` and `deploy/onprem.env.example` already set it —
an operator starting from one needs no action. `deploy/eval.env.example` deliberately leaves it
unset and relies on the `dev` exemption, so it cannot be mistaken for a deployment seed; production-
shaping that file means choosing an edition first. **An existing deployment that relied on the old
soft-launch behaviour must add the variable before taking this upgrade.**

`saas` additionally forbids the internal-access opt-ins (bootstrap, private SSO issuer hosts,
internal AI/SMTP hosts); `silo` and `on-prem` allow them. `on-prem` forbids instance-managed mail
(`CONNEX_MAIL_MANAGED=true` fails startup there) — an on-prem operator configures their own SMTP,
and workspace SMTP overrides stay available.

Full profile semantics, the capability×profile matrix, and how to demonstrate the difference are
in [DEPLOYMENT_EDITIONS.md](DEPLOYMENT_EDITIONS.md).

### Email deliverability

Connex is an SMTP submission client only: it signs nothing with DKIM, publishes and evaluates no
SPF/DKIM/DMARC records, and never sets a separate envelope sender. Whether your mail reaches an
inbox is decided by the relay you point `CONNEX_MAIL_HOST` at and by the DNS records on the domain in
your `CONNEX_MAIL_FROM` address. Note also that `CONNEX_MAIL_ENABLED=true` wires the transport but
does not by itself start sending password-reset or verification mail — each of those flows has its
own flag, all defaulting off.

Which records to publish for each mail shape, how the workspace-override fallback changes the sending
identity, what the built-in send-test does and does not prove, and the common failure modes are in
[DELIVERABILITY.md](DELIVERABILITY.md).

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
The normalized backend identity is persisted in the control plane on the first startup after this
version: provider plus absolute filesystem root, or S3 bucket, region, normalized endpoint, and
path-style mode. Every later startup compares configuration to that immutable row and aborts on any
mismatch, including a changed filesystem root or S3 addressing coordinate. Concurrent first
startups converge through the singleton row. Do not switch between `filesystem` and `s3` by
configuration or copy the filesystem tree verbatim: filesystem files have an implementation suffix
that S3 keys do not. A provider cutover requires a separately implemented checksummed,
key-translating migration that deliberately updates this identity, read verification, rollback
point, and retained source copy.

The filesystem provider reconciles abandoned `.connex-object-*.tmp` files at startup and every
minute without following symbolic links. It removes only files older than
`CONNEX_OBJECT_STORAGE_FILESYSTEM_TEMP_RETENTION=1h`; the schedule is configurable with
`CONNEX_OBJECT_STORAGE_FILESYSTEM_TEMP_CLEANUP_DELAY_MS=60000`. Cleanup failures are logged for
operator investigation and never expose object paths.

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

Workspace exports use the singleton `tenant_export_admission_control` row for database-global
admission. Its seeded capacity is four; capacity exhaustion or concurrent admission-row contention
returns HTTP 429 immediately. Each admitted export persists a database lease in the same control
transaction, captures tenant tables and active-object references in one repeatable-read database
snapshot, closes the snapshot before private-provider reads, and holds the database lease until
streaming cleanup completes. Export leases do not expire and are never reaped automatically:
workspace and organization teardown fail closed while they remain. Remove a stranded lease only
after operationally proving that no backend instance or response stream still owns that exact
export.

Object removal uses durable database queues. Tenant-owned objects are recorded in that tenant
catalog's `object_deletion_queue`; control-plane user-profile objects use
`user_object_deletion_queue`. The metadata update and deletion intent commit atomically. The backend
retries due rows every 60 seconds. Before every provider write, Connex commits a delayed two-pass
tombstone in an isolated transaction; the metadata transaction cancels it only after the write is
confirmed. The metadata transaction locks and revalidates that exact tombstone identity before
quota reservation and holds it across provider I/O and cancellation. Retry workers likewise reload
the selected row by id and key under lock, so a stale selection cannot delete bytes committed by a
later writer. Managed writes acquire deletion-queue locks before quota; business-card entity and
audit writes follow binary storage, preserving deletion-queue, quota, then audit order. A rollback,
process exit, database cancellation failure, or ambiguous provider response
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
   verify the target release manifest and put its exact `CONNEX_BACKEND_DIGEST`,
   `CONNEX_FRONTEND_DIGEST`, and `CONNEX_OCR_DIGEST` values in the mode-0600 `.env` as
   64-character lowercase digests without the `sha256:` prefix, but do not run `up`; pulling the
   target images does not replace the still-running old containers.

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

## Monitoring & support (operator-facing)

Connex deployments are frequently operated by the customer (silo / on-prem), so the observability
surface is designed for **your** monitoring stack — Connex has no remote access and nothing phones
home. The default error sink is local (structured `ERROR` log lines); no error data leaves the
deployment unless the operator explicitly configures a vendor integration.

- `GET /api/health` — liveness. Anonymous, returns `200 {"status":"UP"}` while the process serves
  traffic. Safe for load-balancer checks; carries no version or build information.
- `GET /api/health/ready` — readiness. Anonymous, `200` when the database is reachable, all Flyway
  migrations are applied, and every startup task has finished, otherwise `503`. The body reports
  `checks.db`, `checks.migrations`, and `checks.startup` separately as status words only — never
  exception details. Because the embedded server accepts connections before startup tasks finish,
  this endpoint — not TCP connectivity — is what restarts and upgrades must gate on. Startup tasks
  (backfills, secret rewrap) run once per upgrade and can take minutes on a large dataset, so give
  any deployment health gate a timeout that accommodates them. `checks.auditGuard` additionally
  reports whether all three exact append-only `audit_log` guards are visible; it is **reported, never
  gating** (an application user without the MySQL `TRIGGER` privilege cannot see them), so alert on
  a `DOWN` there and on the matching `ERROR` log line rather than taking an instance out of
  rotation.
- `GET /api/metrics` — JVM, HTTP, and connection-pool metrics for scraping. Readable **only** with
  the operator-configured scrape token (`CONNEX_METRICS_SCRAPE_TOKEN`), sent as
  `Authorization: Bearer <token>`; an ordinary authenticated session cannot read it and neither can
  a `HEAD` request. Unset token = endpoint unavailable to every caller, which the backend warns
  about at startup.

**Support flow (references and correlation ids):** every API response carries an
`X-Correlation-Id` header, and unexpected `500` responses include the same id in the JSON body.
For rendering failures the frontend error screen instead shows a `Reference:` digest. The app
best-effort reports that digest to `/api/client-errors`. Because its decimal shape does not prove
framework provenance, the digest is retained only in the deployment-local error report and is not
persisted or exported. Client-error metadata keeps only workspace, report time, a closed-vocabulary
route template, and a domain-separated correlation HMAC; it is purged on startup and hourly at the
fixed 30-day UTC cutoff. Local ECS logs still carry the digest and stack details for an operator
with host access, but those
user-data-bearing lines do not belong in an artefact sent to support.

**Support bundle:** an organization administrator can download a redacted, manifest-bearing
support bundle from `GET /api/orgs/{orgId}/support-bundle` and hand it to a support engineer, so a
ticket can be diagnosed without database or SSH access. The bundle carries readiness, allowlisted
configuration, migration history, redacted client-error metadata, and a windowed audit slice — and
never carries secrets, hosts, record values, or personal names. The audit slice labels its
non-spoofable `serverMintedRequestId` separately from
`untrustedClientAssertedCorrelationHmac`, which is only an organization-scoped lookup aid and never
proof of request identity.

On a systemd deployment, the host operator can add the optional closed-field request-completion
slice with `collect.sh --include-journal --journal-unit <unit>`. Those records are filtered first by
the server-resolved organization integer; missing, malformed, ambiguous, and other-organization
records are dropped, and raw journald `MESSAGE`, exception text, stacks, headers, hosts, and query
strings are never copied. The dedicated record and bundle carry only
`untrustedClientAssertedCorrelationHmac`, using the same organization-scoped disclosure-HMAC
derivation as current audit and client-error rows, never the raw caller value. It is only a secondary
lookup aid after organization scoping, not a substitute for `serverMintedRequestId`. Async request
completions are omitted because tenant resolution can change before redispatch. A journal collection,
projection, repack, or pre-publication post-repack verification failure uses exit code `68` and
publishes no archive. `SUPPORT_BUNDLE.md` explains the correlation boundary. Collect and read it with
[`deploy/support-bundle/`](../deploy/support-bundle/README.md); the full contents, redaction
contract, and a worked "a contact vanished" investigation are in
[`SUPPORT_BUNDLE.md`](SUPPORT_BUNDLE.md).

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

## Installing from source (`silo` / `on-prem`)

An operator who builds the images themselves — rather than pulling the published, signed digests —
uses the same profile template plus the build overlay and
[`deploy/source-build.env`](../deploy/source-build.env):

```bash
cd deploy
cp onprem.env.example .env        # or silo.env.example
# fill every REPLACE_* value in .env, including CONNEX_VERSION
docker compose \
  --env-file .env --env-file source-build.env \
  -f docker-compose.yml -f docker-compose.build.yml \
  up --build -d
curl -s http://localhost/api/version
```

`source-build.env` supplies placeholder image digests. They are never resolved, because
`docker-compose.build.yml` replaces every image reference with a locally built tag — but Compose
interpolates `docker-compose.yml` before the overlay is merged, so the variables must be defined
for the file to parse at all. The profile templates deliberately leave those digests **blank** so
that a published-image install refuses to start rather than silently running an unverified image;
do not copy the placeholders into `.env`.

A from-source install carries none of the supply-chain guarantees of a published release: there is
no cosign signature, no provenance attestation, no SBOM attestation, and no immutable digest to
pin. Operators who need those must install the published images instead, following
[`RELEASE.md`](RELEASE.md).

## CI coverage

[`deploy-smoke.yml`](../.github/workflows/deploy-smoke.yml) validates the compose bundle and boots
the backend under **all three deployment profiles** (`saas`, `silo`, `on-prem`), smoking
`/api/version` + `/api/capabilities` for each so a profile-specific startup regression fails CI. It
boots every profile with instance-managed mail configured and asserts the resulting split —
`mailManaged` true under `saas`/`silo`, false under `on-prem` — so collapsing the capability×profile
matrix fails the build. The profile-boot job runs with dev-relaxed transport to isolate the profile
dimension; a full image-and-Caddy end-to-end bring-up is exercised locally and on release rather
than per-PR.
