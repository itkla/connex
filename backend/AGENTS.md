# Backend — Agent Guide

The root [`/AGENTS.md`](../AGENTS.md) applies here in full. This file adds backend-specific rules. The Golden Rules — Explore→Plan→Question→Act, match existing patterns, docs-only comments, strict null/type safety, layered architecture, mandatory review — are not optional here.

## Stack

Spring Boot 4 on **Java 26** · MyBatis · Flyway + MySQL · Spring Security with **WebAuthn** · Lombok · JUnit 5. Multi-tenant, RBAC-enforced. Package root: `ooo.klae.connex.backend`.

Layout: `controllers/` · `services/` · `mappers/` (+ XML in `resources/mappers/`) · `dto/` · `beans/` · `tenant/` · `notifications/` · `config/` · `exceptions/`. Migrations in `resources/db/migration/`.

## Architecture — keep the layers clean

**controller → service → mapper.** Strictly.

- Controllers: HTTP only — validation, mapping DTOs, delegating to services. No business logic.
- Services: business logic and transactions. The only layer that calls mappers.
- Mappers (MyBatis): all data access. No SQL anywhere else.
- Cross-cutting tenancy and RBAC are load-bearing — every query must be tenant-scoped, every endpoint RBAC-checked. There are arch tests guarding this (`architecture/RbacEnforcementArchTest`, `tenant/TenantScopeInterceptorTest`); keep them green.

## Tenant isolation & RBAC — the core invariant

This is the most load-bearing property of Connex. A change that leaks data across tenants or bypasses RBAC is a critical defect, full stop. The detailed model lives in [`../MULTITENANCY_PLAN.md`](../MULTITENANCY_PLAN.md) — read it before touching tenancy.

- **Every query is tenant-scoped.** Reads and writes must be constrained to the resolved workspace/tenant. Most tables carry a workspace column (see the `*_workspace` migrations) — filter on it in the mapper, every time. Never trust a tenant id from the request body; use the resolved tenant context.
- **Every endpoint is RBAC-checked.** No controller method is reachable without the appropriate role/permission check. Mirror how the neighboring controller for the same entity authorizes.
- **Don't disable the guards.** The tenant-context enforcement and `TenantScopeInterceptor` exist to fail closed when no tenant is resolved. Don't switch them off to "make it work."
- **Keep the arch tests green:** `architecture/RbacEnforcementArchTest` and `tenant/TenantScopeInterceptorTest`. If a change requires touching them, that's a signal to stop and re-think, not to edit the test.
- Sharing/permissions changes get extra scrutiny and a `/security-review`.

## Security & secrets

- **Never log secrets or PII** — no passwords, tokens, session ids, WebAuthn material, or personal contact data in logs or error messages.
- **Never commit credentials.** Database passwords live in local env/config, not source control. For local Docker MySQL, create `backend/.env` from `backend/.env.example`, fill throwaway local passwords, and keep the file untracked.
- **Validate all input** at the boundary (`@Valid` + Bean Validation on DTOs). Treat request data as hostile.
- **Auth/WebAuthn** flows are sensitive: don't weaken session handling, CSRF, or credential verification to simplify a change.
- Prefer parameterized MyBatis (`#{}`) over string substitution (`${}`) to avoid SQL injection.

## Data, migrations & MyBatis

- **Schema changes go through Flyway.** Add a new migration in `src/main/resources/db/migration` named `V{next}__{snake_case_description}.sql`, using the next sequential number after the highest existing `V`. Never edit or renumber an applied migration. Make migrations forward-only and mind MySQL specifics.
- New tenant-scoped tables need their workspace column from day one — follow the `*_workspace` migrations as the pattern.
- **MyBatis mappers:** Java interface in `mappers/`, XML in `src/main/resources/mappers/{Entity}Mapper.xml`. Mirror an existing mapper pair. All data access lives here — no SQL elsewhere. Use `#{}` bindings.

## Conventions

- **Comments:** Javadoc on classes/methods only. **No inline comments.**
- **Null/type safety:** validate DTOs (`@Valid`, Bean Validation); guard nulls; no unchecked casts. Prefer explicit handling over silent fallthrough.
- **Use Lombok** as the existing code does; don't hand-write boilerplate it generates.
- **Error handling:** throw the domain exceptions in `exceptions/` (`BadRequestException`, `ForbiddenException`, `ResourceNotFoundException`, `DuplicateResourceException`); `GlobalExceptionHandler` maps them to HTTP responses. Don't build ad-hoc error bodies or catch-and-swallow.
- Mirror the existing controller/service/mapper for the entity you're touching before adding new patterns.

## Smart segments & the condition model

