# Staging auto-deploy (preview.connexcrm.jp)

The internal staging box (`192.168.0.141`) tracks `origin/main` and redeploys automatically.
This runbook covers how that pipeline works, how to verify what is live, and the operator
preflight for releases that need maintenance steps. The customer-facing silo/on-prem bundle is
documented separately in [DEPLOYMENT.md](DEPLOYMENT.md).

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
   record fails closed by stopping the frontend and alerting. When the recorded transaction target
   differs from the wrapper's newly selected commit, recovery is handed to the deploy script from
   the recorded target; parsed logic from one commit never activates another commit.
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

   An old candidate is never unlinked from `releases/`. It is atomically renamed on the same
   filesystem into `.staging/release-quarantine/<sha>`, so an undetected consumer's cwd and open
   handles continue to reference the complete runtime. Candidate inspection is only a promotion
   signal: a clean scan records eligibility, but deletion waits for a later timer run and another
   clean scan. An observed or unreadable live process revokes eligibility and keeps the run
   nonzero. A candidate first quarantined under uncertainty needs a clean later run to become
   eligible and one further clean run before deletion. `.staging/prune-needed` persists while the
   quarantine is non-empty or pruning cannot finish, and ordinary no-change timer runs retry it;
   a continuing consumer or indeterminate process therefore repeats the failure alert instead of
   becoming an invisible disk backlog. Proven zombies, vanished processes, and processes already
   rooted in unrelated deleted directories do not block that retry. This is application rollback
   only; Flyway schema migrations remain forward-only.
8. **Emits a sanitized failure alert.** The stderr `ALERT` record includes only allow-listed gate,
   component and rollback state values plus validated target, marker, backend, frontend, and
   rollback shas. It never interpolates response bodies, command output, environment values,
   credentials, cookies, or filenames.

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

An entry in `release-quarantine` for one timer interval is normal: the quarantine protocol always
requires a later clean cycle before unlinking. If the same sha remains for multiple cycles, or the
journal repeats `Release pruning pending` / `Release prune backlog remains unresolved`, do not
delete or move the directory manually. Find the process or unit named by the adjacent journal
message, stop or restart that out-of-band consumer through its owning service, and let the timer
rescan the quarantine. For an indeterminate process, first determine whether it is a live process,
a zombie, or a stale/deleted cwd and correct the owning service. Escalate persistent unreadable
state or an invalid quarantine/marker to the staging owner; retain the directory until absence is
proved. Once the backlog is clean, the timer removes eligible entries and clears `prune-needed`
without operator deletion.

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
```

Staging is a Connex-operated instance on a dedicated database, which is `silo`. Without it the
backend fails startup with `CONNEX_DEPLOYMENT_PROFILE must be set to saas, silo, or on-prem
outside dev/test/seeder`; the health gate then rolls the deploy back to the previous JAR and
retries every cycle, so the running instance survives but every subsequent deploy fails until the
variable is added. Tracked as an owner action in
[RELEASE_1_0_EXTERNAL_BLOCKERS.md](RELEASE_1_0_EXTERNAL_BLOCKERS.md) item 6.
