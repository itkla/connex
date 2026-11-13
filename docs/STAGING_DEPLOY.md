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

   Before any new build, the filesystem containing `.staging` must have the 4 GiB
   `STAGING_MIN_FREE_BYTES` floor **plus** the measured size (`du -sb`) of
   `.staging/releases/<deployed-sha>` **plus 256 MiB** for cache growth. The deployed sealed pair
   is the estimate for the next pair; the extra margin allows package/build caches to grow without
   consuming the activation floor. A missing, unreadable, symlinked, or unmeasurable deployed tree
   uses a conservative **512 MiB** footprint instead (over twice the observed 200–220 MB pair),
   still adding the 256 MiB margin. Logs report the footprint, its `deployed` or `fallback` source,
   cache margin, available bytes and the full required byte count before building. Activation
   still independently requires exactly the bare 4 GiB floor. An interrupted transaction is
   recovered before either new-work headroom check, so low space cannot prevent recovery.
2. **Builds outside the live checkout.** The target commit is exported into an isolated temporary
   source tree. The frontend gets a frozen install and clean Next build, its generated route assets
   are checked by `frontend/ci/verify_build_chunks.mjs`, and the backend gets `clean bootJar` with
   the target sha stamped into `build-info.properties`. A failed build cannot mutate a running
   component or its checkout.

   Only the staging `pnpm build` call sets `CONNEX_DISABLE_TURBOPACK_BUILD_CACHE=1` and
   `CONNEX_SKIP_BUILD_TYPECHECK=1`, for both target builds and previous-runtime rebuilds. The first
   disables Turbopack's build filesystem cache: every build uses a fresh scratch tree that is
   deleted afterwards, so the cache would never be read. The second skips the memory-intensive
   build-time TypeScript check, which required CI already performs before a PR can merge to main.
   A type error that reaches main through a merge would therefore not block the staging build;
   CI on main still reports it. Both opt-outs require the exact value `1`; absent or other values
   preserve the existing cache and type-check behavior for CI, Docker, and local builds.
3. **Seals one complete release pair.** Each release lives at
   `.staging/releases/<sha>/` with `backend.jar`, a complete Next standalone `frontend/` runtime
   (traced dependencies, server, static output, and `public`), and `manifest.tsv`. Verification
   requires the manifest sha, both SHA-256 digests, the JAR's embedded `build.gitSha`, and the
   frontend's release identity to agree. The frontend digest skips the cache roots Next.js writes
   beneath its dist directory while it serves: `server/route-cache` and `cache` under `.next` or
   `.next-new`. The launcher keeps the sealed runtime as the server's working directory, and
   Next 16.3 promotes build seeds into `server/route-cache` on the first request for a static
   route, so hashing them would invalidate the live release the first time it served `favicon.ico`.
   Sealing refuses a runtime that already contains one of those paths, so build output is never
   left unhashed. A partial or corrupt directory is never overwritten or activated. The first
   transactional run rebuilds the marker commit's frontend from Git into the same standalone
   format; it does not label the unversioned legacy `.next` tree as proven. It
   never removes legacy `.next`, `.next-new`, or `.next-old` directories: it builds elsewhere,
   stops the legacy frontend before switching the checkout, and leaves those ignored trees on disk
   for deliberate operator cleanup after the sealed runtime is verified live. This also makes a
   first transactional run safe when an interrupted legacy deploy is still serving from one of
   those trees.
   These co-located hashes detect partial writes and accidental corruption; they are not a trust
   boundary against a process already running as the deploy user. Such a process can rewrite the
   artifacts, embedded identities, and writable manifest together and recompute the hashes. Strong
   tamper evidence would require a signature or digest anchored outside the deploy user's control.

   If a successful target build is refused or fails before a transaction is recorded (including
   pair verification, activation headroom, or transaction-write failure), EXIT/INT/TERM cleanup
   atomically renames that target into `.staging/release-quarantine/<sha>`. It never moves a target
   protected by `deployed-sha`, `rollback-sha`, `frontend-release` or the attested `frontend-running`
   record. It also retains the tree when a visible process references its cwd, root, executable,
   descriptors or mappings, or deployment-user process inspection is indeterminate. Invalid
   serving state, symlinked release paths, an existing quarantine destination, or a failed atomic
   rename produces a loud refusal and retains the tree. Once a transaction exists, its recovery
   path owns the target instead. A later attempt at the same sha builds again: only
   `releases/<sha>` participates in sealing/reuse; quarantine is never a ready-release source.
   If that retry commits, the old quarantine copy is deferred while the sha remains deployed,
   rollback or attested-running. The deploy compares device/inode identities to prove it is a
   separate directory from `releases/<sha>`; a missing, aliased or uninspectable protected tree
   still fails closed. Deferring a stale duplicate is successful cleanup, so commit and no-change
   runs do not leave `prune-needed` pending. The independent pruner keeps protecting that sha,
   then reclaims the old copy once it is no longer protected and its other gates pass.
   If the rebuilt tree itself becomes a retirement candidate first, its move is deferred without
   failing the deploy; `prune-needed` stays pending until the old quarantine copy is reclaimed,
   then a later pass can retire the rebuilt tree with the usual atomic rename.
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

   Quarantine is terminal **for the deploy script**: it never unlinks an entry, for exactly the
   reason above. Reclaiming the space is a separate program on its own schedule,
   `connex-staging-prune`, described under "Reclaiming quarantine" below. Every timer run logs
   quarantine entry and byte occupancy; more than four entries or more than 1 GiB emits a sanitized
   warning `ALERT`. The 1 GiB byte threshold remains below the 4 GiB free-space floor on purpose — set
   above it, the warning could never fire before the deploy gate had already blocked. New work
   fails closed at the footprint-inclusive pre-build headroom gate described above, before
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

