# CodeQL Baseline Triage Log

Tracking record for the CodeQL alerts that the first full-tree `main` analysis produced after
CHK-089 ([#1244](https://github.com/itkla/connex/issues/1244)) enabled the SAST workflow. It exists
to satisfy the finding-ownership and false-positive procedures in
[STATIC_ANALYSIS.md](STATIC_ANALYSIS.md) and the intake and SLA rules in
[VULNERABILITY_MANAGEMENT.md](VULNERABILITY_MANAGEMENT.md). The umbrella issue is
[#1296](https://github.com/itkla/connex/issues/1296).

This log is not a baseline exemption. Every entry carries a disposition, an accountable owner, an
approver role, and — where an alert is not remediated in code — a fixed expiry and a re-review date.
An open-ended suppression is forbidden by [STATIC_ANALYSIS.md](STATIC_ANALYSIS.md).

## Baseline inventory

| Field | Value |
| --- | --- |
| Ref | `refs/heads/main` |
| Commit | `dde1b6020b9c50468a181ac67fdb7c2896019776` |
| Analyses | `1620875210` (`/language:java-kotlin`), `1620859661` (`/language:javascript-typescript`) |
| Measured | 2026-08-14 |
| Open alerts | 70 |
| Security Owner / approver | Hunter Nakagawa, Founder |
| Remediation owner | Hunter Nakagawa, Founder |
| Acknowledged (clock start) | 2026-08-14 |
| High due | 2026-08-28 |
| Medium due | 2026-10-13 |

| Count | Severity | Rule | Disposition |
| --- | --- | --- | --- |
| 38 | high | `java/csrf-unprotected-request-type` | Split by the 2026-08-30 independent re-review: mostly false positive, **7 real** (remediated below), 2 accepted by design |
| 6 | high | `java/user-controlled-bypass` | False positive — dismissal pending independent review |
| 4 | high | `java/polynomial-redos` | **Remediated** |
| 3 | high | `java/tainted-arithmetic` | False positive — dismissal pending independent review |
| 3 | high | `java/uncontrolled-arithmetic` | False positive — dismissal pending independent review |
| 2 | high | `js/xss-through-dom` | False positive — dismissal pending independent review |
| 2 | high | `js/insecure-randomness` | False positive (test-only) — dismissal pending independent review |
| 1 | high | `java/spring-disabled-csrf-protection` | **Remediated** |
| 1 | high | `js/insecure-temporary-file` | False positive (test-only) — dismissal pending independent review |
| 4 | medium | `java/log-injection` | False positive — dismissal pending independent review |
| 3 | medium | `java/unreleased-lock` | False positive — dismissal pending independent review |
| 3 | medium | `js/log-injection` | False positive (test-only) — dismissal pending independent review |

### Corrections to the figures first reported in #1296

- The count is **70**, not 73. The three `java/sensitive-log` alerts reported there are not present
  in this analysis; that rule was addressed in the
  [#1289](https://github.com/itkla/connex/issues/1289) remediation round, as #1296 anticipated.
- One rule was missing from the original table: `java/spring-disabled-csrf-protection` (#10). It is
  the root cause worth fixing in this batch and is remediated below.
- The original table's "primary assessment" for the 38 CSRF alerts — that CSRF is globally enabled
  and CodeQL cannot follow framework configuration — is **not** the reason those alerts are false
  positives. The query does not claim CSRF is disabled; it claims a state-changing action is served
  over a request method that CSRF does not protect. The verified reason is recorded below.

### `java/csrf-unprotected-request-type` — alert #77, `DocumentAcceptanceController.preview`

Raised on PR #1307 (e-signature delivery). Triaged 2026-08-15.

The rule fires on `GET /api/document-acceptance/{token}` for "an apparent state-changing action".
Two genuine defects it surfaced were **fixed**, not dismissed:

- `DocumentDeliveryService.downloadArtifact` wrote an audit record on a `GET`, so a forged cross-site
  request could fabricate a download entry on the signed-artifact trail. The audit write was removed;
  no other managed-object reader records one either.
- `DocumentAcceptanceService.preview` stamped `first_viewed_at` and appended a `viewed` event on the
  `GET`. Email scanners, prefetchers and URL-rewriting proxies all issue that request, so recipient
  view evidence in the completion certificate could be forged by ordinary mail infrastructure.
  Recording moved to `POST /api/document-acceptance/{token}/viewed`.

What remains is a false positive. After those fixes the only mutation reachable from the handler is
`DocumentAcceptanceRateLimiter`, which updates two in-process `ConcurrentHashMap` windows. Rate
limiting a public endpoint is required, not incidental, and an in-memory window is neither persistent
nor attacker-valuable state. The route is also necessarily CSRF-exempt: it is session-less, carries no
ambient authority, and is authorized solely by a 256-bit bearer token that a cross-site attacker
cannot supply.

Disposition: **false positive**, dismissed to unblock the required gate. Flagged here for independent
re-review because it was triaged by the same author as the change.

The rule re-raised as alert #79 against the same handler once the code moved, and was dismissed on the
same basis. Expect a new alert number whenever this handler changes shape: the heuristic reads the
locking read and transaction open as a state change, so it will keep firing on a route that is
necessarily CSRF-exempt. Re-check that the handler still records nothing before dismissing the next
one — that property, not the alert count, is what matters.

## Remediated

### `java/csrf-unprotected-request-type` — #110, #111, #114, #141, #142, #143, #144 (7 alerts)

The 2026-08-30 independent re-review of the baseline
([#1296](https://github.com/itkla/connex/issues/1296)) found that seven of the CSRF alerts are
**real**, not false positives. All seven are a `@GetMapping` that inserts a durable `audit_log` row
on a normal successful request. `JSESSIONID` is `SameSite=Lax`
(`backend/src/main/resources/application.yml:524`), and `Lax` **is** sent on cross-site top-level
`GET` navigation, so an attacker page could navigate a signed-in victim to any of these URLs and
forge entries in the evidence trail under that victim's actor id. The attacker cannot read the
response; the write is the impact.

These seven sit in the set of nine CSRF sites that were closed as `fixed` and re-raised under new
numbers on 2026-08-26 and so never received an individual dismissal record. The log's own standing
instruction — *"Re-check that the handler still records nothing before dismissing the next one"* —
is what had not been applied to them.

Each site was decided on its merits, using only the two remedies this log already set as precedent:
remove the audit write where the read does not warrant an evidence entry, or move the recording
behind an explicit state-changing route.

| Alert | Handler | Remedy |
| --- | --- | --- |
| #141 | `SearchController.search` | **Removed** the audit write |
| #144 | `SupportBundleController.supportBundle` | **Moved** the whole operation to `POST` |
| #143 / #142 | `SecretStoreDiagnosticsController` workspace / org diagnostics | **Removed** the audit write |
| #114 / #111 / #110 | `AiAssistantController` `?scope=retained` reads | **Moved** to explicit `POST` routes |

- **#141 — removed.** `SearchService:117` wrote `auditService.record("search", "search", null,
  query, ...)`, placing the **raw, untrimmed** caller query in the row's `targetLabel`. That is the
  worst of the seven: it let a cross-site navigation plant attacker-chosen free text in the trail,
  where it would later mislead a human reading the audit UI or a downstream consumer of the support
  bundle's `audit-slice.csv`. No peer read surface — companies, people, deals, notes, tasks — records
  an access row, and no runbook or compliance document requires one for search, so the write was
  removed outright, matching the `DocumentDeliveryService.downloadArtifact` precedent. `/api/search`
  could not have moved to `POST`: `frontend/app/(app)/search/page.tsx` calls it from a React Server
  Component, and `frontend/app/lib/api.ts` deliberately omits the CSRF header during SSR.
- **#143 / #142 — removed.** `SecretStoreLifecycleService` wrote a `secret_store.diagnostics.read`
  row from `diagnosticsForWorkspace` and `diagnosticsForOrg`. The payload is metadata-only key-health
  counters and never contains plaintext, ciphertext, wrapped data keys, or key material. Decisive
  fact: `TenantDiagnosticsService:122` and `:149` call those very same methods, so
  `GET /api/workspaces/{id}/diagnostics` and `GET /api/orgs/{id}/diagnostics` — the endpoints the
  settings diagnostics panel actually renders — emitted the same row on every panel load and were
  vulnerable in the same way without being flagged. Moving only the two flagged routes to `POST`
  would therefore have left the defect reachable. Removing the write closes all four GETs at once.
  `AuditService.SECRET_ACTIONS` and the `secret_store.diagnostics.read` display label are
  **deliberately retained**: `AuditService.sensitiveAction` uses them on the read/export path, and
  `audit_log` is append-only, so historical rows must keep rendering correctly.
- **#144 — moved to `POST`.** The handler wrote two rows through `recordStrictIndependentScoped`
  (an independent transaction, so they survive an outer rollback), ran full bundle assembly under a
  concurrency permit, and returned `Content-Disposition: attachment` — a forged navigation also
  dropped a ZIP of redacted organization data into the victim's downloads. Those audit rows are a
  genuine export-disclosure record and had to be kept, so the method was what was wrong. The route is
  now `POST /api/orgs/{orgId}/support-bundle`. There were no frontend callers;
  `deploy/support-bundle/collect.sh` now fetches a token from `GET /api/auth/csrf` with the same
  cookie jar and echoes it in the configured header, and `docs/SUPPORT_BUNDLE.md`,
  `docs/INTERNAL_OPERATIONS_RUNBOOK.md`, `docs/DEPLOYMENT.md` and
  `deploy/support-bundle/README.md` were corrected. `docs/SUPPORT_BUNDLE.md` had previously
  documented this exact exposure as an accepted risk; that paragraph now records the fix.
- **#114 / #111 / #110 — moved to `POST`.** The `?scope=retained` branches disclose a **departed**
  workspace member's private assistant transcripts to an `AI_SESSION_ADMIN`, and record the
  disclosure. That record is a genuine privacy artifact, so removing it was not acceptable; the read
  moved instead, to `POST /retained`, `POST /{id}/retained`, `POST /{sessionId}/tool-calls/retained`
  and `POST /{sessionId}/tool-calls/{toolCallId}/retained`. The `scope` query parameter is gone from
  the four GET handlers, which now fall through to the caller's own accessible-session read — a
  fail-safe outcome, since that read cannot return a departed member's private session. This cost
  nothing at the client: no frontend caller ever sent `scope`, and the API-client wrappers do not
  accept the argument. `AiAssistantController` already served `POST /scope-preview`, a POST that
  performs a read, so the shape was established in that class.
- **Deliberately NOT removed: the "accessible"-scope administrative read audit.** Alert #114 is
  raised on the `get()` handler, which additionally reaches
  `AiAssistantService.auditAdministrativeReads` and inserts an `ai.assistant.session.read` row with
  `{"scope": "accessible"}` whenever an `AI_SESSION_ADMIN` reads a session they did not create.
  Removing it would have made the flagged `GET` handler write-free and fully closed the alert, and
  an earlier revision of this change did remove it. That removal was **reversed by product
  decision**: "an administrator read another user's AI session" is a privacy-accountability record
  under the APPI entrustee posture, and deleting an accountability record to close a CSRF-forgery
  vector is not an acceptable trade. The record is preserved as it was, reusing the existing
  `AiAssistantSessionReadAudit` mechanism.

  **Residual, recorded deliberately:** because that row is still written from a `GET`, a cross-site
  top-level navigation can still cause it, so `#114` is expected to remain open on the `get()`
  handler even after this change. The severe half of #114 — the `?scope=retained` branch, which
  disclosed a departed member's private transcript and produced the most damaging false entry — is
  fully closed by the move to `POST`. Closing the remaining half and preserving the accountability
  record are mutually exclusive without also serving `GET /api/ai/assistant/sessions/{id}` over
  `POST`, which would break the primary assistant read path in the SPA. That trade is left to the
  Security Owner rather than decided here.

  For the record, the exposure the residual row carries is bounded: `auditAdministrativeReads`
  reaches a session only through the `accessibleSession` SQL fragment
  (`AiChatMapper.xml:104-114`), which is *own session OR shared session you have joined*, and
  holding `AI_SESSION_ADMIN` does **not** widen that predicate by one row. A forged row can
  therefore only ever name a session the victim had already been deliberately granted access to,
  and it carries no caller-supplied text — only the session id and a fixed scope string — so it
  cannot be used to plant chosen content in the trail the way #141 could.

Regression coverage: `SearchTenantIsolationTest` asserts a search writes no `search` row and plants
no caller text in any `target_label`; `SecretStoreLifecycleServiceTest` asserts both diagnostics
scopes write no row; `SupportBundleControllerTest` asserts a `GET` is refused and never reaches
assembly; `AiAssistantControllerTest` asserts the retained reads are `POST`-only and that the retired
`?scope=retained` query string never reaches a recorded read; `AiAssistantServiceTest` asserts an
admin's accessible-session read is still recorded and that the row carries no caller-supplied text.

### `java/csrf-unprotected-request-type` — #112, #148: accepted by design (`won't fix`)

The same re-review left these two open as UNCERTAIN, on the ground that each persists a
server-generated timestamp reflecting *when the forged request arrived*. Re-derived at
`origin/main` = `e5a8d3ee0`, both are accepted rather than changed. **Neither writes to
`audit_log`**, neither attributes an actor, and — the point the UNCERTAIN framing missed — **neither
timestamp can be moved anywhere except toward the truth.**

- **#112** — `AiAssistantController.getTurn` → `AiChatTurnPersistenceService.expireIfStale`. The
  `updateTurnTerminal` statement (`AiChatMapper.xml:640-657`) sets **only** `status` and
  `terminal_reason`; it writes no timestamp column at all. It is additionally guarded by
  `AND updated_at <= #{updatedBefore}`, where the cutoff is a full `DURABLE_LIFETIME` in the past, so
  it fires only on a turn that has been untouched past its expiry and is already due to be
  `TIMED_OUT`. The only timestamp that moves is the table's own `updated_at` bookkeeping column.
- **#148** — `WorkflowManualRunController.get` → `completeInvocationIfActive`
  (`WorkflowOperationsMapper.xml:394-401`) sets `completed_at` on an invocation whose constituent
  records are **already terminal** and whose `completed_at` is still `NULL`. The earliest value a
  forged request can write is the invocation's true completion moment, and the next legitimate read
  would write the same value. An attacker can only make the stamp *more* accurate, never fabricate a
  completion that did not occur.

The correct dismissal reason for both is **`won't fix` (accepted risk)**, not `false positive` —
they do mutate durable state on a `GET`, exactly as the query claims. No alert state was changed as
part of this remediation.

### `java/spring-disabled-csrf-protection` — #10

`SecurityConfig.java:198` disabled CSRF entirely when `connex.security.csrf-enabled` was false. The
flag was reachable in production through `CONNEX_SECURITY_CSRF_ENABLED`, was set by nothing in the
repository, deployment configuration, or `.env.example`, and had no legitimate production use.

Rather than dismiss the alert, the switch was removed: CSRF is now unconditional in `SecurityConfig`
and `WebSocketSecurityConfig`, and the property is gone from `application.yml` and from the three
tests that pinned it to `true`. This is the remedy [STATIC_ANALYSIS.md](STATIC_ANALYSIS.md) prefers —
fix the construct rather than suppress the query.

Regression coverage: `AiGenerationEndpointSecurityTest` now sets the removed property to `false` at
class level and asserts CSRF is still enforced.

### `java/polynomial-redos` — #11, #12, #13, #14

All four regexes consume request-controlled input. Measured on Java 26 before the fix:

| Alert | Site | Construct | Measured behaviour on hostile input |
| --- | --- | --- | --- |
| #14 | `ReferenceService` `NOTE_REFERENCE_DEFINITION` | `[ \t]` runs (flagged); `(?:\\.\|[^\]\\])+` before `\]` (not flagged) | tab runs 0–9 ms; label repetition **`StackOverflowError`** at ~2,000 characters |
| #13 | `ImportService.slug` | `^_+\|_+$` | 20k chars → 705 ms; 60k → 6.1 s; 120k → 23.0 s |
| #11 | `CompanyService.legacyWebsiteKey` | `/+$` | 20k chars → 1.06 s; 60k → 9.2 s; 120k → 37.0 s |
| #12 | `ImportService.validateRequired` | `^[^@\s]+@[^@\s]+\.[^@\s]+$` | Not reproducible — see below |

Reachability and severity, assessed per
[VULNERABILITY_MANAGEMENT.md](VULNERABILITY_MANAGEMENT.md#risk-adjusted-severity):

- **#14 is the material one, but not for the reason CodeQL gives.** The alert text names "many
  repetitions of `\t`" — the `[ \t]` indent and description runs. Those were measured at 0–9 ms up
  to 60k characters, so the flagged construct is the theoretical one. The construct CodeQL did
  **not** flag, the label repetition `(?:\\.|[^\]\\])+` before `\]`, is the one that actually
  fails: `markdownNoteTargets` runs over note, task, activity, comment and introduction prose, and
  any line beginning with `[` followed by roughly 2,000 characters before the closing bracket
  crashes the scan with an unhandled `StackOverflowError` on an authenticated request path,
  reachable from ordinary long-bracketed prose without hostile intent.

  Both are fixed by making every repetition in the pattern possessive, which is language-preserving
  here: no label alternative can consume a bare `]`, and no indent run can be followed by a space
  or tab that the next required token would accept. The trailing description group is also
  rewritten from `[ \t]+.*` to `[ \t].*`, which is exactly equivalent because `.` already covers
  further spaces and tabs, and removes the genuine ambiguity CodeQL objected to.

  **This is worth remembering when reading the rest of this log:** on the one finding in the batch
  that turned out to be a real crash, the query pointed at the wrong construct. Alert text is a
  starting point for triage, not the finding.
- **#13 is a real authenticated CPU-exhaustion path.** `ColumnMapping.customFieldLabel` carries no
  `@Size` constraint, so an import mapping can supply an arbitrarily long label, and `slug` is
  reached from `previewPersons`/`previewCompanies`/`previewDeals` through
  `requireCustomFieldPermission` before any insert. Replaced with an index-based trim.
- **#11 is quadratic but not practically exploitable.** `CompanyDto.website` is `@Size(max = 255)`
  and `@URL`-validated, so the reachable input is capped two orders of magnitude below the range
  where the blowup matters. Replaced with an index-based trim as defence in depth.
- **#12 could not be shown to be superlinear.** Four candidate hostile shapes (trailing whitespace,
  alternating dots, trailing dot, absent domain) all ran flat at 0–5 ms up to 120k characters on
  Java 26. The regex was replaced with a linear `isEmailShaped` scan because it is simpler and
  removes the alert, **not** because an exploitable ReDoS was demonstrated. This log records the
  negative result rather than inheriting the query's claim.

Behavioural equivalence for all four rewrites was checked against the originals across the probe
sets recorded in the pull request; there were no differences. Regression coverage:
`ReferenceServicePlainTextTest` gains three cases that fail with `StackOverflowError` against the
original pattern, and `ImportServiceTest` gains slug and email-shape cases. The pre-existing
`toPlainText_scansHostileUnmatchedLabelsInLinearTime` test does **not** cover the #14 input shape and
passed against the vulnerable pattern.

## False positives — recorded, dismissal pending independent review

Each entry below is evidenced and approved for dismissal by the Security Owner role, but the CodeQL
alerts are deliberately **left open**. [STATIC_ANALYSIS.md](STATIC_ANALYSIS.md) requires a reviewer
independent of the original dismissal to reproduce the evidence first, and states that a dismissal
without that record is invalid. The evidence here was produced by the triager, so the triager must
not also perform the dismissal.

Common terms unless an entry overrides them:

- **Remediation owner:** Hunter Nakagawa, Founder
- **Approver:** Security Owner role ([#1230](https://github.com/itkla/connex/issues/1230))
- **Expiry:** 2027-02-14
- **Re-review:** 2027-01-14
- **Additional reassessment triggers:** the cited data flow changes, or the CodeQL query is
  materially updated.

### `java/csrf-unprotected-request-type` — #31–#68 (38 alerts)

Every one of the 38 flagged handlers is a `@GetMapping`. The query fires when a handler served over
a method CSRF does not protect appears to perform a state-changing action, so the question is not
whether CSRF is configured — it is — but whether these GETs mutate state.

CodeQL's own cited "state-changing action" locations show the call graph is imprecise: 29 of the 38
results hit the SARIF cap of 100 related locations, reaching unrelated subsystems such as
`CampaignDispatchService`, `ProviderCaptureWorker` and `AiChatTurnPersistenceService` from handlers
like `SearchController.search`. A further 7 cite only `AuditIntegrityService:76/94/95` — the audit
chain-hash write that every authenticated request performs, including reads.

The remaining 2 results cite exactly one specific write, and both were verified by hand:

- `AiAssistantController.getTurn` reaches `AiChatTurnPersistenceService.expireIfStale`, an
  idempotent lazy transition of an already-past-cutoff turn to `TIMED_OUT`.
- `WorkflowManualRunController.get` reaches `WorkflowManualRunService` `completeInvocationIfActive`,
  an idempotent close-out of an invocation whose records are already terminal.

Both are time-driven reconciliation that would occur on the next read regardless, confer nothing on
a cross-site caller, and return their result under the same-origin policy. They are accepted as
designed rather than reclassified.

The three GETs with genuine security weight were verified individually:

- `ProviderConnectionController.callback` is an OAuth redirect callback, where GET is
  protocol-mandated. `ProviderConnectionService.completeCallback` validates and consumes a
  session-bound `state` before anything else and always redirects to the trusted app base URL —
  the correct OAuth CSRF defence.
- `TenantLifecycleController.export` redeems a one-time export grant, but the grant cookie is
  `HttpOnly`, `Secure`, `SameSite=Strict` and scoped to the exact download path, and `redeem`
  additionally binds actor and session id. A cross-site GET never carries it.
- `DeliveryUnsubscribeController` is unauthenticated, and correctly splits `preview` (GET,
  read-only) from `unsubscribe` (POST) — evidence the GET/POST boundary is already handled
  deliberately in this codebase.

### `java/user-controlled-bypass` — #25–#30 (6 alerts)

In all six the "user-controlled condition" is an input-validation guard whose failure branch
**throws**, so the "bypassed" sensitive method is not reached because the request was rejected:

- `WebAuthnController:129` — the guard at :120 rejects a null, blank or over-long passkey label with
  `BadRequestException`; `authorizePasskeyRegistrationVerify` is skipped only on that rejection.
- `WorkflowService:182, :192` and `:305, :318` — the guards at :161 and :292 throw
  `BadRequestException` when `expectedRevision` is absent; `lockAuthoringPrincipals` and
  `requireCurrentReferences` are skipped only on that rejection.
- `WorkspaceMailConfigService:107` — `clearStoredPassword = !isEnabled() || !isAuth()` is the
  intended semantics, and the alternative branch preserves the existing encrypted password.

### `java/uncontrolled-arithmetic` — #69, #70, #71

`SecretCipher:97`, `SecretStoreCrypto:201` and `AesGcm:57` are all `new byte[iv.length +
ciphertext.length]` on the **encrypt** path, where `iv = new byte[IV_BYTES]` is a compile-time
constant 12 bytes and `ciphertext` comes from `Cipher.doFinal`. Overflow would require a plaintext
near 2 GiB, which `doFinal` cannot produce without exhausting the heap first. CodeQL treats the
constant-length `iv.length` as uncontrolled.

### `java/tainted-arithmetic` — #72, #73, #74

- `MatchingService:352` and `:372` — `port = port * 10 + character - '0'` and the IPv4 octet
  equivalent, both guarded by a preceding length check (`> 5` and `> 3` respectively) and a
  `Character.isDigit` test, bounding the accumulator at 99,999 and 999.
- `ProviderCaptureReviewService:64` — `Math.multiplyExact(page - 1, size)` already throws on
  overflow, and `ProviderCaptureController` is `@Validated` with `@Min(1) int page` and
  `@Min(1) @Max(100) int size`, so the underflow the query posits is unreachable.

### `java/unreleased-lock` — #15, #16, #17

- `AiRestrictionEpoch:97` and `:141` — the write and read fences are **deliberately** handed off to
  `TransactionSynchronizationManager`, which releases them at transaction completion; the `finally`
  releases only when the hand-off did not happen. Restructuring for the query would break the
  commit-fence design the Javadoc documents.
- `HealthService:98` — every path is balanced: the lock is held exactly once on entry to the `try`
  (`tryLock` succeeded, or `lock()` was called when no snapshot existed) and released once in the
  `finally`; the early returns happen before acquisition or after a failed `tryLock`.

### `java/log-injection` — #18, #19, #20, #21

- `MailService:72` and `:78` — the only user-derived value logged is
  `ContactMask.maskEmail(message.to())`, which **fails closed to `***`** on any whitespace,
  including CR and LF, making forged log lines impossible.
- `ProviderConnectionService:120` and `:125` — the tainted value is `provider`, but both statements
  sit inside `completeCallback` after `requireEnabled` → `requireSupported`, which throws
  `ResourceNotFoundException` for anything outside the supported-provider allowlist.

### `js/xss-through-dom` — #4, #5

`NewCompanyDialog.tsx:495` and `:939` render `<img src={logoPreview} />`, where `logoPreview` is a
`blob:` URL from `URL.createObjectURL(file)` on a file the local user chose in their own file
picker. The full SARIF flow is `e.target.files?.[0]` → `URL.createObjectURL` → `<img src>`.

`<img>` is an image context: it never parses HTML, and SVG loaded through it has scripting disabled.
The URL is same-origin, generated locally by the browser, and cannot be set by another origin. The
file is additionally gated by `isManagedImageFile`, which requires the declared media type and the
leading magic bytes to identify the same supported raster format (JPEG, PNG or WebP), so an SVG or
HTML payload does not reach the preview at all.

`NewCompanyDialog` is the only picker flagged because it is the only one whose preview reaches a
bare `<img src>`: `NewContactDialog` renders through `next/image`, and `ProfilePanel` and
`QuickEditSheetShell` render through `ProtectedMediaImage`, indirection the query does not follow.
The difference is where the sink is, not how exposed the flow is — all four carry the same
locally-generated `blob:` URL.

### Test-only findings — #2, #3, #6, #7, #8, #9

| Alerts | Rule | Location |
| --- | --- | --- |
| #2, #3 | `js/insecure-randomness` | `frontend/test/e2e/global.setup.ts:100–101` |
| #6, #7, #8 | `js/log-injection` | `frontend/test/e2e/matrix/fault-proxy.mjs:73, 79, 87` |
| #9 | `js/insecure-temporary-file` | `frontend/test/e2e/matrix/support/matrix.ts:433` |

None of these files ship in a build artifact or execute in any deployment shape; they run only under
Playwright in CI and on developer machines. `Math.random` here seeds test fixture identifiers, not
credentials or tokens, and the fault proxy logs a request line it itself generated. Recorded rather
than silently ignored, per the last item of the disposition order in #1296.

## Review and closure

1. An independent reviewer reproduces the evidence for each false-positive section above.
2. That reviewer dismisses the corresponding CodeQL alerts as `false positive`, with a comment
   linking this document and stating the 2027-02-14 expiry.
3. The alerts are reopened and reassessed at expiry, when a cited data flow changes, or when the
   relevant query is materially updated.
4. [#1296](https://github.com/itkla/connex/issues/1296) closes once steps 1–2 are complete; the
   remediated entries closed with the pull request that introduced this document.
