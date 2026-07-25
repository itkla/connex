# Connex 1.0 — External Blockers & Owner Actions

Tracking companion to the 1.0 release program (umbrella epic [#848](https://github.com/itkla/connex/issues/848), waves [#852](https://github.com/itkla/connex/issues/852)–[#857](https://github.com/itkla/connex/issues/857)). Everything in this document requires action **outside the codebase** — legal counsel, third-party verification programs, root access, staffing, or a business decision. Engineering does not block on these day-to-day, but each one gates a specific release wave, and several have month-scale external lead times. Items are ordered by urgency of *starting*, not finishing.

Update the Status column in place as items move; the wave issues reference this file rather than duplicating it.

| # | Blocker | Gates | Status |
|---|---------|-------|--------|
| 1 | Legal counsel engagement (APPI) | Wave 3 submission, Wave 4 legal-effective | Not started |
| 2 | Corporate entity facts | Item 1; legal pages | Not started |
| 3 | Google OAuth verification + CASA Tier 2 | Wave 3 *production enablement* only | Not started |
| 4 | Microsoft publisher verification | Wave 3 production enablement (MS provider) | Not started |
| 5 | Penetration-test procurement | Wave 5 execution | Not started |
| 6 | Staging root actions | Wave 0 close-out (mitigated) | Pending |
| 7 | Backup-retention decision | Wave 1 (design input) | Undecided |
| 8 | Staging `connex_pub` cutover decision | Wave 5 execution | Undecided |
| 9 | Error-monitoring vendor (APPI) | Wave 1 observability floor | Undecided |
| 10 | Native-JP reviewer | Wave 5 EN/JA quality gate | Unassigned |

## 1. Legal counsel engagement (APPI)

**Why it blocks:** four public pages (`/privacy`, `/legal`, `/disclosure`, `/tokushoho`) carry draft status, and the DPA template is not executable. Google's restricted-scope review (item 3) evaluates the **published** privacy policy — so counsel must finish **before** that submission, not in parallel.

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

**Why it blocks:** `gmail.readonly` is a **restricted** scope: OAuth verification plus an annual CASA Tier 2 third-party security assessment (month-scale). `calendar.readonly` is sensitive-scope (verification, no CASA).

**What it does NOT block:** engineering. All sync is built and QA'd against an unverified app in test-user mode (≤100 allow-listed test users) and ships **flag-gated**. Verification is a production-enablement switch, not a code dependency — 1.0 ships either way.

**Action:** register the production OAuth app, submit for verification the day the privacy policy is counsel-final (item 1), start the CASA Tier 2 process, and record case IDs on [#855](https://github.com/itkla/connex/issues/855).

## 4. Microsoft publisher verification

**Why it blocks:** production Microsoft Graph mail/calendar consent for external tenants requires a verified publisher (MPN account). Materially lighter than item 3.

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

*Mitigated meanwhile:* sha-stamped builds force a fresh JAR per commit; staleness is detectable via `GET /api/version`.

## 7. Backup-retention decision

**Why it blocks:** retention length is a **design input** to Wave 1's backup automation, and the DPA's 30-day deletion clause (item 1) constrains it — decide retention first, then build, or the automation is built twice.

**Action:** choose the retention window (and thus RPO/RTO targets) reconcilable with the DPA deletion commitment. Record on [#853](https://github.com/itkla/connex/issues/853).

## 8. Staging `connex_pub` cutover decision

**Why it blocks:** preview.connexcrm.jp has accumulated real data across ~300 auto-deployed commits. The Wave 2 canonical-identity backfill and warmth consolidation will collide with pre-existing duplicates and change numbers people have seen. Wave 5 executes whichever decision is made.

**Action:** decide **migrate or wipe** at 1.0. If any pilot-customer data lands there before then, wipe becomes unavailable and the migration path must be planned.

## 9. Error-monitoring vendor

**Why it blocks:** Wave 1's observability floor needs an error sink on both tiers; shipping error payloads to a foreign SaaS is an APPI cross-border transfer that must be named in `docs/SECURITY.md` §4/§5 and the DPA (item 1).

**Action:** pick the vendor (or self-hosted alternative) with counsel in the loop.

## 10. Native-JP reviewer

**Why it blocks:** message-key parity is CI-enforceable, but business-register Japanese quality is not. Every new 1.0 surface ships EN/JA, and Wave 5's quality gate needs a named native reviewer.

**Action:** name the person (or engage one) for the Wave 5 review of the highest-traffic namespaces.
