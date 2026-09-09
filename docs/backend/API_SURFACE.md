# Published API inventory and retirement

Owner: Backend maintainers. Security review: security maintainers. Control: CHK-054 / SEC-60.

## Generated evidence

[`api-surface.tsv`](api-surface.tsv) is the approved source-derived HTTP surface, including
conditional controllers, implicit HEAD and OPTIONS representations, filter-served authentication
routes, and the WebSocket HTTP handshake. `/api/v1` is the opt-in bearer API; unversioned `/api`
is the browser/session contract. Unversioned does not mean deprecated. Next.js forwards `/api/**`
and `/saml2/**` to the backend; it is not an API gateway or an independent retirement control.
There are currently no Next.js `route.ts`/`route.js` handlers in the frontend.

`ApiSurfaceInventory` uses compiled Spring merged mapping annotations (including aliases), scans
main-source controllers across backend packages without evaluating feature flags, and uses the JDK
Java parser to inspect controller method bodies. It records direct service `@RequirePermission`
and explicit controller `Permission` references. `domain authorization` identifies a delegated
method whose checks must be inspected; it does **not** assert that the method is unprotected or
that all transitive authorization is statically proved. Overloaded service methods are conservatively
combined. For inferred OPTIONS, the controller and authorization-evidence columns identify the
originating mapping; Spring answers OPTIONS without invoking that controller/service method.
Mapping conditions include content negotiation and conditional-property activation.

The tenant column describes routing evidence, not exhaustive effective business scope: credential workspace, MVC session
workspace resolution, or domain/handler-owned scope. MVC eligibility is not proof that an operation
requires a selected workspace: account/organization operations and anonymous bearer flows can resolve
scope in their services. `WebConfig`, `TenantResolutionInterceptor`, the referenced service, and
existing tenant/RBAC architecture tests remain authoritative for that distinction.

[`api-surface-policy.txt`](api-surface-policy.txt) records ordered application matchers and hashes
of controller, service, filter, security/tenant/configuration sources, dependency/build configuration, application settings and the
Next.js proxy. These hashes deliberately require review even when a URL stays unchanged: removing
an imperative permission check or adding a filter must not silently preserve the approval evidence.
They are change detectors, not proofs of effective authorization. Review the source diff before
regenerating. Filter-provided paths use the explicit configuration setters; the SAML request path
is Spring's default beneath the explicitly permitted `/saml2/**` namespace.

Framework OAuth and SAML path matchers accept all seven default-firewall HTTP methods;
the ledger records each method even when CSRF or protocol validation rejects a particular
request. SAML initiation supports both the registration-ID path and its query-parameter
variant. These mappings follow the installed Spring Security 7.1 implementation and must be
reviewed on upgrades; configured route setters and dependency declarations are guarded.

From `backend/`, with the lane environment loaded on a shared development host:

```bash
flock /home/dev/worktrees/gradle.lock bash gradlew generateApiSurface
flock /home/dev/worktrees/gradle.lock bash gradlew test \
  --tests 'ooo.klae.connex.backend.architecture.ApiSurfaceInventoryArchTest' \
  --tests 'ooo.klae.connex.backend.integration.ApiSurfaceAnonymousSecurityTest'
```

CI's ordinary backend test task runs these guards. Regeneration is never part of an assertion:
added, removed or changed rows fail exact comparison until a reviewed ledger update is committed.
Ledger/source files and the UTC lifecycle date are explicit Gradle test inputs, so edits or a new
EOL day cannot hide behind an up-to-date test task. The generator and architecture comparison
require no application context or database. The anonymous
boundary test loads the application and connects to the configured test database.

The anonymous test takes expected posture from the **committed** ledger, sends requests through the
application's Spring Security chain and enabled servlet filter registrations discovered through
Boot's `ServletContextInitializerBeans`, including automatically adapted plain Filter beans and delegated
Spring Session filters resolved to their actual target beans in registration order, and requires a sentinel
204 for every anonymous controller mapping. Protected mappings must return 401/403. A real filter-issued CSRF token/session
and syntactically valid document-grant cookie isolate perimeter authorization from intentionally
independent CSRF and grant-shape admission. No business controller is invoked and the synthetic grant
does not authorize document data. CORS preflight is independently handled by the configured CORS policy; the ledger posture describes
non-CORS requests. This is not an end-to-end token, tenant-interceptor, provider or
SSO ceremony test. `/api/v1` and framework protocol routes retain their dedicated tests and are not
counted by this sentinel test. No alternate port, proxy deployment, or production traffic is tested.

