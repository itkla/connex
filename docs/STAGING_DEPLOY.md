# Staging auto-deploy (preview.connexcrm.jp)

The internal staging box (`192.168.0.141`) tracks `origin/main` and redeploys automatically.
This runbook covers how that pipeline works, how to verify what is live, and the operator
preflight for releases that need maintenance steps. The customer-facing silo/on-prem bundle is
documented separately in [DEPLOYMENT.md](DEPLOYMENT.md).
The application deploy does not configure the public edge. Cloudflare onboarding, origin lock-down,
rules, secret-free event review, and the required independent public retest for
`preview.connexcrm.jp` are in [EDGE_DEFENCE.md](EDGE_DEFENCE.md).

## Topology

- Checkout: `/opt/connex-staging` (a clone of this repo, hard-reset to `origin/main`).
- Backend: `connex-staging-backend.service` runs
  `/opt/connex-staging/backend/build/libs/backend-0.0.1-SNAPSHOT.jar` on `:8081`.
- Frontend: `connex-staging-frontend.service` runs `pnpm start` on `:3001`. The package script
  delegates to `deploy/staging/connex-frontend-start.sh`: staging launches the standalone runtime
  named by `.staging/frontend-release`, while checkouts without that marker retain ordinary
  `next start` behavior. Before executing Node, the launcher atomically records the release sha and
  its own process ID in `.staging/frontend-running`; deploy health checks require that live process
  to have the sealed runtime as its working directory.
- Trigger: `connex-staging-deploy.timer` fires `connex-staging-deploy.service`
  (`User=dev`) every 5 minutes, which runs `/usr/local/bin/connex-staging-deploy`.

## Deploy pipeline

`/usr/local/bin/connex-staging-deploy` is a root-installed thin wrapper
([`deploy/staging/connex-staging-deploy-wrapper.sh`](../deploy/staging/connex-staging-deploy-wrapper.sh)):
it takes the deploy lock, fetches the candidate script from `origin/main` into a temporary file
without changing the live checkout, and hands off to that reviewed script. The wrapper pins the
same fetched commit as the deploy target; the candidate script does not fetch again. If `main`
advances after that selection, the newer commit waits for the next timer run instead of being
deployed by logic loaded from the older commit. The candidate
[`deploy/staging/connex-staging-deploy.sh`](../deploy/staging/connex-staging-deploy.sh), which:

1. **Validates the live release before doing work.** The committed sha lives in
   `.staging/deployed-sha`. On transactional releases, the running backend `/api/version`,
   on-disk JAR identity, `.staging/frontend-release`, `.staging/frontend-running`, live runtime
   directory, active systemd units, and sealed release bundle must all name that sha. Any
   unexplained mismatch is refused and alerted; the backend-is-target shortcut from #829 no
   longer exists. Only an absent frontend marker is eligible for the one-time legacy transition;
   a malformed marker is a refusal.
2. **Builds outside the live checkout.** The target commit is exported into an isolated temporary
   source tree. The frontend gets a frozen install and clean Next build, its generated route assets
   are checked by `frontend/ci/verify_build_chunks.mjs`, and the backend gets `clean bootJar` with
   the target sha stamped into `build-info.properties`. A failed build cannot mutate a running
   component or its checkout.
3. **Seals one complete release pair.** Each release lives at
   `.staging/releases/<sha>/` with `backend.jar`, a complete Next standalone `frontend/` runtime
   (traced dependencies, server, static output, and `public`), and `manifest.tsv`. Verification
   requires the manifest sha, both SHA-256 digests, the JAR's embedded `build.gitSha`, and the
   frontend's release identity to agree. A partial or corrupt directory is never overwritten or
   activated. The first transactional run rebuilds the marker commit's frontend from Git into the
   same standalone format; it does not label the unversioned legacy `.next` tree as proven. It
   never removes legacy `.next`, `.next-new`, or `.next-old` directories: it builds elsewhere,
   stops the legacy frontend before switching the checkout, and leaves those ignored trees on disk
   for deliberate operator cleanup after the sealed runtime is verified live. This also makes a
   first transactional run safe when an interrupted legacy deploy is still serving from one of
   those trees.
   These co-located hashes detect partial writes and accidental corruption; they are not a trust
   boundary against a process already running as the deploy user. Such a process can rewrite the
   artifacts, embedded identities, and writable manifest together and recompute the hashes. Strong
   tamper evidence would require a signature or digest anchored outside the deploy user's control.