### Reclaiming quarantine

`connex-staging-prune` (`deploy/staging/connex-staging-prune.sh`) runs hourly from
`connex-staging-prune.timer` and is the only thing permitted to unlink a quarantined tree. The
deploy script's invariant is unchanged.

**Merging a change to this script does not change what the host runs.** The unit executes
`/usr/local/bin/connex-staging-prune`, a separately installed copy, and nothing in the repository
copies it there — so a host keeps its old script and its old timer schedule until someone rolls the
change out by hand. Do that whenever `connex-staging-prune.sh` or either unit file changes:

```bash
# On the staging host, in a root maintenance session. Verify first, then install.
sudo install -m 0755 deploy/staging/connex-staging-prune.sh /usr/local/bin/connex-staging-prune
sudo install -m 0644 deploy/staging/systemd/connex-staging-prune.service \
    deploy/staging/systemd/connex-staging-prune.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now connex-staging-prune.timer

# Confirm the host now runs the intended schedule and script, not the previous ones.
systemctl cat connex-staging-prune.service | grep ExecStart
systemctl list-timers connex-staging-prune.timer
# Run the dry run as the unit's own user (User=dev, Group=dev), not as root: state-file
# permissions and /proc visibility differ, so a root check can report a clean scan while the
# timer's own runs refuse or make different reference decisions.
sudo -u dev /usr/local/bin/connex-staging-prune --dry-run
```

The dry run is the check that matters: it reports what the installed copy would reclaim without
removing anything, so a mismatch between the repository and the host shows up as a difference in
that output rather than as silence — and running it as `dev` is what makes that output represent
what the timer will actually do.

It removes an entry only when every one of these holds:

1. the entry is under `release-quarantine`, is a plain directory, is not a symlink, and is named as
   a bare 40-character SHA;
2. it is not the committed, rollback, or attested-running release;
3. it is not within the two most recent entries, which are kept for post-mortems;
4. it is older than `CONNEX_STAGING_PRUNE_MIN_AGE_SECONDS` (default 4h), measured from **ctime** —
   `rename(2)` preserves mtime, so mtime is when the release was built, not when it was retired;
5. the running frontend started *after* the entry was quarantined, since a process cannot hold a
   tree that was already quarantined before that process existed;
6. no live process references the tree — `/proc` is scanned for cwd, root, exe, open descriptors
   and mapped files under the path.

