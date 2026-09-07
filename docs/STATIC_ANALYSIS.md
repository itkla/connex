# Static Application Security Testing

Connex uses GitHub CodeQL advanced setup as its security-oriented static analysis workflow. The
implementation lives in `.github/workflows/security.yml` and follows that workflow's existing
classify-per-surface-required aggregation pattern. Workflow failure and repository-level merge
enforcement are separate controls; the current enforcement limitation is documented below.

## What runs

- `Backend SAST (CodeQL)` analyzes `backend/` as `java-kotlin` with Java 26. CodeQL uses manual
  build mode and observes the real `compileJava` and `compileTestJava` Gradle builds.
- `Frontend SAST (CodeQL)` analyzes `frontend/` as `javascript-typescript` with build mode `none`,
  as required for an interpreted language.
- Both jobs use GitHub's `security-extended` suite. It contains the high-precision default security
  queries plus broader security queries. Connex deliberately does not use `security-and-quality`:
  non-security quality findings belong in lint, typecheck, and review, while this workflow needs a
  clear vulnerability signal and a manageable false-positive process.

The CodeQL actions are pinned to immutable revisions and maintained by Dependabot's `github-actions`
updates. Only the two CodeQL jobs receive `security-events: write`; the workflow retains
`contents: read` as its top-level default. CodeQL sends SARIF to GitHub code scanning for this public
GitHub repository. No third-party scanner, SaaS account, or scanner token receives Connex source or
secrets.

## Selection and scan cadence

`.github/scripts/classify-ci-changes.py` selects backend SAST for every changed path under
`backend/` and frontend SAST for every changed path under `frontend/`. A workflow, action, CI script,
Dependabot, or secret-scanning policy change fails safe to both jobs. Empty, invalid, unavailable,
or unclassified change sets also select the complete suite.

Pull requests therefore scan only affected language surfaces, but each selected CodeQL job analyzes
its full configured directory. Each analysis is scoped by a repository-relative `paths` entry in its
per-language configuration file (`.github/codeql/backend.yml`, `.github/codeql/frontend.yml`), never
by the action's `source-root` input, so every result location is a repository-relative path.
Pull-request analyses run with the codeql-action's diff-informed analysis disabled
(`CODEQL_ACTION_DIFF_INFORMED_QUERIES: 'false'` on both SAST jobs): the action would otherwise
prune the uploaded results to the pull request's diff ranges, and the gate needs the complete result
set on the merge ref. Both properties are asserted by `test_security_workflow.py`;
*Third generation — 2026-09 path restoration* in [SAST_TRIAGE_LOG.md](SAST_TRIAGE_LOG.md) records
the analysis generations those two settings produced.