4. **Records a recoverable transaction.** `.staging/deploy-transaction` atomically records the
   validated prior sha, target sha, pre-deploy retained rollback sha, and phase (`prepared`,
   `frontend_stopped`, `backend_live`, `frontend_live`, or `committed`). EXIT/INT/TERM restore the
   prior pair and the pre-deploy rollback marker after activation begins.
   On SIGKILL, reboot, or power loss, the next timer run validates the recorded bundles and either
   reinstalls and health-gates both target artifacts before re-smoking them, or restores the exact
   prior pair. Recovery never trusts a running backend response without also reinstalling the
   target JAR, so an interrupted rollback cannot later boot a stale artifact. An invalid recovery
   record fails closed by stopping the frontend and alerting. When a nonterminal transaction target
   differs from the wrapper's newly selected commit, activation or rollback is handed to the deploy
   script from the recorded target; parsed logic from one commit never activates another commit.
   A schema-2 `committed` transaction needs no activation, so the newly selected script consumes it
   and runs its current terminal-quarantine cleanup instead of granting older logic authority to
   prune or unlink.
5. **Quiesces before activation.** After both bundles verify, the deploy stops the frontend, then
   and only then resets the checkout. It always installs and restarts the target backend, waits up
   to 900 seconds for the exact `/api/version` sha plus readiness, and rechecks the same PID after
   15 seconds. The target frontend is then selected by the atomic frontend-release marker and
   restarted from its standalone runtime. Its release/PID attestation and live process directory
   must identify that runtime. No old frontend remains serving against a new backend.
6. **Runs the full post-deploy smoke.** Before committing the marker, the script verifies the
   public frontend, direct backend readiness, the proxied `/api/version` target sha, the complete
   capabilities response shape, the login page, a normal password/session login, and `/dashboard`
   rendered inside `data-app-main`. The smoke session uses a temporary mode-0700 directory. A failed
   logout blocks marker advancement and alerts because deleting the local cookie cannot invalidate
   Spring Session; cleanup failure also blocks the deploy, scrubs sensitive files when deletion is
   unavailable, and cannot prevent rollback or alerting. Credential content and cookies are never
   logged or placed in argv.
7. **Commits or rolls back the pair.** Only after smoke passes does the script atomically write
   `rollback-sha` and `deployed-sha`, mark the transaction committed, disarm rollback, and print
   `Done`. The no-change path also verifies that `rollback-sha` names a valid pair distinct from the
   deployed release; a missing, current-release, or corrupt retained pair is a loud refusal rather
   than a successful no-op. Every
   activation failure stops the frontend, first installs the target checkout's sealed-runtime
   launcher as deployment control plane, then restores both artifacts from the verified prior
   bundle. It requires the exact rollback backend sha/readiness/stable PID plus frontend release,
   process-directory, PID, and HTTP health; leaves `deployed-sha` unchanged; and exits nonzero.
   Before pruning older immutable releases, the script requires the durable frontend SHA/PID
   record to identify a live descendant of the frontend unit's `pnpm` MainPID in the same systemd
   control group, with the sealed runtime as its working directory and the same release as
   `deployed-sha`. A missing, stale, orphaned, or cross-release record prevents any release from
   entering the pruning lifecycle.

   An old candidate is never automatically deleted. It is atomically renamed on the same filesystem
   into `.staging/release-quarantine/<sha>`, so an undetected consumer's cwd and open handles
   continue to reference the complete runtime. The deploy preflights GNU `mv --no-copy` support;
   separately mounted `releases/` or `release-quarantine/` directories make the rename fail loudly
   instead of degrading to copy-then-unlink. Mounting the whole `.staging` directory separately is
   safe because both children remain on the same filesystem.

   Quarantine is the script's terminal state. There is no eligibility marker or automatic unlink
   sweep because a second advisory process scan cannot prove that no unobserved consumer started
   using the tree. The scan remains only as operator-facing reporting and never authorizes
   deletion. Every timer run logs quarantine entry and byte occupancy; more than eight entries or
   more than 8 GiB emits a sanitized warning `ALERT`. Before recovery or build work, the script also
   requires at least 5 GiB free on the filesystem containing `.staging`, failing closed before
   transaction, marker, rollback, or release writes begin under insufficient headroom.
   `.staging/prune-needed` persists only when a candidate could not be moved safely, while a
   non-empty quarantine still causes no-change runs to repeat the advisory usage report. This is
   application rollback only; Flyway schema migrations remain forward-only.
8. **Emits sanitized alerts.** The stderr failure `ALERT` record includes only allow-listed gate,
   component and rollback state values plus validated target, marker, backend, frontend, and
   rollback shas. The quarantine warning `ALERT` contains only fixed labels and numeric occupancy
   and threshold values. Neither record interpolates response bodies, command output, environment
   values, credentials, cookies, or filenames.