It takes the deploy lock non-blocking and exits quietly if a deploy holds it, so it never races the
rename it reasons about. It refuses outright, removing nothing, if the markers are unreadable or the
frontend start time cannot be established, and rejects any argument other than `--dry-run`.

It also removes **orphaned build scratch**. A build writes into
`.staging/.target-release-<sha>.XXXXXX` and deletes it on both the success and failure paths, but a
build that is *killed* never reaches that cleanup, stranding most of a frontend and backend build —
close to 900 MB. The reaper removes such a directory once it is past the minimum age, holds the
deploy lock, and no live process references it.

```bash
# Run as dev: report what would be reclaimed, without touching anything.
connex-staging-prune --dry-run
```

A `release-quarantine/<sha>` entry means the deploy script retired that release from the public
release set with an atomic rename. It does **not** mean the release is unused. The journal's
`advisory scan detected no matching consumer` message covers the frontend cgroup and deploy-UID
processes the timer can inspect; it is useful evidence, but never proof that another UID, service,
cwd, or open file does not use the tree. The deploy timer path never unlinks the entry: committed cross-version recovery stays in current
logic, while recorded logic is used only to interpret nonterminal transactions. A timer invocation
already running an older script is outside that guarantee, so complete the rollout before relying
on it. More than four entries or more than 1 GiB produces a warning `ALERT`; inspect reclamation
at or before either threshold. New builds require the footprint-inclusive reserve described above.

### Refreshing ClamAV signatures

`connex-staging-clamav-refresh.timer` runs weekly on Sunday at 04:00 host-local time, with up to
30 minutes of randomized delay and catch-up after downtime. Its root-run oneshot builds a fresh
`connex-clamav:staging-candidate` image from `git archive <deployed-sha> clamav`, using the committed
`.staging/deployed-sha` rather than checkout HEAD. `--pull --no-cache` forces the image's signature
prefetch to run again. The scanner still has no runtime updater or added network egress.

Install or update the script and both units explicitly; merging does not replace the installed
copy. The host needs Docker, Git, Bash, jq, curl, tar and flock. Run from the staging checkout:

```bash
sudo install -m 0755 deploy/staging/connex-staging-clamav-refresh.sh /usr/local/bin/connex-staging-clamav-refresh
sudo install -m 0644 deploy/staging/systemd/connex-staging-clamav-refresh.service \
    deploy/staging/systemd/connex-staging-clamav-refresh.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now connex-staging-clamav-refresh.timer
systemctl cat connex-staging-clamav-refresh.service | grep ExecStart
systemctl list-timers connex-staging-clamav-refresh.timer

# Run once through the same root unit, then inspect its fixed-label result.
sudo systemctl start connex-staging-clamav-refresh.service
sudo journalctl -u connex-staging-clamav-refresh.service -n 30 --no-pager
```

The job takes `/tmp/connex-staging-deploy.lock` non-blocking and logs `result=skipped reason=lock_busy`
if a deployment or another maintenance job holds it. It requires the existing lock created by the
deploy wrapper, leaving ownership and permissions intact. A failed build leaves the live container
untouched. Before swapping, it inspects `connex-staging-clamav` and preserves port bindings, memory
and swap limits, read-only root, tmpfs mounts, shared-memory size, restart policy, network and aliases,
log driver and options, container user, and capability/security options. Only `CONNEX_CLAMAV_*`
environment entries are copied, using a root-only temporary file removed on exit; raw command
diagnostics and environment values never enter the job's journal output. It refuses bind/volume
mounts, tmpfs supplied through `--mount` rather than `--tmpfs`, multiple networks,
privileged/auto-remove containers, malformed settings, or an existing
`connex-staging-clamav-previous` backup instead of guessing how to recreate them.

The old scanner is stopped and renamed to `connex-staging-clamav-previous` while the replacement
starts with the original name. Uploads may briefly fail closed during this interval. The job waits
up to 420 seconds for Docker health `healthy` **and** HTTP `/health` reporting `ready: true`, covering
the image's 180-second healthcheck start period plus database loading. Before those checks and
tagging succeed, any failure or handled termination removes the candidate container and renames and
starts the old one again, exiting nonzero. Restoring an already expired scanner does not repair its
signatures; check the failure and retry the refresh. If Docker itself prevents rollback, the original
container is retained and `result=failed stage=rollback` requires operator attention.

