# Connex Multitenancy Implementation Plan

> Status: design / not yet started. Produced from a full-codebase audit (schema, all 14 MyBatis
> mappers, every controller/service, auth, frontend, background jobs, search/analytics, tests).
> Every claim below is cited to `file:line`. The canonical "correct" patterns already in the repo —
> `DealMapper.xml`, `TaskService`, `NotificationScheduler`, and the `deal`/`deal_collaborator`
> composite-key schema — are the templates this plan generalizes to every entity.

---

## 0. Locked decisions, design deltas & issue map (2026-06-23)

Terminology: keep the existing schema's **`workspace` / `workspace_id`** (conceptually == the issues'
`org_id` / "organization"). GitHub issue map: **Phase 0 → #88** (stop-the-bleeding) + #67; **Phase 1 →
#89 / #61** (tenant isolation); **Phase 2 → #90** + #11 (authorization/roles); **audit → #91**;
self-registration → #81; hardening grab-bag → #67/#68/#69/#71/#72/#73/#76/#79/#80.

| # | Decision | Implication |
|---|---|---|
| 1 | **Pre-launch** — no live data to preserve | Schema changes ship as **Flyway** migrations on empty tables — *no nullable-add → backfill → NOT-NULL dance*. Big simplification. (#88) |
| 2 | **Shared address book** — a contact/company can be shared across workspaces | `person`/`company` are **owned-but-shareable** (see §0.1). Forces **plain FKs, not composite**, from private→shareable entities |
| 3 | One shared `app_user`, member of many workspaces | switcher model; `app_user`/`workspace_member` are the control-plane identity tables (no `workspace_id`) |
| 4 | Legacy rows → `default` workspace | mostly moot pre-launch |
| 5 | **Per-tenant tags** | `UNIQUE(workspace_id, name)`; `person_tag` etc. carry the **tagging** workspace's id |
| 6 | **Per-tenant pipelines, optionally shareable** | pipeline/stage = owned-but-shareable (same model as §0.1) |
| 7 | No hard uniqueness on person.email / company.name | index only |
| 8 | Session attr + re-validated `X-Workspace-Id` header | no URL segment |
| 9 | Registration **creates+owns** a workspace **and** invite-join, **with an instance setting to disable self-service creation** | `allow_public_workspace_creation` flag (config-backed); relates to #81 |
| 10 | `owner`/`admin`/`member` built-in **+ owner-defined custom roles with chosen privileges** | full RBAC (`workspace_role` + `permission` catalog + `workspace_role_permission`); built-ins first, custom roles a dedicated later phase (§0.2) |
| 11 | Both invite flows (email-token **and** admin-adds-existing) | new `WorkspaceController` endpoints |
| 12 | Member removal → owner/assignee `SET NULL`, authored history preserved | (matches plan §2 user-ref hardening) |
| 13 | Notification prefs per-user-global; **inbox alerts across ALL the user's workspaces** | notification queries are recipient-scoped across memberships, each row **labeled with its workspace** — NOT filtered to the active workspace (§0.3) |
| 14 | Per-tenant audit log for workspace admins **+ a super-admin/owner combined cross-workspace view behind a filter** | `audit_log.workspace_id` + a gated combined query |
| 15 | Structural enforcement of owner/collaborator membership | composite FK → `workspace_member` (works: owner is in the deal's own workspace) |
| 16 | Orphan rows → `default` | moot pre-launch |
| 17 | **Flyway** | adopt in Phase 0; `schema.sql` retired as the boot mechanism |
| 18 | Phased, flag-gated rollout | per-phase branches/PRs |
| 19 | **Testcontainers MySQL** | Docker confirmed available; tenant-isolation suite runs on a clean per-run DB |
| 20 | **Everything in scope** — blob/URL auth (#68/#69), export scoping (#57), Next fetch-cache keys, CSRF (#67), authorship spoofing (#71/#72), unbounded page size (#73), LIKE escaping (#76) | folded into the relevant phases |

### 0.1 Sharing model — companies, contacts, pipelines (answers #2, #6)

These three (+ `stage` via pipeline) are **workspace-owned but shareable**, not pure single-owner:

- Each row carries `workspace_id` = the **owning** workspace; by default visible only to the owner.
- An owner shares a record via additive junctions `company_share` / `person_share` / `pipeline_share`
  `(<e>_id, workspace_id, granted_by, can_edit, created_at)`. Visibility becomes
  `e.workspace_id = :ws OR EXISTS(SELECT 1 FROM <e>_share s WHERE s.<e>_id = e.id AND s.workspace_id = :ws)`.
- **FK consequence (load-bearing):** a *private* record (deal/task/activity/note) in workspace **B** may
  legitimately reference a *shareable* record (person/company/pipeline) owned by **A** and shared to B —
  their `workspace_id`s differ — so references from private→shareable entities use a **plain FK to the
  parent id + a service-level visibility check (owned-or-shared) + the fail-closed interceptor**, NOT a
  composite `(workspace_id, parent_id)` FK. Composite FKs remain only **among same-workspace private
  entities** (activity→deal, task→deal, deal_collaborator→deal/workspace_member, the `deal_*` junctions).
- **Per-workspace overlay:** workspace-scoped data *about* a shared record (tags, activities, notes,
  tasks) carries the **viewing** workspace's id — so A's tags/notes on a shared contact are invisible to B.
- **Staging:** ownership ships first (visibility = owned-only); share junctions + share-aware visibility
  are an additive sub-phase. The schema is written share-compatible from day one (plain FKs to shareable
  entities), so enabling sharing needs no schema rework.

> This is the one interpretation that most departs from the original §2 (which assumed single-owner +
> composite FKs everywhere). The §2/§3 composite-FK guidance now applies **only to private↔private
> references**; private→shareable references are plain-FK + checked.

### 0.2 RBAC — custom roles (answer #10)

Built-in `owner`/`admin`/`member` ship in Phase 2 (#90). A later dedicated phase adds owner-defined
custom roles: `workspace_role(id, workspace_id, name, …)`, a fixed `permission` catalog enum, and
`workspace_role_permission(role_id, permission)`. `workspace_member.role` migrates from a free VARCHAR to
reference a role (built-in or custom). Independent of tenant isolation — sequenced after Phase 2.

### 0.3 Cross-workspace notifications (answer #13)

The inbox/bell is **recipient-scoped across every workspace the user belongs to**, not the active one, so
nothing is missed while viewing another workspace; each notification is labeled with its source
workspace. Delivery prefs stay per-user-global. (The reminder *generation* jobs still run per-workspace.)

### 0.4 Data-layer enforcement is primary (issue #89)

Per #89, scoping is enforced **in the data layer via a mandatory MyBatis interceptor**, not by trusting
hand-written `WHERE` clauses. §4.2 is revised: the interceptor is the **primary, fail-closed** mechanism
(asserts/【injects】 a workspace predicate on every statement tagged tenant-scoped; throws if the
`TenantContext` is unresolved); explicit `@Param` predicates are used only where the interceptor cannot
reason (multi-table joins, aggregates, the polymorphic attachment query). For **shareable** entities the
interceptor enforces the owned-or-shared visibility predicate.

> **✅ Implemented (2026-06-24):** `TenantScopeInterceptor` (registered via `MyBatisConfig`). It does not
> rewrite SQL; it throws `ForbiddenException` when a workspace-scoped statement runs on a request thread
> with an unresolved `TenantContext`, and stays out of the way of background jobs (off the request thread)
> and the nullable-workspace audit insert. Toggle with `connex.tenancy.enforce-scope`.

---

## 1. Diagnosis

Multitenancy is **half-built**. The tenant boundary exists (`workspace`, `workspace_member` —
`schema.sql:60,72`) and the *newer* subsystems were designed tenant-aware:

- `deal`, `task`, `notification`, `deal_collaborator` carry `workspace_id`; `deal` exposes
  `UNIQUE KEY uq_deal_workspace_id (workspace_id, id)` (`schema.sql:189`) so children can FK the
  composite key.
- `DealMapper.xml` scopes **every** statement with `WHERE workspace_id = #{workspaceId} AND id = #{id}`
  (IDOR-safe; `DealMapper.xml:110,154,158`); junction writes verify parent ownership via
  `INSERT … SELECT … WHERE d.workspace_id = #{workspaceId}` (`DealMapper.xml:173-211`).
- `NotificationScheduler` already loops per-workspace (`findWorkspaceIds()`, `NotificationScheduler.java:35`).

But the **original core entities were never retrofitted**. `company`, `person`, `pipeline`, `stage`,
`tag`, `activity`, `note`, `attachment` and every junction (`deal_person`, `*_tag`) are **global —
shared across all tenants** — and their mappers/services/controllers have no workspace scoping at all.

**Nothing leaks *today*** only because every user shares one `default` workspace
(`AuthService.register` force-joins `getDefaultWorkspace()`, `AuthService.java:67-71`) and
`WorkspaceService.getCurrentWorkspace()` **throws** if a user belongs to >1 workspace
(`WorkspaceService.java:34-36`). The instant a second workspace exists, the leaks below detonate.

### Confirmed leak classes (ranked by blast radius after a multi-tenant flip)

| # | Leak | Type | Evidence |
|---|------|------|----------|
| 1 | **Global search** returns 8 of 10 entity types across all tenants — companies, contacts (PII), notes, **and every user's name+email** | READ, 1 endpoint, max reach | `SearchService.search` resolves `workspaceId` but passes it only to `dealMapper.search`/`taskMapper.search`; the other 8 run unscoped `LIKE` (`SearchService.java:57-68`); `userMapper` search has no `workspace_member` join |
| 2 | **CRUD by bare `id`** = cross-tenant write/delete IDOR on 7 entities | WRITE/DELETE | `getById`/`update`/`delete` key on `WHERE id = #{id}` (`CompanyMapper.xml:38,65,68`; `PersonMapper.xml:77,182,185`; `TagMapper.xml:39,41`; etc.); controllers pass `{id}` with no workspace context (`CompanyController.java`). `TagMapper.delete` cascades across all four `*_tag` junctions |
| 3 | **`tag.name` GLOBAL UNIQUE** — one tenant's "VIP" blocks all others; insert-collision is an existence oracle | constraint / DoS | `schema.sql:116` |
| 4 | **Polymorphic attachment** trusts client `(entityType, entityId)` with no FK and no ownership check — plant a file row onto another tenant's record | WRITE/inject | `AttachmentService.create`; `AttachmentService` injects no `WorkspaceService` |
| 5 | **Analytics + TeamLeaderboard** mix scoped deals/tasks with unscoped activities/notes/companies/**users** — leaks headcount, names, activity volume | READ aggregate | `overview/analytics/page.tsx`; `TeamLeaderboard` ranks the global user roster |
| 6 | **Audit log** has no `workspace_id` column at all → cross-tenant action history, actor names, IPs, change diffs | READ | `AuditService.recent`/`forEntity`; `audit_log` `schema.sql:409` (append-only triggers `:433-436`) |

Plus latent structural issues: cross-entity write straddle (a workspace-A note/task/deal can reference a
workspace-B person/tag because parents are validated with **unscoped** `getById`), and a stored-PII
snapshot — `findTaskReminderCandidates` joins `person` with no workspace predicate and snapshots
`p.name` into `notification.context_label`, so foreign-tenant names get *persisted* into inboxes.

---

## 2. Target tenancy model

**Shared-database, shared-schema** with a single `workspace_id` integer discriminator. Every business
row belongs to exactly one workspace. Three classification rules:

- **Directly-scoped** — the row *is* tenant data (top-level aggregate or polymorphic owner). Carries
  `workspace_id NOT NULL`, exposes `UNIQUE KEY uq_<t>_workspace_id (workspace_id, id)` so children can
  FK the composite, and references parents via composite FKs.
- **Inherits-via-parent (denormalized)** — logically owned by one parent (a deal, a pipeline) but still
  carries a *denormalized* `workspace_id` so both ends are pinned by composite FK. Used for all
  junctions and for `stage`. Defense-in-depth: a junction can never bridge tenants even with a stale id.
  (`deal_collaborator`, `schema.sql:307-316`, is the reference.)
- **Intentionally-global** — identity/config: `app_user`, `workspace`, `workspace_member`,
  `notification_preference` (per-user, all workspaces). No `workspace_id`.

| Table | Today | Target | `workspace_id` source | `uq(ws,id)` anchor | Composite FKs to add |
|---|---|---|---|---|---|
| `app_user` / `workspace` / `workspace_member` | global | **global** | — | no | — |
| `notification_preference` | global | **global** (per-user) | — | no | — |
| `company` | global | **directly-scoped** | default ws (backfill) | yes | — |
| `person` | global | **directly-scoped** | default ws | yes | `(ws,company_id)→company` |
| `pipeline` | global | **directly-scoped** | default ws | yes | — |
| `stage` | global | **inherits pipeline (denorm)** | parent pipeline | yes | `(ws,pipeline_id)→pipeline` |
| `tag` | global, `name` UNIQUE | **directly-scoped** | default ws | yes | — |
| `activity` | global | **directly-scoped leaf** | deal→person→default | no¹ | `(ws,deal_id)→deal`, `(ws,person_id)→person` |
| `note` | global | **directly-scoped leaf** | deal→person→default | no¹ | `(ws,deal_id)→deal`, `(ws,person_id)→person` |
| `attachment` | global, polymorphic | **directly-scoped** | resolve owner→default | yes | none (polymorphic; app-validated) |
| `deal` | scoped ✓ | done | self | yes ✓ | swap `pipeline/stage/company` to composite |
| `task` | scoped ✓ | done | self | n/a | swap `person_id`/`deal_id` to composite |
| `deal_person` / `person_tag` / `company_tag` / `deal_tag` / `attachment_tag` | global junction | **inherits parent (denorm)** | authoritative parent | no | both ends composite |
| `deal_collaborator` / `notification` | scoped ✓ | done | self | n/a | — |
| `audit_log` | global | **directly-scoped, NULLable** | active ws at write | no | none (system events keep NULL) |

¹ `activity`/`note` are leaves — nothing FKs *to* them — so they need `workspace_id` + composite parent
FKs but no `uq(workspace_id,id)` anchor.

**User-reference hardening:** `deal.owner_id`, `activity.created_by_id`, `note.author_id`,
`attachment.uploaded_by_id`, `notification.actor_id` FK `app_user(id)` globally. Where membership
integrity matters (owner), make composite `(workspace_id, user_id) → workspace_member` (the
`fk_task_assigned_member`/`fk_notification_recipient_member` pattern). Historical refs
(author/creator/uploader/actor) should *survive* a member leaving → keep `app_user` FK with
`ON DELETE SET NULL` (product call).

---

## 3. Schema & migration

### 3.1 DDL shape (fresh-install `schema.sql`)

Directly-scoped anchor (template for company/pipeline/tag/person/attachment):

```sql
workspace_id INT NOT NULL,
CONSTRAINT fk_<t>_workspace FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE RESTRICT,
UNIQUE KEY uq_<t>_workspace_id (workspace_id, id),   -- anchor for children
INDEX idx_<t>_workspace (workspace_id)
```

Composite parent FK (the cross-tenant-reference fix — applies to `deal`, `task`, `activity`, `note`,
`stage`, all junctions):

```sql
-- e.g. person → company in the SAME workspace
CONSTRAINT fk_person_company FOREIGN KEY (workspace_id, company_id)
    REFERENCES company(workspace_id, id) ON DELETE SET NULL
-- e.g. deal's three bare FKs become composite
ADD CONSTRAINT fk_deal_pipeline FOREIGN KEY (workspace_id, pipeline_id) REFERENCES pipeline(workspace_id, id) ON DELETE RESTRICT,
ADD CONSTRAINT fk_deal_stage    FOREIGN KEY (workspace_id, stage_id)    REFERENCES stage(workspace_id, id)    ON DELETE RESTRICT,
ADD CONSTRAINT fk_deal_company  FOREIGN KEY (workspace_id, company_id)  REFERENCES company(workspace_id, id)  ON DELETE SET NULL
```
> MySQL composite FK with a nullable column (`company_id`) is satisfied when any part is NULL, so
> `ON DELETE SET NULL` still works; `workspace_id` is never NULL.

Junction (denormalized `workspace_id`, both ends composite — makes cross-tenant links *structurally
impossible*):

```sql
CREATE TABLE deal_person (
    workspace_id INT NOT NULL, deal_id INT NOT NULL, person_id INT NOT NULL, role VARCHAR(64),
    PRIMARY KEY (workspace_id, deal_id, person_id),
    CONSTRAINT fk_deal_person_deal   FOREIGN KEY (workspace_id, deal_id)   REFERENCES deal(workspace_id, id)   ON DELETE CASCADE,
    CONSTRAINT fk_deal_person_person FOREIGN KEY (workspace_id, person_id) REFERENCES person(workspace_id, id) ON DELETE CASCADE,
    INDEX idx_deal_person_person (workspace_id, person_id)
);
-- person_tag / company_tag / deal_tag / attachment_tag follow the identical shape
```

Constraint fixes:

| Current | Fix |
|---|---|
| `tag.name … UNIQUE` (global, `schema.sql:116`) | drop global UNIQUE; add `UNIQUE KEY uq_tag_workspace_name (workspace_id, name)` |
| `idx_person_email (email)` global (`:136`) | → `idx_person_workspace_email (workspace_id, email)` |
| `pipeline.name` | add `uq_pipeline_ws_name (workspace_id, name)` (per-tenant; never global) |
| `uq_stage_pipeline_position (pipeline_id, position)` (`:154`) | already pipeline-scoped — keep |
| `audit_log` | `ADD workspace_id INT NULL` + `idx_audit_log_workspace (workspace_id, created_at)`; prefix `idx_audit_log_entity` with `workspace_id` |

### 3.2 Migration mechanics — **the config trap comes first**

There is **no Flyway/Liquibase**. `schema.sql` does `DROP DATABASE IF EXISTS connexdb` (`schema.sql:10`),
and **source `application.yml:15` has `# mode: always` (commented) while the *built*
`build/resources/main/application.yml:15` has `mode: always` (active)** — a real source/artifact drift,
verified. So the built app **drops and recreates the DB on every boot**. Any backfill on a populated DB
is destroyed on restart.

**Prerequisite for all phases:** reconcile the drift and set `spring.sql.init.mode: never` for any
environment holding real data; adopt Flyway/Liquibase with `schema.sql` as a **non-destructive
baseline** (no `DROP DATABASE`); fold the existing `conversions/*.sql` in as versioned migrations.

Two parallel tracks, authored together:
- **Dev / fresh install** → edit `schema.sql` to the §3.1 target form (tables created correct, no
  ALTERs); seed rows set `workspace_id` to default.
- **Prod / populated** → idempotent stored-procedure conversion in `backend/conversions/`, modeled on
  `2026-06-23_notification_system.sql` (verified: `information_schema`-guarded
  nullable-add → backfill → `SIGNAL` verify → `MODIFY NOT NULL`, re-runnable).

> ⚠️ The notification template **aborts if any user has ≠1 membership** (`…notification_system.sql`
> `HAVING COUNT(*) <> 1` guard) — mirroring `WorkspaceService`'s throw. The full-tenancy conversion must
> **not** copy that guard; multi-membership is gated by the new resolver instead.

### 3.3 Per-table backfill derivation

| Table | `workspace_id` derivation |
|---|---|
| `company`, `pipeline`, `tag`, `person` | default workspace (no inherent owner) |
| `stage` | parent `pipeline.workspace_id` |
| `activity`, `note` | `COALESCE(deal.workspace_id, default)` (deal_id may be NULL) |
| `attachment` | resolve `(entity_type, entity_id)` → owner's workspace; else default |
| `deal_person`, `deal_tag` | `deal.workspace_id` (authoritative) |
| `person_tag` | `person.workspace_id` · `company_tag` | `company.workspace_id` · `attachment_tag` | `attachment.workspace_id` |
| `audit_log` | leave NULL (or resolve from entity for entity-scoped actions; system/auth stay NULL) |

**Ordering invariant** (so no FK violates mid-flight): add nullable column → backfill → `SIGNAL`-verify
(no NULLs, no cross-tenant straddle) → `MODIFY NOT NULL` → add `uq(ws,id)` anchors + workspace FKs →
**swap bare-id child FKs to composite LAST**, parents before children
(`company → pipeline → stage → tag → person → activity/note/attachment → junctions → deal/task FK swaps → audit_log`).

---

## 4. Backend enforcement

### 4.1 Active-workspace resolution — **session attribute (trust anchor) + `X-Workspace-Id` header (re-validated hint)**

Not a URL segment. Rationale: auth is already cookie/session based
(`HttpSessionSecurityContextRepository`, `AuthService.java:42,110`); the session is server-controlled and
survives across tabs. Store `activeWorkspaceId` on the session, set at login, mutated only by an
authenticated switch endpoint. The `X-Workspace-Id` header lets the client pin a workspace per-call
(SSR fan-out, multi-tab) and — since CSRF is globally disabled (`SecurityConfig.java:40`) — a custom
header is a *mild* CSRF mitigation. **The header is always a hint:** the resolver calls
`requireMember(headerWs, callerId)` and 403s if the caller isn't a member. A URL segment is rejected —
it forces rewriting every `<Link>`/`router.push` and `proxy.ts` matcher for no security gain (membership
still must be validated server-side).

- **Login** (`AuthService.login`, after `:110`): seed `session.activeWorkspaceId` from the user's
  remembered-last or first membership.
- **Switch**: new `WorkspaceController` `POST /api/workspaces/{id}/switch` → `requireMember` → set
  session attr → persist "last active".

### 4.2 Request-scoped `TenantContext` (no param-threading churn)

`getCurrentWorkspaceId()` today re-runs the `getWorkspacesForUser` JOIN on **every call** (DealService
alone ~27×/request). Resolve **once per request**:

```java
@Component @RequestScope
class TenantContext { Integer workspaceId, userId; String role; boolean resolved;
                     int getWorkspaceIdOrThrow() { if(!resolved) throw new ForbiddenException(...); return workspaceId; } }

// HandlerInterceptor (registered in WebConfig, runs AFTER the security filter so principal is present):
preHandle: ws = header "X-Workspace-Id" ?? session.activeWorkspaceId;
           if (ws == null) return true;                       // onboarding → leave unresolved → fail-closed
           role = workspaceService.requireMemberWithRole(ws, user.id);   // 403 if not a member
           ctx.set(user.id, ws, role, resolved=true);
```

`WorkspaceService.getCurrentWorkspaceId()` is rewritten to read the context and **drop the `size()>1`
throw** (`WorkspaceService.java:34-36`). Every service keeps calling
`workspaceService.getCurrentWorkspaceId()` exactly as `TaskService` does (`TaskService.java:40`) — no
signature changes — but it's now a cheap field read.

**SQL scoping mechanism — keep explicit `@Param`, add a MyBatis interceptor as a fail-closed net (both,
layered).** Explicit `@Param("workspaceId")` + `WHERE … workspace_id = #{workspaceId}` stays the primary,
greppable, reviewable mechanism (it's the proven Deal/Task/Notification pattern). Additionally wire the
**empty** `MyBatisConfig` (it's a stub today) with an interceptor that **throws if a statement tagged
workspace-scoped runs while `TenantContext` is unresolved** — it does *not* rewrite SQL (can't safely
rewrite arbitrary joins/unions/the polymorphic attachment query), it just turns a forgotten predicate
into a hard error instead of a silent leak.

### 4.3 Defense-in-depth vs IDOR

1. **Composite-key lookups → 404, not 403.** Every `getById`/`update`/`delete` keys on
   `(workspace_id, id)` (mirrors `DealMapper.xml:110`). Cross-tenant id → `null`/0 rows →
   `ResourceNotFoundException` (hides existence). Be consistent: cross-tenant id = **404**;
   membership/role denial = **403**.
2. **Scoped-exists on every cross-entity write.** One helper used everywhere a foreign id is accepted:
   ```java
   if (dealId   != null && !dealMapper.exists(ws, dealId))   throw new BadRequestException("deal not in workspace");
   if (personId != null && !personMapper.exists(ws, personId)) throw new BadRequestException("person not in workspace");
   if (tagId    != null && !tagMapper.exists(ws, tagId))     throw new BadRequestException("tag not in workspace");
   ```
   Closes the named seams: `ActivityService/NoteService/AttachmentService.create` (persist caller
   parents unchecked), `DealService.addPerson/addTag` (unscoped `getPersonById`/`getTagById`), and even
   "gold-standard" `TaskService.validateReferences` (validates deal, **not** person). `create()` sets
   `bean.setWorkspaceId(ws)` server-side — **never trust a client `workspaceId`**.
3. **Fail-closed default** — `getWorkspaceIdOrThrow()` + the MyBatis assertion interceptor; junction
   inserts gate the *foreign* side too (`DealMapper.insertTags` must add `AND t.workspace_id = #{ws}`).

### 4.4 Per-layer change list

- **Mappers** (`Company/Person/Pipeline/Tag/Activity/Note/Attachment/AuditLog`): add
  `@Param("workspaceId")` to every `getAll*`/`getById`/`search`/`getBy*`/page/facet/`update`/`delete`;
  add `AND <alias>.workspace_id = #{workspaceId}`; key `update`/`delete` on `(workspace_id, id)`; add
  `exists(ws,id)`. Joins to scoped tables get a predicate. Junction inserts gate both sides.
  `*FullResult` collections change `column="id"` → `column="{workspaceId=workspace_id, …Id=id}"` and
  their nested selects take `workspaceId` (matching `DealMapper.xml:26-35`).
  `userMapper.search`/`getAllUsers` → `JOIN workspace_member wm … AND wm.workspace_id = #{workspaceId}`.
- **Services**: inject `WorkspaceService` where missing (notably `AttachmentService`); thread
  `getCurrentWorkspaceId()` into every CRUD method, copying `TaskService` (`:39-97`); add the
  cross-entity guard (§4.3.2).
- **Controllers**: no per-endpoint workspace param (implicit via `TenantContext`); add the new
  `WorkspaceController` (list/switch/create/invite); add role annotations (§4.5).
- **Search/analytics**: pass `workspaceId` into all 10 `*Mapper.search` calls (`SearchService.java:57`),
  user-search member-scoped; scope analytics feeders (`getAllActivities`/`getAllNotes`/`getAllCompanies`
  + member-scoped `getUsers`) so `ActivityVolume`/`TeamLeaderboard` aggregate one tenant; scope
  attachment library `getPage`/facets and the person directory `getPage`.
- **Background jobs**: `NotificationScheduler` is already the template (loops `findWorkspaceIds()`,
  passes `workspaceId` explicitly). **Rule: never call `getCurrentWorkspaceId()` off the request
  thread** (no principal → throws). Provide a `forEachWorkspace(IntConsumer)` helper; keep
  `findWorkspaceIds()` confined to background services. When `person` gains `workspace_id`, add the
  predicate to `findTaskReminderCandidates`'s person join **and re-reconcile existing
  `notification.context_label` snapshots** (the leaked value is denormalized and survives the join fix).

### 4.5 Authorization / roles

`workspace_member.role` (`owner`/`admin`/`member`) is written at registration (`AuthService.java:71`)
and **never read** — no `@PreAuthorize`/method security anywhere. Today every member can delete
pipelines, tags, companies, and other users. Add a `requireRole(ws, userId, min)` chokepoint reading
`TenantContext.role`; enable `@EnableMethodSecurity`. Proposed policy (confirm with product):

| Operation | Min role |
|---|---|
| Read/create/update records (company, person, deal, activity, note, task, attachment) | member |
| Pipeline/stage CRUD, tag CRUD, company **delete** | admin |
| Member invite/remove, role change, workspace settings, audit-log read | admin |
| Delete workspace, transfer ownership | owner |

Also: `requireMember` should throw **403** not 400; `UserService.create` inserts a user with **no**
membership (unlike `register`) — route through `addMember`, gate `admin`+. `register` should create a
**new owned workspace** (or invite/accept) instead of force-joining `default`; `getDefaultWorkspace()`
becomes the migration-only backfill target.

> **✅ Invite flows implemented (2026-06-24, #11):** both flows ship. Email-token invites
> (`workspace_invite`, migration V11) via `InviteService` + `WorkspaceController` `POST/GET/DELETE
> /api/workspaces/{id}/invites` (admin-gated) and token-addressed `GET /api/invites/{token}` +
> `POST /api/invites/{token}/accept` (email-bound, expiry-checked). Admin-adds-existing via
> `POST /api/workspaces/{id}/members`. Role-change and member removal remain (removal must respect the
> `task.assigned_to_id` `RESTRICT` member FK).

## 5. Frontend

**Transport: header + cookie** (aligns with §4.1; no URL segment). `api.ts` funnels ~120 wrappers
through one `requestJson` (`api.ts:17-43`) → inject the active workspace **once** there.

- **Client components** → `X-Workspace-Id` header from a non-`HttpOnly` `connex_workspace` cookie.
- **SSR pages** (`records/deals/page.tsx`, `me`, `notes`, `search`) → already forward
  `headers: { cookie }` via `safeWithCookie` (`api.ts:80-90`); the `connex_workspace` cookie rides along.
  Resolver precedence: header > cookie > user's default membership.
- `connex_workspace`: `Path=/`, `SameSite=Lax`, **not** `HttpOnly` (the provider reads it; it's just an
  integer and the backend re-validates membership). `JSESSIONID` stays the `HttpOnly` auth boundary.

Pieces:
- **Types** (`app/lib/types.ts`): `Workspace { id, name, slug, role }`, `MyWorkspaces { workspaces, activeWorkspaceId }`.
  (`Deal.workspaceId?`/`Task.workspaceId?` at `:112/:248` stay server-derived, never client-sent.)
- **API** (new `WorkspaceController`): `getMyWorkspaces`, `createWorkspace`, `switchWorkspace`, `acceptInvite`.
- **Provider** `app/hooks/useWorkspace.tsx` (mirror `NotificationProvider`), mounted in
  `app/(app)/layout.tsx` **wrapping** `NotificationProvider`; redirect to `/onboarding` if
  `workspaces.length === 0`.
- **Switcher** replaces the static org name at `Sidebar.tsx:328-330` (existing TODO) with a Radix
  `DropdownMenu` (Radix already imported there) listing workspaces + "Create or join workspace".
- **Cache invalidation on switch** (the real stale-data risk; no React Query/SWR): `router.refresh()`
  (re-runs SSR lists); add `activeWorkspaceId` to `useNotifications.tsx` poller deps (`:54`) and refresh
  immediately (else the bell shows the previous tenant's count up to 45s); thread `activeWorkspaceId`
  into `useServerRecords.ts` `load` deps (`:54`) so Files/Contacts directories refetch + reset to page 1.
- **Onboarding** `app/onboarding/page.tsx` (outside `(app)`): Create (→ owner) / Join (invite token);
  `AuthForm.tsx` routes new users with no workspace there.
- **Proxy** `frontend/proxy.ts` (Next 16 — middleware renamed to `proxy`; **read
  `node_modules/next/dist/docs/…/proxy.md` before editing** per `frontend/AGENTS.md`): allow a
  session-without-`connex_workspace` through (layout redirects to onboarding); gate `/onboarding` on
  session only; no `/[workspace]` segment.
- **i18n EN/JA parity**: switcher → `CommonSidebar` (en + ja); new `onboarding.json` namespace (register
  in `i18n/request.ts` namespaces array); invite copy → `auth.json`. ⚠️ `loadMessages` swallows a
  missing fragment via try/catch (`request.ts:36-40`) → a missing `ja` file degrades **silently**; add a
  CI parity check asserting identical key sets across `messages/en/*` and `messages/ja/*`.

---

## 6. Testing

- **Infra**: introduce **Testcontainers MySQL** via `@DynamicPropertySource` so the suite stops sharing
  live `connexdb` (`application.yml:7`); keep `@Transactional` rollback.
- **What breaks immediately**: `AbstractMapperTest` seed helpers (`newCompany:62`, `newPipeline`,
  `newStage`, `newTag`, `newPerson`) take no workspace → fail once those tables require
  `workspace_id NOT NULL`; `WorkspaceMapperTest:24` asserts exactly one membership;
  `CompanyMapperTest`/`TagMapperTest` actively assert **global** visibility/uniqueness; most service
  tests have no `WorkspaceService` mock. Update in lockstep, not after.
- **Seeding**: make `AbstractMapperTest` workspace-aware (overloads + no-arg delegating to a default
  workspace); add `seedTwoTenants()` building a full entity graph in each of two workspaces.
- **Isolation regression suite** (model on the one existing isolation test, `DealMapperTest:259-272`):
  per scoped mapper assert **read** (`getAll`/`getById`/`search` for A never returns B), **write/IDOR**
  (`update`/`delete` of a B id from A affects 0 rows), **cross-entity link** (A activity/note/task/deal
  referencing a B parent → rejected), **search fan-out** (B-only company/person/user/note name → empty
  for A; user search returns only A members), **reminder recipient** (a B collaborator is never an A
  candidate), **hydration fan-out** (`*FullResult` never hydrates B children), **snapshot leak**
  (reconciliation never writes a B name into an A `context_label`). Add multi-membership resolver tests.
  **Write each entity's isolation test before its scoping merges.**

---

## 7. Phased rollout

Each phase is an independently mergeable, flag-gated branch/PR. **Pre-launch (#1)** removes the
nullable-add → backfill → NOT-NULL dance: schema migrations run on **empty tables**, so they write the
target shape directly. Phases 2–3 are observable no-ops on the single `default` workspace; Phase 4 is the
behavior-changing flip.

| Phase | Issue | Scope | Risk | Kill-switch |
|---|---|---|---|---|
| **0 — Foundation / stop the bleeding** | **#88**, #67 | Adopt **Flyway**; convert current `schema.sql` to a `V1__baseline` migration **without** `DROP DATABASE`; disable `spring.sql.init` + fix the `mode: always` source/build drift. Re-enable **CSRF** (cookie token repo + frontend header). Harden session cookies (`HttpOnly`/`Secure`/`SameSite`). Move DB creds to env vars. *(All independent of the sharing/RBAC design.)* | low–med | config-reversible; CSRF behind a profile flag during cutover |
| **1 — Schema (target shape, empty tables)** | **#89**, #61 | Flyway migrations adding `workspace_id` to company/person/pipeline/stage/tag/activity/note/attachment/audit_log + junctions; per-tenant `tag` uniqueness; `uq(ws,id)` anchors; composite FKs **private↔private only**; **plain FKs** private→shareable (§0.1); `*_share` tables created (unused until sharing sub-phase); member-FK hardening on owners/collaborators | low (empty tables) | additive; revert = drop migration + recreate |
| **2 — Resolution + TenantContext + MyBatis interceptor** | #89 | `@RequestScope TenantContext` + `HandlerInterceptor` (header→cookie→single membership), `requireMember` gate, **403** fix, `getCurrentWorkspaceId()` reads context. **Wire the mandatory MyBatis scope interceptor as the primary enforcement** (§0.4), fail-closed when unresolved. Keep `size()>1` throw for now | med | `multitenancy.resolver.enabled` → legacy single-membership path |
| **3 — Scope queries + IDOR + search/analytics** | #89, #81 | Thread `workspaceId` through every Company/Person/Pipeline/Tag/Activity/Note/Attachment/Search/Audit mapper+service (mirror `DealService`); `(workspace_id, id)` lookups → 404; cross-entity parent visibility checks (owned-or-shared for shareable; incl. the missing `TaskService` person check); member-scoped user search/leaderboard; `*FullResult` composite collections. **Closes the leaks.** Fold in authorship-from-session (#71/#72), LIKE escaping (#76), page-size caps (#73) | high | `multitenancy.scoping.enforced`; no-op on one workspace |
| **4 — Multi-membership + switching + onboarding** | #61 | Remove `size()>1` throw; `WorkspaceController` (list/switch/create/invite, both invite flows #11); registration creates an owned workspace + `allow_public_workspace_creation` instance setting (#9/#81); cross-workspace notification inbox (§0.3); full frontend (provider/switcher/onboarding/cache/transport/i18n). **The flip** | high | `multitenancy.multiMembership.enabled` off → single-workspace behavior |
| **5 — Authorization / roles** | **#90**, #11 | Built-in `owner`/`admin`/`member` enforcement (`@EnableMethodSecurity` + `TenantContext.role`); per-op policy (plan §4.5); fix `UserService.create` membership gap | med | policy behind a flag; default-allow → default-deny per op |
| **6 — Sharing enablement** | #61 | Wire `*_share` junctions + owned-or-shared visibility into the interceptor & queries; share/un-share UI; pipeline templating | med | `multitenancy.sharing.enabled` |
| **7 — Custom RBAC** | #11 | `workspace_role` + permission catalog + `workspace_role_permission`; owner-defined roles (§0.2) | med | feature-flag |
| **8 — Audit + hardening tail** | **#91**, #68/#69/#79/#80 | `audit_log.workspace_id` filtering + super-admin combined view (#14); blob/URL access control (#68/#69); export scoping (#57); Next fetch-cache keys; session-fixation/rotation (#79); rate-limiting (#80) | med | per-item flags |

**Invariant**: 0 (foundation) → 1 (schema) → 2 (resolver+interceptor) → 3 (scope, no-op) → 4 (flip) →
5 (authz) → 6 (sharing) → 7 (custom RBAC) → 8 (audit+tail). Phases 5–8 are independent of each other once
1–4 land.

---

## 8. Decisions — RESOLVED 2026-06-23 (see §0 for the locked answers)

> All seven below were answered in the 20-question round and are recorded in §0. Kept here for the
> rationale trail. Notably: shared address book (#2 → §0.1), per-tenant tags & shareable pipelines,
> header+cookie transport, owned-workspace registration with an opt-out instance setting, custom RBAC.

### Original open questions (now answered)

1. **Fate of existing `default`-workspace data** — assign all legacy rows to `default` and keep current
   users as members of it (simplest), or partition rows by their related deals' workspace before cutover?
   *Foundational — everything downstream depends on it.*
2. **Tags & pipelines: per-tenant or shared?** Plan assumes per-tenant (`tag.name` →
   `(workspace_id, name)`; clone a default pipeline per workspace). `deal.pipeline_id` is `FK RESTRICT`,
   so a shared pipeline would couple tenants' deletes.
3. **Can a person/company belong to multiple workspaces, or be duplicated?** Plan assumes single-owner
   (`workspace_id` column). A shared address book would need a junction + per-workspace overlay — a
   *different* schema shape for company/person. Also: per-tenant unique email/name?
4. **Active-workspace transport** — header+cookie (chosen; smallest blast radius, mild CSRF resistance)
   vs `/[workspace]/…` URL segment (shareable/per-tab but large frontend cost).
5. **Registration semantics & role policy** — new user creates+owns a workspace vs invite-only join;
   which operations are owner/admin/member (the role column is written but unused).
6. **Notification preferences** — stay per-user-global, or become per-workspace?
7. **Audit log** — per-tenant filterable, or a global super-admin-only compliance log?

---

## 9. Smallest safe first PR (no schema, no migration, reversible)

**"Scope global search & user lookup to the active workspace + add the resolver scaffold."**

1. Introduce `TenantContext` + the request-scoped resolver behind a flag (reads session/header, falls
   back to the user's single membership). `getCurrentWorkspaceId()` reads it — behavior identical for
   today's single-membership users, and it kills the N-queries-per-request. **No schema change.**
2. Scope `userMapper.search`/`getAllUsers` to `workspace_member` — **needs no new column** (users are
   already scoped via the existing membership table). Immediately closes the worst PII surface (every
   user's email via search and the leaderboard).
3. Gate `SearchService` so the 8 unscoped entity searches require membership — or temporarily drop them
   from results until their columns land (returning fewer results is safe; leaking is not).

Highest-leverage, lowest-risk: it hits the most-reachable leak (#1/#5), exercises the resolver every
later phase depends on, needs no DDL/backfill/FK-swaps, is independently revertable, and forces the
transport decision (§8.4) to surface early on a small surface.