## Lifecycle

The endpoint's domain maintainer owns compatibility and migration; Backend maintainers are the
accountable default owner until a named domain owner is recorded. Security maintainers review
anonymous access and removal evidence. Release operators deploy and verify retirement across all
replicas, SaaS and supported on-prem releases. Ownership here assigns a role, not a claim that an
individual has accepted a new operational responsibility.

1. **Active.** Each mapping is in the generated inventory. EOL is `not scheduled`; usage is
   `not measured` until evidence is attached to tracked work. Do not infer unused status from no
   frontend call, no source reference, or a feature flag's default. CLI, integrations and older
   supported clients can call these endpoints.
2. **Deprecation decision.** Open or update tracked work with the exact method/path, accountable
   owner, affected versions/deployments, migration target, UTC EOL date, usage evidence and client
   notification plan. Record aggregate counts by normalized route template and method over an
   owner-approved representative window; never record tokens, raw paths, request bodies or PII.
   This repository does not yet establish production usage telemetry for every endpoint.
3. **Mark.** Apply Java `@Deprecated(since = "YYYY-MM-DD", forRemoval = true)` to the mapped
   method (or controller when all routes retire). Add each generated representation, including
   implicit HEAD for GET, to [`api-lifecycle.tsv`](api-lifecycle.tsv) with state `deprecated`, owner, EOL,
   migration target and issue. Synthetic OPTIONS is shared by all methods at a path and is not
   owned by an individual retired method; it disappears only when the entire path is removed. Regenerate the inventory. Publish migration instructions and release
   notes before removing a supported contract. Deprecation annotations and response headers alone
   do not disable HTTP access.
4. **Retire before EOL.** Remove the handler mapping and its obsolete filter/proxy aliases. For a
   version namespace, remove its controllers and public scope rules and retain a deny-all fallback;
   a disabled feature flag alone is not permanent retirement. Set lifecycle rows to `retired` and
   retain them as tombstones. The guard rejects expired mapped endpoints, missing metadata, stale
   deprecation rows, and retired mappings with the same HTTP method and normalized path template.
   Lifecycle identity ignores URI-variable names (including catch-all variable names), but preserves
   regex constraint text, catch-all markers, wildcards and literal path text exactly. For example,
   `{id}` and `{itemId}` are identical for lifecycle checks; `{id:[0-9]+}` and `{id}` are distinct.
   This is equality checking, not proof that route matching languages do not overlap: broader
   wildcards, changed regex constraints, aliases and equivalent regex spellings can escape this check.
   A CI date check does not switch off an already-running deployment: operators must deploy the
   removal before EOL.

   **Overlap review policy.** Every added or changed mapping must be compared against same-method
   retirement tombstones by the domain maintainer and a security reviewer before inventory approval.
   In particular, `/api/items/**` or `/api/items/{*rest}` can cover a retired `/api/items/{id}`;
   changed constraints and literal aliases also need explicit overlap assessment. Record the affected
   tombstones, overlap rationale, and formerly valid URL cases in the tracked work. An overlapping
   replacement must exclude or explicitly deny retired requests without legacy behavior or side
   effects, with authenticated and anonymous boundary tests proving that behavior. Retain tombstones;
   regeneration alone does not approve reactivation. If overlap cannot be ruled out or safely denied,
   block approval until an explicit owner and security retirement decision resolves it.
5. **Prove removal.** Test the old paths with a valid formerly authorized credential/session as
   well as anonymously, with their former methods, through both the direct backend and deployed
   proxy. Require 404/410 or an explicit deny with no legacy response or side effect; anonymous
   401 alone is insufficient evidence of removal. Check every replica and supported configuration,
   and verify the migration target still works. Attach command output, release revision, deployment
   identity and aggregate usage evidence to the tracked work. Rollbacks must not reactivate retired
   mappings; a deliberate EOL extension needs a new reviewed decision before expiry.