After readiness, the candidate is tagged `connex-clamav:staging`, the stopped old container is
removed, and its superseded image is removed without force. An image still referenced elsewhere
is retained with `result=retained stage=image_cleanup`. A container-cleanup failure after the commit
leaves the healthy replacement running and the backup retained. The service allows 30 minutes for
the full build and replacement; its termination handler attempts rollback before systemd's final
kill timeout. A hard kill or host crash cannot run cleanup: inspect any retained backup and private
temporary directory before retrying. Signature-age monitoring remains necessary; see
[MALWARE_SCANNING.md](MALWARE_SCANNING.md#signature-freshness).

### Operator note: headroom refusals and failed target builds

A pre-build refusal logs one complete requirement, for example:

```text
Staging build reserve: release_bytes=230686720 source=deployed cache_margin_bytes=268435456
Staging filesystem headroom: 4500000000 bytes available; preflight requires at least 4794089472 bytes
Deploy refused: staging filesystem has insufficient free-space headroom
```

Clear enough space to reach the **reported requirement**, rather than just the bare 4 GiB floor.
With the fallback footprint the requirement is 5,100,273,664 bytes. This is a build estimate, not
a guarantee against unrelated disk growth or a much larger target; the unchanged activation gate
can still refuse with `insufficient free-space headroom before activation`.

After a pre-transaction failure, expect `Quarantined unactivated target <sha>; live release
untouched`. Markers and deployed/rollback trees remain in place. Quarantining is a rename: it does
not itself free bytes. The independent hourly prune timer reclaims eligible entries under its
existing age, retention, frontend-start and process-reference gates; it does not require a
successful deploy. Inspect `journalctl -u connex-staging-prune` and run the installed pruner's
`--dry-run` as its service user before expecting space to return. A newly quarantined target may
remain because the current frontend predates it, or because it is among the two retained entries.

If space is still insufficient, use the deliberate marker and deploy-user process checks below to reclaim
an eligible quarantine entry in a maintenance window, or free unrelated space / expand the
filesystem. Do not delete deployed or rollback trees or lower the floor to force activation.
`Unactivated target quarantine refused` means the target remains under `releases/`; resolve the
reported serving-state, consumer, destination-collision or atomic-move blocker first. A retry can
rebuild the same sha even while its earlier tree is quarantined, but a second refused build cannot
overwrite that quarantine entry. Older unreferenced trees stranded in `releases/` by earlier
deploy logic are not swept by this change or by the independent pruner; escalate them for owner-led
inspection rather than bulk-deleting releases. SIGKILL and power loss before transaction creation
also bypass EXIT cleanup and require that inspection.

To reclaim one entry, first identify its owning release and establish which services could use it.
Run the following as `dev` in one interactive maintenance shell, substituting the quarantined
40-character sha. The trees are dev-owned, so inspection of dev's processes and removal need no
sudo. Hold the deploy lock through inspection and deletion so markers cannot change between them:

```bash
sha=0123456789abcdef0123456789abcdef01234567
state=/opt/connex-staging/.staging
entry="$state/release-quarantine/$sha"
exec 9>>/tmp/connex-staging-deploy.lock || exit 1
flock -n 9 || { echo 'deploy or prune is running; retry later' >&2; exit 1; }
[[ "$sha" =~ ^[0-9a-f]{40}$ ]] || { echo 'invalid sha' >&2; exit 1; }
if [ ! -d "$entry" ] || [ -L "$entry" ]; then
    echo 'invalid quarantine entry' >&2
    exit 1
fi
for marker in deployed-sha rollback-sha frontend-release; do
    marker_sha="$(cat "$state/$marker")" || { echo "cannot read $marker" >&2; exit 1; }
    [[ "$marker_sha" =~ ^[0-9a-f]{40}$ ]] || { echo "$marker is invalid" >&2; exit 1; }
    [ "$marker_sha" != "$sha" ] || { echo "$marker still protects $sha" >&2; exit 1; }
done
IFS=$'\t' read -r running_sha running_pid running_extra < "$state/frontend-running" \
    || { echo 'cannot read frontend-running' >&2; exit 1; }
if ! [[ "$running_sha" =~ ^[0-9a-f]{40}$ ]] || ! [[ "$running_pid" =~ ^[1-9][0-9]*$ ]] \
    || [ -n "$running_extra" ]; then
    echo 'frontend-running is invalid' >&2
    exit 1
fi
[ "$running_sha" != "$sha" ] || { echo "frontend still serves $sha" >&2; exit 1; }
dev_references_entry() {
    local deploy_uid process process_uid link reference state scan_status
    deploy_uid="$(id -u)" || return 2
    for process in /proc/[0-9]*; do
        [ -d "$process" ] || continue
        if ! process_uid="$(stat -c %u -- "$process" 2>/dev/null)"; then
            [ ! -d "$process" ] && continue
            return 2
        fi
        [ "$process_uid" = "$deploy_uid" ] || continue
        state="$(sed -n 's/^State:[[:space:]]*\([A-Z]\).*/\1/p' "$process/status" 2>/dev/null)" || return 2
        [ "$state" != Z ] || continue
        for link in "$process/cwd" "$process/root" "$process/exe" "$process"/fd/*; do
            if reference="$(readlink -- "$link" 2>/dev/null)"; then
                case "$reference" in "$entry"|"$entry"/*) return 0 ;; esac
            elif [ -d "$process" ]; then
                case "$link" in
                    "$process"/fd/*) [ ! -L "$link" ] || return 2 ;;
                    *) return 2 ;;
                esac
            fi
        done
        [ -d "$process" ] || continue
        [ -r "$process/fd" ] && [ -x "$process/fd" ] || return 2
        scan_status=0
        grep -F -- "$entry" "$process/maps" 2>/dev/null || scan_status=$?
        [ "$scan_status" -ne 0 ] || return 0
        [ "$scan_status" -eq 1 ] || { [ ! -d "$process" ] || return 2; }
    done
    return 1
}
scan_status=0
dev_references_entry || scan_status=$?
[ "$scan_status" -eq 1 ] || { echo 'referenced or uninspectable; retain entry' >&2; exit 1; }
```

The scan reads dev-owned processes' cwd, root, executable and descriptor links, plus their mapped
file paths; it also catches references whose child pathname is marked `(deleted)`. If it finds a
reference or cannot inspect a dev process, retain the entry and stop or restart the consumer
through its owning service before repeating the checks. Do not assume passwordless sudo grants
`lsof` or removal privileges: the deploy account's sudo rules cover only the specified application
service operations.

**Other users' processes cannot be fully inspected without root.** A clean dev-only scan cannot
exclude those consumers. This visibility limit is why the deploy keeps quarantine as an atomic
rename: any unseen consumer retains the complete tree through its existing handles. If another
user or an out-of-band service may use the entry, keep it and ask the staging owner to arrange a
root inspection. Once ownership of all consumers is established, all marker checks still pass,
and a fresh scan is clean, remove exactly the validated dev-owned entry deliberately in the same
shell holding the lock:

```bash
rm -rf -- "$entry"
flock -u 9
exec 9>&-
```

Operator reclamation is scheduled maintenance; a clean dev-only scan is evidence within that
user's visibility, not proof that no other user holds the tree. Never unlink a serving runtime.

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

Session and workspace cookies default to `Secure`, including when systemd launches from
`/opt/connex-staging/backend`. That directory still supplies the local CORS/WebAuthn origin
`http://localhost:3001` and WebAuthn RP ID `localhost` when the environment does not override them;
it does not opt out of secure cookies. A local HTTP-only staging session requires the explicit
`CONNEX_LOCAL_HTTP_COOKIES_ENABLED=true` exception and exclusively loopback HTTP CORS and WebAuthn
origins. Public origins are refused with that exception, and legacy cookie-Secure=false overrides
without the exception fail startup. Leave the exception unset for `https://preview.connexcrm.jp`.
The public edge is managed separately: confirm a positive `Strict-Transport-Security` max-age on
the public HTTPS response during the independent edge retest; checkout settings do not prove live HSTS.

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
