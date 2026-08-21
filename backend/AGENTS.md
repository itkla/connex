# Backend — Agent Guide

The root `../AGENTS.md` applies here. This file contains backend-wide rules only. Detailed subsystem, transaction, migration, storage, and deployment contracts are loaded on demand through **Task routing**.

## Stack and structure

Spring Boot 4 · Java 26 · MyBatis · Flyway/MySQL · Spring Security/WebAuthn · Lombok · JUnit 5. Package root: `ooo.klae.connex.backend`.

Primary layers:

- `controllers/` — HTTP boundary: validation, DTO mapping, authorization entry points, service delegation.
- `services/` — business logic and transaction orchestration.
- `mappers/` + `resources/mappers/` — all database access and SQL.
- `dto/`, `beans/`, `tenant/`, `config/`, `exceptions/` and feature packages support those layers.
- Flyway migrations live under `src/main/resources/db/migration/`.

## Architecture and core invariants

**controller → service → mapper.** Keep the boundary strict.

- Controllers do not contain business logic or direct data access.
- Services own business rules and transaction orchestration.
- Mappers own SQL. Use parameterized MyBatis bindings (`#{}`); do not introduce string-substitution SQL for user-controlled data.
- Every tenant-owned read/write is scoped from the resolved server-side workspace context. Never trust a tenant/workspace id supplied by the request as authorization.
- Every endpoint is RBAC-enforced using the established permission pattern for that domain.
- Tenant/routing guards fail closed. Do not disable or bypass them to make a test or feature work.
- Validate request DTOs at the boundary with Bean Validation where applicable.
- Never log secrets, tokens, session identifiers, WebAuthn material, provider credentials, personal contact data, uploaded content, or other PII.

Before touching tenancy, routing, lifecycle, catalog/control planes, or organization-wide data access, read `../docs/MULTITENANCY_PLAN.md` and the relevant architecture tests.

## Task routing

Read the relevant contract before editing that subsystem:

| Work | Required reading |
|---|---|
| Security/auth/provider/upload boundary | `../docs/backend/SECURITY_BOUNDARIES.md` |
| AI, masking, assistant, model-provider egress | `../docs/backend/AI_SECURITY.md` |
| Business-card upload/OCR/fallback/import | `../docs/backend/BUSINESS_CARD_SCANNING.md`, `../ocr/AGENTS.md` |
| Automation, segments, rules, workflows | `../docs/backend/AUTOMATION.md` |
| Duplicate review, CSV, interaction-history import | `../docs/backend/IMPORTS_AND_DUPLICATE_REVIEW.md` |
| Tenancy, routing, lifecycle, data planes | `../docs/MULTITENANCY_PLAN.md` |
| Transactions, concurrency, lock order | `../docs/backend/LOCKING.md` |
| Flyway/schema/table placement | `../docs/backend/MIGRATIONS.md` |
| Object storage/uploads/deletion queue | `../docs/backend/OBJECT_STORAGE.md` |
| Connected provider capture | `../docs/CONNECTED_CAPTURE.md` |
| Commercial-document signatures | `../docs/ESIGNATURE.md` |
| Volume seeder/startup contract | `../docs/VOLUME_SEEDER.md` |
| Deployment, startup posture, maintenance modes | `../docs/DEPLOYMENT.md` |
| Vulnerability handling | `../docs/VULNERABILITY_MANAGEMENT.md` |

Inspect the owning package, nearest implementation, and tests in addition to the contract. Do not copy a subsystem's full protocol back into this guide.

## Security boundaries

- Auth/WebAuthn, CSRF, session rotation, tenant routing, RBAC/sharing, provider egress, secrets/crypto, and destructive data movement are high-risk work. Preserve fail-closed behavior and require the root security-focused review.
- Authentication methods establish sessions through the existing `AuthService` path; do not expose principal IDs or servlet session IDs merely for client correlation.
- Provider and network egress must use the established bounded/fixed-host/address-validation adapters for that subsystem. Do not add arbitrary redirects, remote URL fetching, or network I/O inside database transactions.
- Upload/image paths must preserve the existing bounded decode/admission and metadata-stripping boundaries. Never add unbounded multipart/image processing or log recognized/uploaded content.
- Idempotency, one-use proofs, generation handles, leases, and ownership checks are data-integrity/security mechanisms. Do not simplify them without reading the owning contract and tests.
- New tables holding workspace/org data must participate in the appropriate tenant/control lifecycle, export, teardown, and residual-verification registries. `../docs/MULTITENANCY_PLAN.md` is authoritative.

