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
its full configured directory. GitHub associates results with the pull request merge ref and shows
only alerts whose complete result location is in the pull request diff. The workflow's explicit
check queries code scanning with that pull request number, then filters the response to the job's
exact analysis category. A frontend alert therefore cannot be attributed to the backend job, or the
reverse, and default-branch findings do not fail an unrelated pull request run.

Pushes to `main` run both analyses to maintain the base-branch result used by pull request
comparison. The existing Sunday schedule runs both full-directory analyses for deeper recurring
coverage. Manual dispatches also run both surfaces. A `merge_group` run analyzes the queue's
synthetic combined ref, queries alerts for that exact `github.ref`, filters by analysis category,
and fails on any Critical, High, or error-severity result. This catches a vulnerable data flow that
exists only in the combined queue state. No merge queue is configured as of 2026-08-13, so this is a
latent workflow path rather than an active repository control.

## Workflow failure and merge enforcement

After CodeQL uploads an analysis and waits for GitHub to process it, the selected job queries all
open CodeQL alerts attributed to that pull request or merge-group ref. It validates each alert's
`most_recent_instance.category` and considers only the selected job's category. Any matching alert
with CodeQL security severity `critical` or `high`, or generic SARIF severity `error`, fails the
job. Pagination is mandatory and the response is structurally validated by
`.github/scripts/check-codeql-alerts.py`.

Within a selected workflow run, this alert check is fail-closed:

- CodeQL initialization, build, analysis, or upload failure fails the selected SAST job;
- a timeout cancels that job and produces a non-success result;
- an unavailable or unauthorized code-scanning API makes the `gh api` command fail;
- malformed, incomplete, unknown-severity, or non-open API results make the checker fail; and
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

Three limits qualify that gate. None is closed by branch protection, and none is a reason to
describe the gate as absent:

- **Pull-request attribution scoping — documented product decision.** The check queries code
  scanning with the pull request number, and as recorded above GitHub shows only alerts whose
  complete result location is in the pull request diff. A Critical or High finding that a pull
  request introduces indirectly, whose result location falls outside that diff, does not appear in
  the response, so the checker passes. This is the accepted scope of the control, not a defect in
  it.
- **Administrator bypass — open hardening item.** `enforce_admins` is `false`, so an administrator
  can merge past a failed required check.
- **No required review of the gate itself — open hardening item.** `required_approving_review_count`
  is `0`, the repository defines no rulesets, and there is no `CODEOWNERS`. The workflow and
  classifier still execute from the pull request's checkout, so the self-modification limitation
  below remains unmitigated.

Branch protection is administered outside this change; this document must be updated only after the
setting is independently verified.

## Known self-modification limitation

The workflow and classifier execute from the pull request's checkout. With no `CODEOWNERS` rule for
`.github/**` and zero required approving reviews, a writer could replace the workflow, classifier,
or check-producing job with a passing implementation under the same check name. This is accepted
today because the repository has one committer with administrative write access, so there is no
additional writer from whom the mechanism could protect itself.

Any additional person or automation identity gaining repository write access is the mandatory
trigger to revisit this acceptance before that access is used. At that point, add code-owner review
for `.github/**` and require more than zero approving reviews in branch protection or a ruleset.
`Security — required` is already a required check, as recorded above. Until the remaining settings
are active and verified, do not describe CodeQL as an independently *tamper-resistant* merge gate:
the required checks are repository-enforced, but an administrator can both bypass them and change
the mechanism that produces them.

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
filter list in `.github/codeql/codeql-config.yml` is asserted empty by
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

The initial workflow failure behavior is proved in the pull request for
[#1244](https://github.com/itkla/connex/issues/1244) by temporarily adding a deliberately vulnerable
fixture under `frontend/test/fixtures/codeql/`, recording the real failing CodeQL and
`Security — required` output, then removing the fixture before merge. The issue retains the run URL,
alert identifier, query identifier, severity, and failing output. A Security reviewer who did not
implement the workflow independently repeats the test before CHK-089 moves from `NG` to `OK`.