Smart-segment evaluation lives in `SegmentService` as a reusable **condition model**: a `SegmentDefinition` (`match` = `all`/`any` over `SegmentCondition`s), where each condition is a graph-aware **predicate** (`warm_intro_available`, `open_deal`, `cooling`, `no_activity`) or a **field** comparison (`industry`/`name`/`tag`), optionally negated — evaluated to workspace + current-user-scoped record ids via `SegmentMapper`. This model is deliberately feature-agnostic: the planned **rule engine (#54)** should consume it as its `WHEN` rather than inventing a new condition language (segments are state-matching; rules add the transition/trigger + action layer on top). Add new predicates/fields here — extend the catalog allow-list and the `SegmentMapper` queries, keeping every statement `#{}`-bound and workspace-scoped.

## Rule engine

Automation rules (`RuleService` CRUD + `RuleEngineService` execution, issue #54) layer a trigger + actions on top of the segment condition model. Conventions:

- A rule = **trigger** (`entity_change` after-commit events, or a `schedule` cadence) + optional **WHEN** (a `SegmentDefinition`, company-only today) + **THEN** actions. Trigger/condition/actions are JSON columns, validated per type in `RuleService`.
- **Execution identity.** A rule runs as the run-as member (`user` mode) or the global system actor (`system` mode, gated to admin via `requireRole(ADMIN)` at authoring). `SystemActor` is a seeded non-login user (migration `V20`) whose permissions are a fixed narrow set granted in `WorkspaceService.permissionsFor` — extend that set if you add an action needing a new permission. Off-thread execution installs SecurityContext + TenantContext via `AutomationExecutor`, so the existing tenant- and RBAC-enforcing services apply unchanged; an actor lacking a permission fails the action.
- **Adding a trigger event.** Emit it after the mutation's write via `ruleTriggers.publish(workspaceId, recordType, entityId, "<entity>.<event>")` (alongside any `notificationChanges.publish`), and add the token to the rule's validated vocabulary. The `@TransactionalEventListener(AFTER_COMMIT)` + `@Async` listener runs the engine off-thread on committed state, mirroring the notification subsystem.
- **Adding an action.** Add the type to `RuleService` validation and a branch in `RuleActionExecutor` (delegating to the existing service), and grant any new permission to `SystemActor` if `system` mode must perform it.
- **Idempotency.** Every fire is keyed in `rule_execution` (unique `dedupe_key` per rule); time-based fires bucket by cadence. The engine never throws to its caller.
- The engine runs **off the request thread** — pass `workspaceId`/`userId` explicitly to mappers and to the session-free `SegmentService.evaluate(workspaceId, userId, …)`; never rely on the request's tenant context there.

## Definition of Done (backend) — the verify loop is required

1. **Run a test server:** `./gradlew bootRun` (MySQL via `docker-compose up -d db`, see `docker-compose.yml` — db on `:3306`, adminer on `:9001`).
2. **Curl every changed `*Controller` endpoint** at `http://localhost:8080/api/...`. Confirm status, body, and — critically — auth, tenant isolation, and RBAC behavior with real requests. Protected endpoints need a session: `POST /api/auth/login` to obtain the `JSESSIONID` cookie, `GET /api/auth/csrf` for a CSRF token, then send both on mutating calls. Exercise an unauthorized and an other-tenant caller too — prove the request is *rejected*, not just that the happy path works.
3. **Write automated tests and make them pass:** `./gradlew test`. Cover services and mappers for new behavior; keep arch tests green.
4. **Scrutinize intensely** for bugs and future failure modes: tenant leakage, RBAC gaps, null/edge cases, N+1 queries, transaction boundaries, migration safety and reversibility.
5. **Independent codex review (required).** Use the **codex** CLI to spawn a **gpt-5.6** agent at **xhigh** reasoning effort to independently check the work for security bugs, logic flaws, and anything that could compromise the app. Run from `backend/`:

   ```bash
   codex exec -m gpt-5.6 -c model_reasoning_effort=xhigh --sandbox read-only \
     "Independently review the backend changes on this branch (git diff against main). Hunt for security vulnerabilities, logic flaws, tenant-isolation and RBAC gaps, injection, auth/WebAuthn weaknesses, and anything that could compromise the app. Report findings with file:line and severity. Do not edit files."
   ```

   Treat its findings as review input — triage and address them before handing back.
6. **`/code-review` + adversarial multi-agent review.** Auth / WebAuthn / tenant / RBAC / sharing changes also get **`/security-review`**.

## Commands

- Run (local): load `CONNEX_DB_*` from your untracked `backend/.env`, then run `SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun` — the `dev` profile (`application-dev.yml`) turns the session and workspace cookie `Secure` flags off so login works over plain-HTTP `localhost`, permits local plaintext DB transport, and supplies a local-only audit-integrity HMAC secret. Without it the fail-closed default (`Secure=true`) drops `JSESSIONID` over HTTP and non-dev startup requires `CONNEX_AUDIT_INTEGRITY_HMAC_SECRET` plus a `CONNEX_DB_URL` with `sslMode=VERIFY_CA` or `sslMode=VERIFY_IDENTITY`. The systemd-controlled local staging checkout at `/opt/connex-staging/backend` is special-cased for `localhost:3001` auth defaults and explicit loopback MySQL with `sslMode=DISABLED`; do not copy that shape to production.
- Test: load `CONNEX_DB_*` from your untracked `backend/.env`, then run `./gradlew test`
- Build: `./gradlew build`
- DB up: create `backend/.env` from `backend/.env.example`, fill local-only passwords, then run `docker compose up -d db` (from `backend/`)
