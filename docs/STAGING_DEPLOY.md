# Staging auto-deploy (preview.connexcrm.jp)

The internal staging box (`192.168.0.141`) tracks `origin/main` and redeploys automatically.
This runbook covers how that pipeline works, how to verify what is live, and the operator
preflight for releases that need maintenance steps. The customer-facing silo/on-prem bundle is
documented separately in [DEPLOYMENT.md](DEPLOYMENT.md).

## Topology

- Checkout: `/opt/connex-staging` (a clone of this repo, hard-reset to `origin/main`).
- Backend: `connex-staging-backend.service` runs
  `/opt/connex-staging/backend/build/libs/backend-0.0.1-SNAPSHOT.jar` on `:8081`.
- Frontend: `connex-staging-frontend.service` runs `pnpm start` on `:3001`.
- Trigger: `connex-staging-deploy.timer` fires `connex-staging-deploy.service`
  (`User=dev`) every 5 minutes, which runs `/usr/local/bin/connex-staging-deploy`.

## Deploy pipeline

`/usr/local/bin/connex-staging-deploy` is a root-installed thin wrapper
([`deploy/staging/connex-staging-deploy-wrapper.sh`](../deploy/staging/connex-staging-deploy-wrapper.sh)):
it takes the deploy lock, fast-forwards the checkout to `origin/main`, and hands off to the
reviewed, in-repo script
[`deploy/staging/connex-staging-deploy.sh`](../deploy/staging/connex-staging-deploy.sh), which:

1. **Gates on a marker, not HEAD.** The last successfully deployed sha lives in
   `/opt/connex-staging/.staging/deployed-sha`. A failed build/deploy leaves the marker stale,
   so the next 5-minute cycle retries instead of silently skipping (the #829 stale-JAR bug).
2. **Builds a versioned backend artifact.** `gradlew clean bootJar -PgitSha=<sha>` — `clean`
   guarantees the JAR reflects current source, and the sha is stamped into build info so the
   running process is verifiable via `GET /api/version` (`gitSha` field). A copy is kept in
   `/opt/connex-staging/.staging/artifacts/backend-<sha>.jar`, plus a `rollback.jar` snapshot
   of the previously live JAR.
3. **Builds both artifacts before restarting anything**, so a frontend build failure can no
   longer strand a half-deployed backend.
4. **Health-gates the backend restart.** After `systemctl restart connex-staging-backend` it
   polls unit state + `http://127.0.0.1:8081/api/version` (bounded, 300s) until the served
   `gitSha` equals the target commit, then rechecks the same MainPID, unit health, and HTTP
   response after a 15s stability interval.
5. **Rolls back on failure.** If the gate fails, the previous JAR is restored and restarted,
   the frontend is left untouched, the marker is not advanced, and the run exits nonzero
   (visible in `journalctl -u connex-staging-deploy`).
6. Only after the backend passes does it restart the frontend and require
   `http://127.0.0.1:3001/` to answer before recording the sha as deployed.

### Installing / updating the wrapper (root, one-time)

```bash
install -m 0755 /opt/connex-staging/deploy/staging/connex-staging-deploy-wrapper.sh \
    /usr/local/bin/connex-staging-deploy
```

The in-repo script needs no installation — every deploy cycle runs the version on `main`.

## Verifying what is live

```bash
ssh -i ~/.ssh/connex_target dev@192.168.0.141
curl -s http://127.0.0.1:8081/api/version        # gitSha must equal origin/main HEAD
cat /opt/connex-staging/.staging/deployed-sha
journalctl -u connex-staging-deploy -n 50 --no-pager
```

## Operator preflight for maintenance releases

Flyway migrations run automatically on backend startup, but some releases document extra
maintenance (data backfills, environment variables, or migrations that must run before the new
code boots — e.g. the 5aa90472 legacy-upload migration). For those:

1. Read the release/PR notes for required env or manual migration steps **before** merging to
   `main`; staging deploys within ~5 minutes of the merge.
2. Apply required environment changes to `/etc/connex-staging/backend.env` /
   `frontend.env` (root) first.
3. If the new backend fails its health gate, the deploy rolls back to the previous JAR and
   retries every cycle — fix forward (or apply the missing step) rather than expecting the
   failed deploy to have stopped the timer.
4. Confirm recovery with the verification commands above.

Note: workspace self-service creation now defaults to **off** (guided-pilot GTM). Staging QA
flows that rely on registering a fresh user with an auto-created workspace need
`CONNEX_WORKSPACES_ALLOW_CREATION=true` in `/etc/connex-staging/backend.env`.
