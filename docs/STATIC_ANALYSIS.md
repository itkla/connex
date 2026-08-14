# Static Application Security Testing

Connex uses GitHub CodeQL advanced setup as the repository's security-oriented static analysis
gate. The implementation lives in `.github/workflows/security.yml` and follows that workflow's
existing classify-per-surface-required aggregation pattern.

## What runs

- `Backend SAST (CodeQL)` analyzes `backend/` as `java-kotlin` with Java 26. CodeQL uses manual
  build mode and observes the real `compileJava` and `compileTestJava` Gradle builds.
- `Frontend SAST (CodeQL)` analyzes `frontend/` as `javascript-typescript` with build mode `none`,
  as required for an interpreted language.
- Both jobs use GitHub's `security-extended` suite. It contains the high-precision default security
  queries plus broader security queries. Connex deliberately does not use `security-and-quality`:
  non-security quality findings belong in lint, typecheck, and review, while this gate needs a clear
  vulnerability signal and a manageable false-positive workflow.

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
gate queries code scanning with that pull request number, so default-branch findings do not block an
unrelated pull request.

Pushes to `main` run both analyses to maintain the base-branch result used by pull request
comparison. The existing Sunday schedule runs both full-directory analyses for deeper recurring
coverage. Merge queues and manual dispatches also run both surfaces.

## Blocking and failure behavior

After CodeQL uploads an analysis and waits for GitHub to process it, the selected job queries all
open CodeQL alerts attributed to that pull request. Any alert with CodeQL security severity
`critical` or `high`, or generic SARIF severity `error`, fails the job. Pagination is mandatory and
the response is structurally validated by `.github/scripts/check-codeql-alerts.py`.

This gate is fail-closed:

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

## Finding ownership and deadlines

Every finding follows [VULNERABILITY_MANAGEMENT.md](VULNERABILITY_MANAGEMENT.md). GitHub is the
system of record: create or link a `Security` issue, retain the CodeQL alert number and URL, apply
the effective severity and matching priority label, assign a remediation owner, and record the
clock start and due timestamp. Duplicate alerts link to the oldest issue rather than starting an
untracked baseline.

Existing alerts found by the first `main` analysis are not accepted as a permanent baseline. Each
one receives the same owner and deadline as a new finding, measured from that first detection, and
is remediated or placed under a fixed-term exception. The first scan's tracking issues and CodeQL
run are the audit inventory.

## False positives and suppressions

Suppressions are exceptional. Connex has no repository-wide CodeQL query exclusion at this time,
and source-level ignore comments are not permitted. Prefer fixing the construct so the query can
understand it. If evidence proves a false positive:

1. Create or update the finding's `Security` issue with the CodeQL alert and rule identifiers,
   technical reason, evidence, named remediation owner, Security Owner role as approver, expiry
   date, and a re-review date before expiry.
2. Have a reviewer independent of the original dismissal reproduce the evidence.
3. Dismiss the CodeQL alert as `false positive` with a comment linking the issue and stating its
   expiry. Dismissal without that record is invalid.
4. Reopen and reassess the alert at expiry, when the affected code or data flow changes, or when the
   query is materially updated. A renewed dismissal requires a new fixed expiry and review.

If a future `.github/codeql/` configuration adds a query filter, every excluded rule must have
adjacent YAML comments containing the tracking issue, reason, owner, approval, expiry, and re-review
date. An open-ended filter is forbidden. The same fixed-term exception and escalation rules apply
to `won't fix` or `used in tests` dismissals.

## Gate proof and independent retest

The initial gate is proved in the pull request for
[#1244](https://github.com/itkla/connex/issues/1244) by temporarily adding a deliberately vulnerable
fixture under `frontend/test/fixtures/codeql/`, recording the real failing CodeQL and
`Security — required` output, then removing the fixture before merge. The issue retains the run URL,
alert identifier, query identifier, severity, and failing output. A Security reviewer who did not
implement the gate independently repeats the test before CHK-089 moves from `NG` to `OK`.