### Installing / updating the wrapper (root, one-time)

```bash
install -m 0755 /opt/connex-staging/deploy/staging/connex-staging-deploy-wrapper.sh \
    /usr/local/bin/connex-staging-deploy
```

The in-repo script needs no installation — every deploy cycle runs the version on `main`.

The wrapper changed with the transactional deploy and **must be reinstalled before the first
transactional release**. The candidate script detects the old wrapper (which does not pin its
target), restores the prior checkout, refuses deployment, and alerts; this prevents the old
reset-first wrapper from silently proceeding with the new transaction contract. The deploy
service account also needs passwordless permission for:

- `systemctl stop connex-staging-frontend`
- `systemctl restart connex-staging-frontend`
- `systemctl restart connex-staging-backend`

If the new stop permission is absent, deployment fails before backend activation and leaves the
still-running prior release untouched.

### Authenticated smoke account (root preflight)

Provision a dedicated low-privilege staging user with one active workspace. Store only its normal
login assertion in a root-owned, deployment-group-readable file:

```bash
install -o root -g dev -m 0640 /dev/null /etc/connex-staging/smoke-login.json
sudoedit /etc/connex-staging/smoke-login.json
```

The file must contain exactly this JSON shape, with real staging-only values substituted locally:

```json
{"username":"STAGING_SMOKE_USERNAME","password":"STAGING_SMOKE_PASSWORD"}
```

The credential path, root ownership, deployment account primary-group ownership, and mode `0640`
are fixed by the deploy script and cannot be relaxed through inherited environment variables. The
deploy refuses before builds or service changes if the file is missing, is a symlink, is unreadable
by `dev`, has different ownership or mode, or contains any other keys. Do not put these values in
Git, a command line, the frontend environment, or systemd logs.

## Verifying what is live

```bash
ssh -i ~/.ssh/connex_target dev@192.168.0.141
curl -s http://127.0.0.1:8081/api/version        # gitSha must equal deployed-sha
curl -s http://127.0.0.1:8081/api/health/ready   # 200 {"status":"UP",...} when serving traffic
cat /opt/connex-staging/.staging/deployed-sha
cat /opt/connex-staging/.staging/frontend-release
cat /opt/connex-staging/.staging/frontend-running
cat /opt/connex-staging/.staging/rollback-sha
find /opt/connex-staging/.staging/release-quarantine -mindepth 1 -maxdepth 1 -type d -printf '%f\n'
test ! -e /opt/connex-staging/.staging/prune-needed || cat /opt/connex-staging/.staging/prune-needed
journalctl -u connex-staging-deploy -n 50 --no-pager
```

For artifact recovery, read `rollback-sha`, then inspect the matching
`.staging/releases/<sha>/manifest.tsv`. The automatic rollback verifies the manifest, both
digests, and both embedded identities again before using the pair.

A `release-quarantine/<sha>` entry means the deploy script retired that release from the public
release set with an atomic rename. It does **not** mean the release is unused. The journal's
`advisory scan detected no matching consumer` message covers the frontend cgroup and deploy-UID
processes the timer can inspect; it is useful evidence, but never proof that another UID, service,
cwd, or open file does not use the tree. Once this terminal-quarantine version is selected by the
wrapper, its timer path never unlinks the entry: committed cross-version recovery stays in current
logic, while recorded logic is used only to interpret nonterminal transactions. A timer invocation
already running an older script is outside that guarantee, so complete the rollout before relying
on it. More than eight entries or more than 8 GiB produces a warning `ALERT`; schedule deliberate
reclamation at or before either threshold. A deploy also refuses to begin recovery or build work
with less than 5 GiB free on the `.staging` filesystem.

To reclaim one entry, first identify its owning release and prove that neither Connex nor an
out-of-band process serves it. Run the following on staging in an interactive maintenance window,
substituting the quarantined 40-character sha:

```bash
sha=0123456789abcdef0123456789abcdef01234567
state=/opt/connex-staging/.staging
entry="$state/release-quarantine/$sha"
[[ "$sha" =~ ^[0-9a-f]{40}$ ]] || { echo 'invalid sha' >&2; exit 1; }
[ -d "$entry" ] && [ ! -L "$entry" ] || { echo 'invalid quarantine entry' >&2; exit 1; }
for marker in deployed-sha rollback-sha frontend-release; do
    marker_sha="$(cat "$state/$marker")" || { echo "cannot read $marker" >&2; exit 1; }
    [[ "$marker_sha" =~ ^[0-9a-f]{40}$ ]] || { echo "$marker is invalid" >&2; exit 1; }
    [ "$marker_sha" != "$sha" ] || { echo "$marker still protects $sha" >&2; exit 1; }
done
IFS=$'\t' read -r running_sha running_pid running_extra < "$state/frontend-running" \
    || { echo 'cannot read frontend-running' >&2; exit 1; }
[[ "$running_sha" =~ ^[0-9a-f]{40}$ ]] && [[ "$running_pid" =~ ^[1-9][0-9]*$ ]] \
    && [ -z "$running_extra" ] || { echo 'frontend-running is invalid' >&2; exit 1; }
[ "$running_sha" != "$sha" ] || { echo "frontend still serves $sha" >&2; exit 1; }
lsof_report="$(mktemp)" || exit 1
lsof_errors="$(mktemp)" || { rm -f "$lsof_report"; exit 1; }
trap 'rm -f "$lsof_report" "$lsof_errors"' EXIT
sudo lsof -nP > "$lsof_report" 2> "$lsof_errors" || { cat "$lsof_errors" >&2; exit 1; }
[ ! -s "$lsof_errors" ] || { cat "$lsof_errors" >&2; exit 1; }
if grep -F -- "$entry" "$lsof_report"; then
    echo "a process still references $sha" >&2
    exit 1
fi
```

The full root-visible `lsof` scan must complete without warnings and contain no quarantine-entry
path. Unlike `lsof +D`, this scans processes rather than walking only the existing directory tree,
so textual paths such as `<entry>/removed-child (deleted)` remain visible. If it reports any cwd,
executable, mapped file, or open file, stop or restart that process through its owning service and
repeat the checks. If the inspection emits a warning, cannot complete, or the deploy journal
reports an indeterminate advisory scan, retain the entry and escalate to the staging owner. After
all marker checks still pass and a fresh full-process scan reports no matching path, remove exactly
that validated entry deliberately:

```bash
sudo rm -rf -- "$entry"
```

This manual proof is intentionally stronger than the timer's advisory scan. Automatic disk
reclamation cannot justify risking a live release tree; operator reclamation is scheduled
maintenance, while unlinking a serving runtime is an outage.

## Operator preflight for maintenance releases

Flyway migrations run automatically on backend startup, but some releases document extra
maintenance (data backfills, environment variables, or migrations that must run before the new
code boots — e.g. the 5aa90472 legacy-upload migration). For those:

1. Read the release/PR notes for required env or manual migration steps **before** merging to
   `main`; staging deploys within ~5 minutes of the merge.
2. Apply required environment changes to `/etc/connex-staging/backend.env` /
   `frontend.env` (root) first. Env-only changes do **not** trigger a deploy cycle —
   restart the affected service yourself (`sudo systemctl restart connex-staging-backend`
   / `connex-staging-frontend`).
3. If either new component or any smoke gate fails, the deploy rolls back the previous complete
   release pair and retries every cycle — fix forward (or apply the missing step) rather than
   expecting the failed deploy to have stopped the timer.
4. Confirm recovery with the verification commands above.

Note: workspace self-service creation now defaults to **off** (guided-pilot GTM). Staging QA
flows that rely on registering a fresh user with an auto-created workspace need
`CONNEX_WORKSPACES_ALLOW_CREATION=true` in `/etc/connex-staging/backend.env`.

**Required (root):** staging runs the default Spring profile, so the mandatory deployment profile
applies to it. `/etc/connex-staging/backend.env` must contain:

```text
CONNEX_DEPLOYMENT_PROFILE=silo
JAVA_TOOL_OPTIONS=-Djava.security.properties=/opt/connex-staging/backend/connex.java.security
```

The second setting is required because staging launches the backend JAR directly instead of using
the published backend image. It loads the tracked one-second positive and zero-second negative JVM
DNS TTLs used by hostname-based trusted proxies. After adding or changing it, run
`sudo systemctl restart connex-staging-backend`; a direct-JAR staging launch without this setting
does not support hostname-based trusted proxies.

Staging is a Connex-operated instance on a dedicated database, which is `silo`. Without it the
backend fails startup with `CONNEX_DEPLOYMENT_PROFILE must be set to saas, silo, or on-prem
outside dev/test/seeder`; the health gate then rolls the deploy back to the previous JAR and
retries every cycle, so the running instance survives but every subsequent deploy fails until the
variable is added. Tracked as an owner action in
[RELEASE_1_0_EXTERNAL_BLOCKERS.md](RELEASE_1_0_EXTERNAL_BLOCKERS.md) item 6.
