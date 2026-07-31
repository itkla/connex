# Connex 1.0 — External Blockers & Owner Actions

Tracking companion to the 1.0 release program (umbrella epic [#848](https://github.com/itkla/connex/issues/848), waves [#852](https://github.com/itkla/connex/issues/852)–[#857](https://github.com/itkla/connex/issues/857)). Everything in this document requires action **outside the codebase** — legal counsel, third-party verification programs, root access, staffing, or a business decision. Engineering does not block on these day-to-day, but each one gates a specific release wave, and several have month-scale external lead times. Items are ordered by urgency of *starting*, not finishing.

Update the Status column in place as items move; the wave issues reference this file rather than duplicating it.

Wave 3 code completion does not wait for human or external-provider actions. Those close conditions were explicitly transferred to Wave 5 and #868 on 2026-07-30. Connected capture therefore remains default-off and unauthorized for partner or public ingestion until the applicable release gate passes.

| # | Blocker | Gates | Status |
|---|---------|-------|--------|
| 1 | Legal counsel engagement (APPI) | Wave 5/public release gate | Not started |
| 2 | Corporate entity facts | Item 1; legal pages | Not started |
| 3 | Google OAuth verification + CASA Tier 2 | Wave 5/public release gate | Not started |
| 4 | Microsoft publisher verification | Wave 5/public release gate | Not started |
| 5 | Penetration-test procurement | Wave 5 execution | Not started |
| 6 | Staging root actions | Wave 0 close-out (mitigated) | Pending |
| 7 | Backup-retention decision | Wave 1 (design input) | **Decided: 30-day rolling + PITR (2026-07-25)** |
| 8 | Staging `connex_pub` cutover decision | Wave 5 execution | Undecided |
| 9 | Error-monitoring vendor (APPI) | Wave 1 observability floor | Undecided |
| 10 | Native-JP reviewer | Wave 5 EN/JA quality gate | Unassigned |
| 11 | v0.9.0 release rehearsal prerequisites | Wave 1 exit criterion; hard gate before Wave 5 | Deferred — owner |

## 1. Legal counsel engagement (APPI)

**Why it blocks public release:** four public pages (`/privacy`, `/legal`, `/disclosure`, `/tokushoho`) carry draft status, and the DPA template is not executable. Google's restricted-scope review (item 3) evaluates the **published** privacy policy — so counsel must finish **before** that submission, not in parallel.

**Action:** sign an engagement letter with JP counsel and hand over the punch list below.

**Counsel punch list** (from `docs/APPI_DPA_TEMPLATE.md` and `frontend/messages/{en,ja}/legal.json`):

- Party/entity blanks: `[Connex legal entity]`, `[customer legal name]`, governing law `[Japan]`.
- Commercial terms: `[Term, liability caps, indemnities — counsel to complete.]`.
- The **[30]-day deletion clause** including *"from routine backups on their normal cycle"* — must be reconciled with the backup-retention decision (item 7) **before** the DPA is declared executable.
- Referenced-artifact placeholders (Breach Response Runbook, DSR Procedure, Encryption Guarantee Matrix, Special-Care Data Policy, SaaS/on-prem encryption runbooks, Security Posture) — confirm each referenced document is in a state counsel will stand behind.
- `legal.json` draft banners (EN and JA) — removal is gated on counsel sign-off, not engineering.
- New subprocessors as they are chosen (item 9 now; payment provider when self-serve returns post-1.0) — each is an APPI cross-border transfer requiring `docs/SECURITY.md` §4/§5 and DPA updates.

**Hard sequencing rule:** DPA "executable" status is gated on the Wave 1 tenant-teardown + export drill *passing*, not on the placeholder fill completing.

## 2. Corporate entity facts

**Why it blocks:** item 1 cannot complete without them, and no engineering velocity substitutes.

**Action:** provide legal entity name, registered address, representative name, 個人情報保護管理者 (personal-information protection manager), and a monitored `privacy@` contact address. If the operating entity is not yet incorporated, that is the true critical path for the entire legal track.

## 3. Google OAuth verification + CASA Tier 2

**Why it blocks public release:** `gmail.readonly` is a **restricted** scope: OAuth verification plus an annual CASA Tier 2 third-party security assessment (month-scale). `calendar.readonly` is sensitive-scope (verification, no CASA).

**What it does NOT block:** engineering. All sync is built and QA'd against an unverified app in test-user mode (≤100 allow-listed test users) and ships **flag-gated**. Verification is a production-enablement switch, not a code dependency — 1.0 ships either way.

**Action:** register the production OAuth app, submit for verification the day the privacy policy is counsel-final (item 1), start the CASA Tier 2 process, and record case IDs on [#855](https://github.com/itkla/connex/issues/855).

## 4. Microsoft publisher verification

**Why it blocks public release:** production Microsoft Graph mail/calendar consent for external tenants requires a verified publisher (MPN account). Materially lighter than item 3.

**Action:** register the Entra app, complete publisher verification, record on #855. Dev-tenant engineering proceeds meanwhile.

## 5. Penetration-test procurement

**Why it blocks:** Wave 5 executes a third-party pentest against the surface frozen at the end of Wave 4; JP enterprise procurement will also ask for the summary letter.

**Action:** commission the SOW now (vendor lead times run weeks-to-months). Scope must include: connected-account sync + OAuth token custody, import/merge-at-import paths, tenant isolation, the workspace/auth surface, and the dark `/api/workflows` endpoints (or confirm they get flag-gated in Wave 4 and excluded). Budget a remediation window separately from the engagement.

## 6. Staging root actions (192.168.0.141)

**Why it blocks:** the new health-gated deploy script ([#859](https://github.com/itkla/connex/pull/859)) is merged and verified, but `dev`'s passwordless sudo covers only the two `systemctl restart` commands — the timer still invokes the legacy ungated script until root installs the wrapper.

**Action (one-liner):**

```bash
sudo install -m 0755 /opt/connex-staging/deploy/staging/connex-staging-deploy-wrapper.sh /usr/local/bin/connex-staging-deploy
```

Optional: staging is now invite-only workspace creation; if QA needs self-service registration there, root must add `CONNEX_WORKSPACES_ALLOW_CREATION=true` to the root-600 `backend.env`. See [STAGING_DEPLOY.md](STAGING_DEPLOY.md).

**Backup timers (added 2026-07-25, Wave 1 backup workstream):** the 30-day backup/PITR tooling ([BACKUP_RESTORE.md](BACKUP_RESTORE.md)) is merged but cannot be installed on staging as `dev` — systemd unit installation, `/etc/connex-backup`, and the Docker socket are all root-only there (`dev` is not in the `docker` group and MySQL runs in Docker with no host client tools). Root actions:

```bash
sudo /opt/connex-staging/deploy/backup/install.sh
sudoedit /etc/connex-backup/backup.env   # set CONNEX_BACKUP_DB_CONTAINER to the staging db container
sudo sh -c 'umask 077; printf "[client]\npassword=%s\n" "<CONNEX_DB_ROOT_PASSWORD from /opt/connex-staging/backend/.env>" > /etc/connex-backup/source.cnf'
sudo docker pull percona/percona-server:8.4   # PITR replay client tools (mysqlbinlog); dumps/archiving need nothing extra
sudo /opt/connex-staging/deploy/backup/install.sh   # rerun to render drop-ins from the edited env
```

*Mitigated meanwhile:* a manual staging backup was taken via SSH tunnel with the shipped tooling on 2026-07-26 and restore-verified locally (see #853); until root installs the timers, staging has no scheduled backups.

*Mitigated meanwhile:* sha-stamped builds force a fresh JAR per commit; staleness is detectable via `GET /api/version`.

## 7. Backup-retention decision

**Why it blocks:** retention length is a **design input** to Wave 1's backup automation, and the DPA's 30-day deletion clause (item 1) constrains it — decide retention first, then build, or the automation is built twice.

**Decided (2026-07-25):** **30-day rolling retention + point-in-time recovery** — daily full logical dumps plus MySQL binlog archiving, 30-day retention with automated pruning; nothing (dumps, archived binlogs, or server-side binlogs) is retained beyond 30 days, deliberately reconcilable with the DPA's 30-day post-termination deletion clause. Implemented in `deploy/backup/`; policy, published RPO/RTO, and restore runbooks live in [BACKUP_RESTORE.md](BACKUP_RESTORE.md). Recorded on [#853](https://github.com/itkla/connex/issues/853).

## 8. Staging `connex_pub` cutover decision

**Why it blocks:** preview.connexcrm.jp has accumulated real data across ~300 auto-deployed commits. The Wave 2 canonical-identity backfill and warmth consolidation will collide with pre-existing duplicates and change numbers people have seen. Wave 5 executes whichever decision is made.

**Action:** decide **migrate or wipe** at 1.0. If any pilot-customer data lands there before then, wipe becomes unavailable and the migration path must be planned.

## 9. Error-monitoring vendor

**Why it blocks:** Wave 1's observability floor needs an error sink on both tiers; shipping error payloads to a foreign SaaS is an APPI cross-border transfer that must be named in `docs/SECURITY.md` §4/§5 and the DPA (item 1).

**Action:** pick the vendor (or self-hosted alternative) with counsel in the loop.

## 10. Native-JP reviewer

**Why it blocks:** message-key parity is CI-enforceable, but business-register Japanese quality is not. Every new 1.0 surface ships EN/JA, and Wave 5's quality gate needs a named native reviewer.

**Action:** name the person (or engage one) for the Wave 5 review of the highest-traffic namespaces.

## 11. v0.9.0 release rehearsal prerequisites

**Why it blocks:** the first-ever execution of `release.yml` (a Wave 1 exit criterion, and a hard gate before Wave 5 — the first run must not be the v1.0.0 tag) is preflighted and parked on three owner actions; the pipeline's first job fails within ~1 minute without the first two. See the preflight comment on [#853](https://github.com/itkla/connex/issues/853) for the full walkthrough of what the pipeline does.

**Actions:**

1. Create a fine-grained PAT for `itkla/connex` with **Administration: read** and set it as the `CONNEX_RELEASE_ADMIN_TOKEN` Actions secret (`gh secret set CONNEX_RELEASE_ADMIN_TOKEN --repo itkla/connex`).
2. Enable **immutable releases** on the repository. Note: once enabled, any published version number is burned forever — a failed rehearsal retry becomes `v0.9.1`.
3. Accept the pipeline's inherent side effects — image pushes to GHCR and permanent public Sigstore/Rekor signing entries.