The workflow's explicit check queries code scanning for every open alert on the analysed ref
(`github.ref`, i.e. `refs/pull/<n>/merge`) and, separately, for every open alert on the base branch
ref (`refs/heads/main`, or the merge group's `base_ref`). It filters both responses to the job's
exact analysis category and fails on any Critical, High, or error-severity alert whose number is
open on the analysed ref but **not** open on the base ref. Because alert numbers are repository-wide
identities shared across refs, this compares findings rather than diff lines: a finding the pull
request introduces indirectly, whose result location is outside the diff, blocks exactly like one
inside it, while a finding already open on `main` under the same number does not fail an unrelated
pull request. A pull request whose base branch has never been analysed has an empty base set, so
every open Critical/High on its merge ref blocks: a stacked pull request against a feature branch
is stricter than one against `main`, never looser. A frontend alert cannot be attributed to the
backend job, or the reverse. Dismissing the alert through the procedure below is the sanctioned way
to unblock; a finding that is `fixed` on `main` and reappears on a pull request blocks again as a
regression.

Pushes to `main` run both analyses to maintain the base-branch result used by that comparison and
are gated themselves (see below). The existing Sunday schedule runs both full-directory analyses for
deeper recurring coverage. Manual dispatches also run both surfaces. A `merge_group` run analyzes the
queue's synthetic combined ref, queries alerts for that exact `github.ref`, subtracts the base ref's
open set, filters by analysis category, and fails on any remaining Critical, High, or error-severity
result. This catches a vulnerable data flow that exists only in the combined queue state. No merge
queue is configured as of 2026-08-13, so this is a latent workflow path rather than an active
repository control. Pull requests from forks are refused by the gate step outright (`::error::fork
pull requests are not gated`); the repository has none.

## Workflow failure and merge enforcement

After CodeQL uploads an analysis and waits for GitHub to process it, the selected job queries all
open CodeQL alerts on the analysed ref and, for pull-request and merge-group runs, on the base ref.
`.github/scripts/check-codeql-alerts.py` validates that each alert's `most_recent_instance.ref` is
the ref it was queried for and that both the alert and its instance are `open`, considers only the
selected job's `most_recent_instance.category`, and fails the job on any matching alert with CodeQL
security severity `critical` or `high`, or generic SARIF severity `error`, that is absent from the
base ref's open set. Pagination is mandatory and every response is structurally validated; a ref
mismatch is an invalid response, not a pass.

**An empty analysis is refused, not passed.** Before it reads a single alert, the same step fetches
the newest code-scanning analyses of the analysed ref and, on pull-request and merge-group runs, of
the base ref, and hands them to the checker (`--analyses`/`--commit`, `--baseline-analyses`). The
checker requires an analysis of the analysed commit (`github.sha`) in the job's category and refuses
one that stored `results_count: 0` while the base ref's newest analysis of the same category stored
results (exit `2`, `CodeQL analysis cannot be gated`). That is what every pull-request analysis
looked like from 2026-08-26: processed successfully, zero results, and an empty alert set that read
as a pass — `wait-for-processing` cannot tell the two apart, and after the alert inventory was
brought to zero a blind run and a clean run print the same line. On `push`, `schedule` and
`workflow_dispatch` runs there is no reference analysis, so only the analysis's existence is
checked; a pruned-but-non-empty upload is outside this step's reach and is left to the end-to-end
proof under *Workflow failure proof and independent retest*.

**The same step gates `main`.** On `push`, `schedule`, and `workflow_dispatch` runs the step queries
the analysed ref (`refs/heads/main` for the first two) with no baseline, so any open Critical, High,
or error-severity alert on `main` in the job's category fails `Backend SAST (CodeQL)` or
`Frontend SAST (CodeQL)`, therefore `Security — required`, therefore the Security workflow run.
`.github/workflows/main-red-alert.yml` watches that workflow for `push` and `schedule` runs and opens
or updates the `main is red` issue (label `ci-main-red`). This is the mechanical trigger for the
"Critical and High overdue findings are escalated immediately" rule in
[VULNERABILITY_MANAGEMENT.md](VULNERABILITY_MANAGEMENT.md): a finding that reaches `main` without a
dismissal — including one a pull request slipped past the gate, and one a CodeQL query update
surfaces on an unchanged tree during the weekly scan — is red within one run rather than waiting for
someone to look at the alert list. It also means the default-branch alert set must be kept at zero
open Critical/High: the sanctioned paths are remediation or a dismissal that carries the record
described under *False positives and suppressions*.

Within a selected workflow run, this alert check is fail-closed:

- CodeQL initialization, build, analysis, or upload failure fails the selected SAST job;
- a timeout cancels that job and produces a non-success result;
- an unavailable or unauthorized code-scanning API makes the `gh api` command fail;
- malformed, incomplete, unknown-severity, or non-open API results make the checker fail;
- an analysis of the analysed commit that is missing, or that stored zero results while the base
  ref's newest analysis of the same category stored some, makes the checker refuse the run — a
  processed upload carrying zero results is the 2026-08-26 failure class, not a pass; and
- the final `Security — required` job rejects every failed or cancelled dependency and separately
  requires an exact `selected → success` or `not selected → skipped` result for each SAST job.

There is no `continue-on-error`. A broken or unavailable CodeQL action therefore appears as a failed
or timed-out `Backend SAST (CodeQL)` or `Frontend SAST (CodeQL)` check, followed by a failed
`Security — required` check. Documentation-only changes may legitimately skip both jobs because the
classifier did not select either code surface.

The workflow's red result prevents a merge. As measured on 2026-09-01, `main` branch protection
requires nine status checks, every one bound to GitHub Actions (`app_id` 15368), and three of them
are this control: `Security — required`, `Backend SAST (CodeQL)` and `Frontend SAST (CodeQL)`. This
supersedes the 2026-08-13 measurement previously recorded here, which found none of them required.
This paragraph's own rule — update only after the setting is independently verified — is satisfied
by the CHK-089 independent re-test recorded on
[#1244](https://github.com/itkla/connex/issues/1244#issuecomment-5506662478), which measured the
same required contexts against the live API.

Four limits qualify that gate. Not every one is closed by branch protection, and none is a reason
to describe the gate as absent:

- **Pull-request attribution scoping — closed on #1244.** The check used to query code scanning
  with the pull request number, which returns only alerts whose complete result location is in the
  pull request diff, and that scope was once recorded here as an accepted product decision. It is no
  longer the scope: the check now compares the full open alert set on the analysed ref against the
  base ref, as described above, and `test_codeql_alerts.py` pins the case of a High whose location
  is outside the diff (`test_alert_outside_the_pull_request_diff_blocks_when_absent_from_main`). The
  earlier framing also concealed a defect — see *Third generation — 2026-09 path restoration* in
  [SAST_TRIAGE_LOG.md](SAST_TRIAGE_LOG.md).
- **`strict: false` — accepted, compensated.** Branch protection does not require a pull request to
  be up to date with `main`, so a pull request analysed against an older base can merge. The `main`
  gate above is the compensating detection: the merge commit's own push run re-evaluates the
  combined tree and turns `main` red if the combination introduced a blocking alert.
- **Administrator bypass — closed 2026-09-05 on #1244.** `enforce_admins` was enabled on
  2026-09-05 and verified the same day, so an administrator can no longer merge past a failed
  required check: `gh api repos/itkla/connex/branches/main/protection --jq
  .enforce_admins.enabled` returned `true`, and the unauthenticated
  `curl -s https://api.github.com/repos/itkla/connex/branches/main | jq
  '.protection.required_status_checks.enforcement_level'` returned `"everyone"` (it read
  `"non_admins"` on 2026-09-02). Re-verify with the same two commands. The canary proof asserts it
  every week under `--require-admin-enforcement`, so silently turning it off turns the proof red.
  **Emergency path**, for a CI outage that leaves a required check unable to pass at all:
  `gh api -X DELETE repos/itkla/connex/branches/main/protection/enforce_admins`, land the fix, and
  `gh api -X POST .../enforce_admins` again **within the same incident**. Both calls appear in the
  repository audit log; the incident record must name who ran them and why.
- **No required review of the gate itself — expiring acceptance.** `required_approving_review_count`
  is `0`, the repository defines no rulesets, and there is no `CODEOWNERS`. The workflow and
  classifier still execute from the pull request's checkout, so the self-modification limitation
  below is unmitigated. Accepted 2026-09-04 — owner Hunter Nakagawa, approver Security Owner role,
  **expiry 2027-02-14**, **re-review 2027-01-14** — because the repository has one committer and its
  agents ship under that same account: a code-owner review requirement no one can satisfy would stop
  every merge rather than add an independent reviewer. The mandatory trigger below is unchanged.

Branch protection is administered outside this change; this document must be updated only after the
setting is independently verified.

## Incident record — result paths and the blind window (2026-08-26 → 2026-09-07)

**The pull-request gate reported success without examining anything for that entire window.** This
is recorded as a control failure, not as a footnote.

**Mechanism.** PR #1294 (`6984ae910`, merged 2026-08-26T13:05:36Z) replaced the workflow's inline
`config: paths: [backend|frontend]` with a shared `config-file:` plus `source-root: backend` and
`source-root: frontend`. `source-root` makes CodeQL emit SARIF locations relative to that subtree
(`src/main/java/...`, `app/...`) instead of to the repository root. The codeql-action prunes
pull-request results before upload in `filterAlertsByDiffRange`, which matches a result's location
against the pull request's diff ranges with `range.path === locationUri` — exact string equality
against repository-relative paths. Nothing ever matched.

**Evidence.** Every pull-request analysis from that date stored `results_count: 0`
(`analyses?ref=refs/pull/<n>/merge`), so `alerts?pr=<n>` returned nothing and the gate step printed
`CodeQL alert check passed: 0 queried open alert(s)`. The same commit analysed on `refs/heads/main`
stored 58 java-kotlin and 8 javascript-typescript results. PR #1574 merged an **in-diff** HIGH —
alert #153, `java/csrf-unprotected-request-type` at `ReportController.java:126`, inside the pull
request's own hunk — with the gate green; the first post-merge `main` analysis raised it.

**Scope.** 60 pull requests merged after `6984ae910` and before the restoration and were therefore
gated blind:

#1467, #1469, #1470, #1471, #1476, #1474, #1482, #1480, #1484, #1487, #1488, #1485, #1492, #1493,
#1498, #1500, #1503, #1490, #1495, #1496, #1504, #1507, #1509, #1514, #1505, #1497, #1515, #1516,
#1526, #1524, #1528, #1519, #1536, #1531, #1525, #1535, #1548, #1551, #1553, #1552, #1554, #1555,
#1556, #1550, #1557, #1558, #1564, #1567, #1568, #1566, #1572, #1573, #1569, #1574, #1575, #1576,
#1577, #1579, #1580, #1581

The retrospective scan of their combined result is the `main` alert inventory measured after the
restoration and recorded in [SAST_TRIAGE_LOG.md](SAST_TRIAGE_LOG.md) under *Third generation —
2026-09 path restoration*.

**Remediation.** Repository-relative `paths:` restored in per-language configuration files with
`source-root` removed; the regenerated alert identities re-dismissed from a committed pre-change
snapshot by `.github/scripts/replay-codeql-dismissals.py`; the gate re-scoped from diff attribution
to a base-vs-merge alert-identity comparison with diff-informed analysis disabled; the same step
extended to gate `main` itself.

**What keeps it closed.** Five independent assertions, each of which fails loudly:

1. `test_security_workflow.py` requires `paths:` in both configuration files and forbids
   `source-root` on either `init` step.
2. `test_security_workflow.py` requires `CODEQL_ACTION_DIFF_INFORMED_QUERIES: 'false'` on both SAST
   jobs, so no result is pruned before upload.
3. The gate step refuses, within the same run, an analysis of the analysed commit that stored zero
   results while the base ref's newest analysis of the same category stored some
   (`check-codeql-alerts.py --analyses`, exit `2`). The failure class itself — a processed, empty
   upload — fails the pull request on the spot instead of reading as a pass. A pruned but non-empty
   upload is outside its reach; that is what assertion 5 is for.
4. The gate compares alert identities across refs, so it no longer depends on GitHub's diff
   attribution being correct at all.
5. The weekly canary proof measures the outcome end to end: it asserts each analysis carries the
   base commit's result count **plus exactly one**, and that both fixture alerts are attributed
   under `pr=<n>` — which is only true while result paths are repository-relative. A silent
   regression of this class turns the proof red within a week.

## Known self-modification limitation

The workflow and classifier execute from the pull request's checkout. With no `CODEOWNERS` rule for
`.github/**` and zero required approving reviews, a writer could replace the workflow, classifier,
or check-producing job with a passing implementation under the same check name. This is accepted
today because the repository has one committer with administrative write access, so there is no
additional writer from whom the mechanism could protect itself.

Any additional person or automation identity gaining repository write access is the mandatory
trigger to revisit this acceptance before that access is used. At that point, run the two prepared
commands:

```bash
printf '/.github/ @itkla\n' > .github/CODEOWNERS
gh api -X PATCH repos/itkla/connex/branches/main/protection/required_pull_request_reviews \
  -F required_approving_review_count=1 -F require_code_owner_reviews=true
```

`Security — required` is already a required check and administrators can no longer bypass it, as
recorded above. Until code-owner review is active and verified, do not describe CodeQL as an
independently *tamper-resistant* merge gate: the required checks are repository-enforced and
administrator-proof, but the single writer can still change the mechanism that produces them.

## Finding ownership and deadlines

Every finding follows [VULNERABILITY_MANAGEMENT.md](VULNERABILITY_MANAGEMENT.md). GitHub is the
system of record: create or link a `Security` issue, retain the CodeQL alert number and URL, apply
the effective severity and matching priority label, assign a remediation owner, and record the
clock start and due timestamp. Duplicate alerts link to the oldest issue rather than starting an
untracked baseline.

Existing alerts found by the first `main` analysis are not accepted as a permanent baseline. Each
one retains its first detection timestamp, is acknowledged by the Security Owner, receives the same
owner and deadline as a new finding measured from acknowledgement, and is remediated or placed under
a fixed-term exception. The first scan's tracking issues and CodeQL run are the audit inventory.

That first baseline is triaged in [SAST_TRIAGE_LOG.md](SAST_TRIAGE_LOG.md), which records the
per-rule disposition, evidence, owner, approver, expiry, and re-review date for every alert it
covers. Add later baselines or batch triages to that log rather than starting a new untracked one.

## False positives and suppressions

Suppressions are exceptional, and source-level ignore comments are not permitted. Prefer fixing the
construct so the query can understand it. **Connex has no repository-wide query exclusions, and the
filter lists in `.github/codeql/backend.yml` and `.github/codeql/frontend.yml` are asserted empty by
`test_security_workflow.py`.**

It used to have one: `java/potentially-weak-cryptographic-algorithm`, for the SHA-1 index the HIBP
k-anonymity Range API requires, governed by
[#1295](https://github.com/itkla/connex/issues/1295). That exclusion was removed in
[#1464](https://github.com/itkla/connex/issues/1464) because its scope was wrong in a way the
annotation concealed. A CodeQL query filter can name the exact query ID but cannot combine it with
the source path that produced one result, so excluding a query to accept one finding silences it for
**every** occurrence — in that case, for every other MD5 and SHA-1 in the backend, including token
handling, signatures and authentication, where it would be a real finding. The rule ID is the
narrowest available *filter*; it is not the narrowest available *control*.

A dismissal is. It applies to one alert at one location, leaves the query live everywhere else, and
carries the same accountability record. **Accept a false positive by dismissing its alert, never by
filtering its query.**

If evidence proves another false positive:

1. Create or update the finding's `Security` issue with the CodeQL alert and rule identifiers,
   technical reason, evidence, named remediation owner, Security Owner role as approver, expiry
   date, and a re-review date before expiry.
2. Have a reviewer independent of the original dismissal reproduce the evidence.
3. Dismiss the CodeQL alert as `false positive` with a comment linking the issue and stating its
   expiry. Dismissal without that record is invalid.
4. Reopen and reassess the alert at expiry, when the affected code or data flow changes, or when the
   query is materially updated. A renewed dismissal requires a new fixed expiry and review.

The filter list must stay empty. If a case ever genuinely cannot be expressed as a dismissal, the
filter that carries it must have adjacent YAML comments containing the tracking issue, reason,
owner, approval, expiry, and re-review date; an open-ended filter is forbidden, and the emptiness
assertion in `test_security_workflow.py` must be updated deliberately rather than deleted. The same
fixed-term exception and escalation rules apply to `won't fix` or `used in tests` dismissals.

## Workflow failure proof and independent retest

The gate's failure behaviour is proved continuously, not once. A **permanent, never-merged canary
pull request** carries one deliberately vulnerable fixture per analysed language, and
`.github/workflows/sast-canary-proof.yml` re-runs that pull request's own `Security` workflow every
Sunday at 21:37 UTC (and on `workflow_dispatch`) and asserts the result.

| | |
| --- | --- |
| Branch | `canary/sast-gate-proof` (base `main`, label `sast-canary`, **not** a draft) |
| Pull request | #1593, titled `[DO NOT MERGE] CodeQL gate canary (CHK-089)` |
| Frontend fixture | `frontend/test/fixtures/codeql/intentional-command-injection.mjs` → `js/command-line-injection`, critical |
| Backend fixture | `backend/src/test/java/ooo/klae/connex/backend/codeqlfixture/IntentionalCommandInjectionFixture.java` → `java/command-line-injection`, critical |

The fixtures are inert where they live: the `.mjs` file is outside `tsconfig.json`'s `include`,
outside Vitest's and Playwright's test globs, and is not linted by CI; the Java file is test source,
is never referenced, is not component-scanned, is excluded from the WAR, and sits outside the
architecture tests' walk of `backend/src/main/java`. Both are analysed by CodeQL, and neither ever
reaches `main` — the proof asserts that both paths return 404 on `?ref=main`.

`.github/scripts/verify-sast-canary.py` fails the proof unless **all** of the following hold:

- both SAST jobs concluded `failure` **at** `Block Critical, High, or error-severity alerts`, with
  `Analyze … with CodeQL` green — a tool failure is reported as a tool failure, never as a proof;
- `Security — required` concluded `failure` and `Classify security impact` concluded `success`;
- both fixtures have an open Critical alert on `refs/pull/<n>/merge` under the expected rule id,
  path and analysis category;
- the same two alert numbers appear under `alerts?pr=<n>` — attribution only works while result
  paths are repository-relative, so this re-proves the incident above stays closed;
- each category's analysis of the merge commit carries exactly one result more than `main`'s
  analysis of that merge commit's first parent — the invariant that nothing is being pruned. The
  workflow walks `main`'s analysis list newest-first, up to 20 pages of 100, until it finds that
  parent; a parent it cannot find is reported as an aged canary with the rebase remedy, never as a
  count violation;
- branch protection still requires `Security — required`, `Backend SAST (CodeQL)` and
  `Frontend SAST (CodeQL)`, with `enforcement_level` `everyone`;
- the pull request's `mergeStateStatus` is `BLOCKED` and all three check runs are `FAILURE`.

It writes those tables to the run's step summary, and a failed proof opens or comments on a
`sast-canary-failed` issue. The reporting step runs on `failure() || cancelled()`, so a proof that
is cancelled or hits its 60-minute job timeout reports too, with the job status in the body; a
workflow-file error that concludes `startup_failure` runs no step at all and is visible only in the
Actions tab.

**Refresh rule.** A re-run reuses the original merge commit, so a gate change on `main` is not
exercised until the canary is rebased. Whenever `.github/workflows/security.yml`,
`.github/scripts/check-codeql-alerts.py`, `.github/scripts/classify-ci-changes.py` or
`.github/codeql/*.yml` changes on `main`, **and in any case at least every 28 days**, rebase
`canary/sast-gate-proof` onto `origin/main` and `git push --force-with-lease`. This is the
repository's single documented force-push exception: a single-purpose branch that is never merged.
The time bound exists because GitHub refuses to re-run a workflow run more than 30 days after its
initial run, and because the base commit of an old merge commit drifts down `main`'s analysis list
(about a hundred analyses a week at the 2026-09 merge rate, against the proof's 20-page budget).
The proof compares each gate file's blob id between `main` and the analysed merge commit, refuses a
canary Security run older than 28 days before it re-runs anything, and fails each case with the
same explicit rebase instruction.

**Honest limits.** The proof covers detection, gate-step failure, required-check failure,
attribution, full-result upload, protection settings and mergeability. It does not exercise every
merge path, and it re-runs an existing analysis rather than creating a new pull request.

**Independent retest.** A Security reviewer who did not implement the workflow dispatches
`sast-canary-proof.yml`, reads its step summary, and re-measures `branches/main/protection` before
CHK-089 moves from `NG` to `OK`.