## Transactions and locking

Connex has deliberate global lock-order contracts across workflows, membership/offboarding, lifecycle/APPI/SSO, tasks, AI chat, duplicate review/imports, object storage, and other concurrent aggregates.

- **Do not invent a lock order locally.** Before adding/changing `FOR UPDATE`, transaction isolation, multi-aggregate writes, lifecycle writes, or retry/lease behavior, read `../docs/backend/LOCKING.md` and the neighboring implementation/tests.
- Discover lock keys without locks where the contract requires it, sort deterministic key sets in Java where required, acquire roots in the documented order, and revalidate locked state before writes.
- Do not assume SQL `ORDER BY ... FOR UPDATE` is a substitute for individually ordered lock acquisition where the contract requires exact locks.
- Do not perform provider/network I/O while holding database locks unless an authoritative subsystem contract explicitly requires and bounds it.
- A change to lock ordering or transaction isolation is high-risk and requires correctness/concurrency review in addition to any security review.

## Data, migrations, and MyBatis

All schema changes use Flyway. Read `../docs/backend/MIGRATIONS.md` before adding or changing a migration.

Minimum rules:

- Never edit or renumber an applied migration.
- Keep the global version sequence valid across migration folders and rebase/renumber unmerged migrations when `main` advances.
- Place tenant/org-data and control-plane migrations in the correct lineage; no foreign key crosses the plane wall.
- New tenant-owned tables are workspace-scoped from creation and enrolled in lifecycle/processing registries as required.
- Composite foreign-key string columns must use compatible explicit charset/collation as documented.
- Mapper interfaces live under `mappers/`; XML lives under `src/main/resources/mappers/`. Keep SQL out of services/controllers.

Architecture tests are enforcement, not obstacles. If a migration or mapper change requires weakening a tenancy/RBAC/plane test, stop and reconsider the design.

## Code conventions

- Java null/type safety is explicit. Validate inputs, guard nullable values, and avoid unchecked casts or silent fallthrough.
- Use Lombok consistently with neighboring code rather than hand-writing generated boilerplate.
- Use domain exceptions from `exceptions/`; let `GlobalExceptionHandler` map them. Do not build ad-hoc HTTP error bodies or catch-and-swallow failures.
- Prefer self-explanatory code and Javadoc for public contracts. Inline comments are reserved for non-obvious safety, lock-order, protocol, compatibility, or operational reasoning and should explain why the constraint exists.
- Mirror the nearest controller/service/mapper and tests before introducing a new pattern.

## Verification

Use targeted local verification. The required `Backend — build & test` CI job owns the exhaustive backend corpus.

For a material backend change:

1. Start MySQL and run the backend with the `dev` profile when runtime verification is needed.
2. If a `*Controller` changed, exercise each materially changed endpoint with real HTTP requests. Verify status/body plus authentication, RBAC, and tenant behavior; include unauthorized/other-tenant cases when relevant.
3. Add/update automated tests. Run every changed/added test class plus directly implicated architecture, tenancy, routing, migration, or security guard tests using Gradle `--tests` selectors.
4. Do **not** run bare `./gradlew test` or `./gradlew build` on the shared development host merely for extra confidence. Use the full suite locally only to reproduce an actual full-suite CI failure that cannot be isolated, when acceptance criteria explicitly require it, or when the user explicitly directs it.
5. Inspect the exact diff for tenant leakage, RBAC gaps, null/edge cases, N+1 behavior, transaction boundaries, migration safety, and failure recovery.
6. Material changes get independent adversarial review. High-risk security/tenant work gets security review; concurrency/locking/migration work gets a separate correctness-focused review when the risk warrants it.

Do not investigate a slow shared development host unless the active task is about that host. A slow test without a code failure is not evidence of a product defect.

## Commands

Local database credentials belong in untracked `backend/.env` derived from `.env.example`.

- Dev server: `SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun`
- Targeted test: `./gradlew test --tests '<fully.qualified.TestClass>'`
- Compile/package without exhaustive tests: `./gradlew assemble testClasses`
- Local DB: `docker compose up -d db`
- Optional OCR sidecar: `docker compose --profile ocr up -d ocr`
- Full backend suite: CI-owned through `Backend — build & test`

The `dev` profile is for local HTTP/database posture only. Production and staging exceptions, TLS requirements, audit-integrity configuration, seeder/maintenance posture, and deployment sequencing belong to the linked runbooks, not this always-loaded guide.