No endpoints are presently annotated `@Deprecated`, so the lifecycle ledger has no deprecation or
retirement rows. It is not evidence of a historic shutdown. Compatibility candidates below must go
through the decision step before dates or removals are invented.

## Current source audit (2026-09-08)

| Surface | Finding and disposition |
| --- | --- |
| `/api/v1/me` | Current v1 bearer identity API. Opt-in security chain; disabled fallback denies `/api/v1/**`. No v0/v2 or other older numbered API mappings found. |
| `/api/rules` and children | Explicit legacy compatibility projection. Frontend `app/lib/api.ts` retains wrappers for these paths, but a symbol search found no consumers in `frontend/app` outside that module. This is evidence of a superseded browser surface, not proof of zero older-client or integration traffic. `RuleService` requires `RULE_MANAGE`; mutation ownership prevents changes to canonical-owned workflows. Retained intentionally; candidate for migration to `/api/workflows`, not proven unused. No approved EOL found. |
| POST `/api/persons`, `/api/companies`, `/api/deals` | `LegacyRecordCreationController` is conditional on `connex.record-creation.guided-cutover-enabled=false` (including missing value). The configured default is false and current create containers/browser actions still call the old POST routes. Guided creation is the migration direction. These routes remain permission-gated and intentionally available for cutover compatibility; do not disable without a client/cutover decision. |
| Workflow legacy resolution/runtime/history | Explicit compatibility and rollback/read-history behavior. Presence of `legacy` in a path is not a deprecation decision. |
| Activity/deal legacy timezone query inputs | Compatibility parameters on current routes, not a second API version. Retirement would require a request-contract change. |
| GET health/readiness, version, capabilities, managed-mail status | Intentionally public operational/build/capability metadata. These disclose deployment characteristics by design; no secrets or tenant records were identified in the reviewed responses. Metrics uses separate scrape authority and is not `permitAll`. |
| `/api/auth/**` | Broad anonymous matcher, preceded by authenticated `/me` GET/HEAD and WebAuthn rules. Registration/login, CSRF bootstrap, recovery and email-link flows are intentional pre-session operations. Newly mapped children now alter the ledger. CSRF, rate limits, one-time grants and service validation remain independent. |
| Invite and invite-link exchange | Intentional pre-session bearer exchange; subsequent grants and membership decisions are service-owned. |
| Native prepare/complete | Intentional bearer-grade native connection handoff; not session-authenticated just because it is under `/api/account`. |
| Delivery unsubscribe and document acceptance | Intentional bearer exchange and purpose-bound browser grants. `permitAll` is not unrestricted tenant-data access. Document admission checks grant shape before body parsing; services validate the actual grant. |
| Delivery/signature webhooks | Intentional callback entry points. Delivery resolves the opaque token and verifies provider signatures; signature service authenticates provider events before tenant routing. |
| POST `/api/csp-reports` | Intentional stateless, bounded collector on its own ordered chain. Other methods require authentication. |
| OAuth/SAML and WebSocket | Framework-owned authentication handshakes and authenticated WebSocket upgrade, included separately from controller handlers. |

**Finding fixed: `HEAD /api/auth/me` crossed the anonymous perimeter.** Spring supplies HEAD for the
GET profile mapping, but the existing authenticated matcher covered GET only; HEAD fell through to
`/api/auth/**.permitAll()`. An explicit HEAD authenticated matcher now closes that gap. The existing
`AuthService.getCurrentPrincipal()` rejected anonymous principals before reading user data, so this
is an authorization-perimeter defect with a surviving service backstop, not demonstrated profile
disclosure. The dedicated HEAD test requires anonymous HEAD `/api/auth/me` to return 401 and a permitted
HEAD control to reach the sentinel through the same security chain. This expectation is independent
of ledger approval.

No other unintentionally exposed endpoint was identified. This is a source/perimeter assessment, not a
claim of a complete penetration test or proof of production usage. Public metadata is a documented
choice; compatibility endpoints have no evidence supporting immediate removal. Production deployment
revision, per-route usage, owner-approved compatibility EOL dates, and historical shutdown evidence
were not available in this lane.
