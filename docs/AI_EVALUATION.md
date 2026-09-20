# Ask Connex Agent Evaluation

Two things live here, and keeping them apart is the point of the document.

1. **The map of the evaluation machinery that exists today** — the scripted provider, the trajectory harness, and the twenty goldens that run in CI on every backend change. This half is verified against the code and is safe to act on.
2. **The design of a live-evaluation runner that does not exist** — an operator-invoked process that runs an authored corpus against a *real* model provider on staging, over data it seeds and disposes of itself. Nothing in that half is built, and building it is decision-gated: see [Decision gate](#decision-gate).

The provider-egress contract itself is not restated here. It lives in [`backend/AI_SECURITY.md`](backend/AI_SECURITY.md), which is authoritative for gates, masking, adapters, budgets, and the scripted seam's five activation layers.

## Two runners, and why an operator cannot swap them

| | Scripted trajectory harness (**exists**) | Live evaluation runner (**designed only**) |
|---|---|---|
| Model answers come from | `*.json` fixtures on the test tree | a real configured provider |
| Network egress to a provider | none, ever | yes — this is the point |
| Provider credential | none exists | the target organization's own |
| Where it runs | a Gradle test fork, and CI | one operator-launched process against staging |
| Spring profile | `ai-scripted-provider` | `ai-eval` (proposed) |
| Deployment edition | refuses to boot under any | runs under the edition staging already declares |
| Data it reads | fixtures it seeded and deletes | records it seeded, in a marked organization |
| What it proves | the server's own controls fire | a real model's behaviour against those controls |

**The two are mutually unbootable, by construction rather than by convention.** `DeploymentProfileValidator` refuses the `ai-scripted-provider` Spring profile whenever `connex.deployment.profile` is non-blank, and refuses it again outside `dev`/`test`; the flag `connex.ai.scripted-provider.enabled` is additionally on the forbidden-key list of all three editions. Staging declares `CONNEX_DEPLOYMENT_PROFILE=silo` (see [`STAGING_DEPLOY.md`](STAGING_DEPLOY.md)), so a scripted provider cannot start there at all — with either the profile or the flag present the process refuses before the application context exists. The live runner is designed to run under that same declared edition, so it can never be the scripted one in disguise.

The failure an operator would otherwise make is the reverse one: assuming a green `scriptedTrajectoryTest` run says something about a model. It does not. Every word the scripted provider "says" was written by the fixture author. The harness proves what the **server** does with a model's output; only the live runner can say anything about the output itself.

## What exists today

Verified against `main`. Where this section and an older plan disagree, this section is the one checked against the code.

### The scripted provider

`backend/src/main/java/ooo/klae/connex/backend/ai/provider/scripted/` holds an `AiProvider` that answers from fixtures and **replaces** `OpenAiCompatibleAdapter` under its profile. Its activation gates, its fixture-free shipped artifact, and the dispatch-accounting contract every adapter owes are documented in [`backend/AI_SECURITY.md`](backend/AI_SECURITY.md#scripted-provider-test-seam). Four startup refusals guard it, all raised from `DeploymentProfileEnvironmentPostProcessor` before any bean exists:

- the profile beside a declared `connex.deployment.profile`;
- the profile outside `dev`/`test`;
- the profile without `connex.ai.scripted-provider.enabled=true`;
- the flag without the profile (while no edition is declared — a declared edition refuses it through the forbidden-key scan instead, naming the edition).

### The harness and the goldens

`AbstractScriptedTrajectoryTest` is the one Spring context that activates the profile. It runs whole turns — `AiAssistantTurnService`, the generation worker, `AiChatAgentLoopService`, the real tool executor, the real write-tool service, real MySQL — with nothing below the agent loop mocked. Twenty goldens run on it, over nineteen fixtures in `backend/src/test/resources/ai/scripted/`:

- `AiAssistantScriptedTrajectoryTest` (ten): multi-step read with grounded citations; an AUTO write that loads its toolset first and is undone; a CONFIRM proposal that leaves the deal alone; an unknown citation settling `malformed_output`; an injected tool result staying inside the untrusted envelope; three provider failure kinds (`provider_error`, `provider_idle_timeout`, `turn_deadline_exceeded`); masked egress; and the demask round trip.
- `AiAssistantScriptedTrajectoryGuardTest` (ten): skill authority; the context floor; native-to-JSON degradation; the no-progress guard in both its answering and its failing form; a streamed answer; special-care exclusion buffered and streamed; a restriction epoch advancing between steps; and a proposal whose target moved before approval.

One fixture serves two goldens (`confirm_proposal` backs both the proposal golden and the freshness refusal), which is why nineteen files carry twenty cases.

**Run them with `./gradlew scriptedTrajectoryTest`, never `./gradlew test`.** The pattern `**/*ScriptedTrajectory*Test.class` is excluded from `test` so this context key does not evict a hot one out of the shared ten-slot cache; the task has its own fork with a two-slot cache, `check` depends on it, and the required `Backend — build & test` CI job names `test scriptedTrajectoryTest` explicitly. `ScriptedAiProviderArchTest` asserts both that the workflow still names the task and that every class extending the harness matches the include pattern, so a golden CI would silently skip fails the build instead of passing quietly.

## Constraints a golden author would otherwise rediscover

Each of these was paid for once. Every one is decided by code named beside it.

- **A turn starts holding only the `core` toolset, so a non-core tool needs a `find_tools` step first.** `AiAssistantToolCatalog.CORE` is the set every turn holds; everything else is loaded on demand, and the goldens assert the durable tool order (`search_records`, `find_tools`, `create_task`) rather than the write alone. A fixture that reaches straight for `create_task` does not rehearse a shortcut — it rehearses a refusal.
- **A routed skill may seed toolsets, but never a write family it could not call anyway.** `SkillSpec`'s compact constructor refuses a declaration whose seeded toolset contains a write tool its `authority` or `allowedTools` cannot reach, because seeding is all-or-nothing over an object family while write authority is per tool. Seeding is not a grant: `requireSkillAuthority` still owns writes, and a write outside the declaration settles the turn `tool_outside_skill_authority`.
- **A step predicate is `(afterToolCalls, onRepair, protocol, closing)`, and two steps may not share one.** Alternative outcomes at the same cursor position are therefore not expressible in one script: three failure kinds after the same read need three fixtures with three selectors, which is exactly why `transport_failure`, `idle_timeout_failure` and `deadline_failure` are separate files.
- **`protocol` is a closed set — `native`, `json`, or `any`.** One turn can visit both: a client-error rejection on the first native attempt does not fail the turn, it clears the native state and retries the same cursor position as JSON. A fixture declaring `expectsNativeDegradation` must declare **both** halves — the rejecting native step and the JSON step that answers the retry — and `ScriptedAiScriptLoader` refuses it otherwise, because a script that can only end in a second rejection rehearses nothing. **A rejecting *repair* step is outside that rule**: the loop degrades only on its first native attempt, a repair attempt is by definition a later one, so a rejection there fails the turn and demanding a degradation declaration for it would make the fixture state something false about its own trajectory.
- **A selector must not *contain* another fixture's selector, or the loader refuses the whole directory.** Selectors are matched by substring search over the serialized prompt, so a request carrying the longer of two nested selectors carries the shorter one too. Uniqueness by equality does not cover it; `requireDisjointSelectors` does, at load time. This is why the streamed special-care fixture is `connex_script_medical_stream` and not `connex_script_special_care_stream` — the latter would contain `connex_script_special_care` and refuse the context for every golden at once.
- **A tool call's `arguments` must be a JSON object, and the loader parses it.** The provider never re-encodes the value: it becomes the native function call's arguments verbatim, or is spliced into the JSON step envelope as raw JSON. A typo otherwise arrives at trajectory time dressed as malformed model output.
- **Every turn claims a run lease, and the harness teardown deletes `ai_run_lease` rows.** The claim that flips a turn to running takes the lease; the loop never releases it, because the durable terminal write tombstones it so a killed process leaves an expiring lease another instance can settle rather than an owner-less running turn. A harness that deleted only the chat tables would leave lease rows behind for a workspace it then deleted, so the `@AfterEach` deletes them first.
- **The journal records every request the provider was *handed*, not every request that *left*.** Recording happens before the attempt executor's egress seam, which can still refuse on the restriction epoch, the feature gate, the provider guard or the admission commitment. Assert egress with `dispatched()`; `recorded()` being empty means the refusal happened above the provider entirely.
- **A leak assertion may only read the server-authored half of a request.** The server authors the system prompt, its directives and every masked tool result; the model authors its tool-call arguments, which native replay carries back verbatim and never re-masks. Scanning both halves measures the fixture rather than the pipeline. The same replay is why a fixture's search query must be a distinctive *fragment* of a seeded record's name rather than the whole name: `OutboundLeakScan` refuses any payload containing a registered raw identifier.

## What the harness cannot reach

These are gaps in reach, not gaps in the product. Closing any of them means adding a production seam, which a test-only change must not do.

- **Requester-only live channels (thinking, narration, todos).** Narration is the text a provider returns *beside* a native tool call, and a scripted tool-call emission carries no text; giving it one is production code. The guarantee stays owned by `AiChatTranscriptProjectionTest` and `AiChatStreamingProgressTest` at their own layers.
- **The durable-partial purge under `restrictions_changed`.** The purge only has something to purge on a streamed turn whose partial is already durable, so the epoch would have to advance between the stream's last batch and the terminal write. The step interceptor fires *before* the emission, so the very step it arms is the step the fence refuses and no partial is ever written. Reaching it needs a post-emission hook. `AiChatTurnPersistenceServiceTest` covers it at the service layer; the end-to-end gap is tracked as #1819 and is not scheduled.
- **Streamed delta ordering.** `AiAssistantTextDeltaProjector` confirms a `NATIVE_FINAL` answer only once the whole final object parses, so however many fragments the provider writes, the batcher is handed the answer once. Ordering and the projector's half-placeholder withholding have no observable effect on a native streamed trajectory, and the streamed golden deliberately claims neither.

The live runner does not close these either. It changes who writes the model's words; it does not add a seam.

## Live evaluation runner — design only

**Nothing below is built.** This section exists so that the thing, if it is ever built, is built this way.

### What it is for

The harness answers "does the server do the right thing with this model output?". It cannot answer "does a real model produce output the server can work with?" — tool-name accuracy, citation discipline, refusal behaviour under an injected tool result, whether the closing directive is honoured, whether a repair round trip converges. Those are properties of a model against this prompt envelope, and they change when the model changes, when the envelope changes, and when a provider silently updates a snapshot behind a model id. Only a real call measures them.

### Invocation

**An operator launches it, by hand, and nothing else can.** Concretely:

- A one-shot non-web process — `spring.main.web-application-type=none` — launched by hand, from the release staging is running. A dedicated `ai-eval` Spring profile gates the runner bean, and a `connex.ai.eval.enabled=true` flag gates it a second time, exactly as the scripted seam pairs a profile with a flag.
- **No controller, no endpoint, no job queue, no `@Scheduled` trigger, and no automation-rule action.** There must be no tenant-reachable path to it and no unattended path to it. A scheduled evaluator is a standing authorization to spend an organization's budget and egress to a provider with nobody watching, and that is not a thing the product should be able to do by accident.
- It runs under the **same** `CONNEX_DEPLOYMENT_PROFILE` the staging instance declares, so it inherits the edition's posture rather than escaping it.
- It refuses to start if `ai-scripted-provider` is active or its flag is set. Under a declared edition that combination already refuses; the runner's own refusal is the message an operator reads when they have mixed the two up.

#### No scheduled work, and no startup work

**The evaluation process runs no scheduled and no startup work, and a process that would start the normal workers must refuse to start.** Not serving HTTP has nothing to do with this: `BackgroundExecutionConfiguration` carries the application's `@EnableScheduling` and `@EnableAsync`, and its only gate is `connex.maintenance.mode=off` with `matchIfMissing = true` — as do the mutating `ApplicationRunner`s, `IdentityBackfillRunner` and `LegacyWorkflowBackfillRunner`. Launched with staging's ordinary configuration, this second process would run every default-on worker across every routed workspace while the evaluation is being scored: `AiBriefScheduler` and `NotificationScheduler` sweep them all, `WorkflowRuntimeScheduler` carries no property gate at all, and `AiRunLeaseSweeper` exists precisely to settle AI runs *another instance* owns.

The lever is therefore the maintenance mode, not the web type, because it is the one switch all of those gates read. `connex.maintenance.mode` is a closed set — `MaintenanceModeStartupValidator` throws `Unknown maintenance mode` on anything but `off`, `seeder` and `legacy-upload-migration` — so an `ai-eval` mode is added there with the eval profile, the eval flag and `web-application-type=none` as its conditions, exactly as the `seeder` branch already does. And the complement is a refusal: **the `ai-eval` profile active while the maintenance mode is `off` refuses to start**, because that is the configuration in which the workers would run.

#### The deployed release, and no migration

**The runner is launched from the release staging is actually running, with Flyway disabled, and refuses a schema that does not already match.** `spring.flyway.enabled` is `true` in `application.yml`, and [`STAGING_DEPLOY.md`](STAGING_DEPLOY.md) states that migrations run automatically on backend startup. The checkout at `/opt/connex-staging` is hard-reset to `origin/main` while the live release is whatever `.staging/deployed-sha` names, and an application rollback moves the running backend without moving the checkout, by design, because schema migrations are forward-only. A launch from the checkout is therefore routinely a launch from a *later* commit than the running backend, which would apply that commit's migrations to the live catalog before the deployment transaction that ships the matching code. Measuring a model must not be able to migrate a live catalog as a side effect.

#### The activation refusals belong in the validator, not in the runner

A refusal written into the runner bean is unenforceable, because `@Profile("ai-eval")` means the bean does not exist in exactly the configurations the refusal is for. The paired flag/profile rules therefore go in `DeploymentProfileValidator.evaluate`, beside `refuseScriptedAiProvider`, so they are raised from `DeploymentProfileEnvironmentPostProcessor` after ConfigData and before any context exists:

- the `ai-eval` profile active while `spring.main.web-application-type` is anything but `none` → refuse. This is the load-bearing one. Without it, appending `ai-eval` to `SPRING_PROFILES_ACTIVE` in `/etc/connex-staging/backend.env` — the same file [`STAGING_DEPLOY.md`](STAGING_DEPLOY.md) already tells an operator to edit for `CONNEX_DEPLOYMENT_PROFILE` and `CONNEX_WORKSPACES_ALLOW_CREATION` — boots the serving, tenant-facing instance with an evaluation runner live in-process;
- the profile active without `connex.ai.eval.enabled=true` → refuse;
- the flag true without the profile → refuse (no dormant flag);
- the profile active while `connex.maintenance.mode` is `off` → refuse, per [No scheduled work, and no startup work](#no-scheduled-work-and-no-startup-work);
- `connex.ai.eval.enabled` joins `POSTURE_KEYS`, so the startup posture line names it, and joins `SAAS_FORBIDDEN_KEYS`, so a multi-tenant SaaS instance refuses it outright. It deliberately does **not** join the silo or on-prem forbidden lists: staging declares `silo`, and forbidding the flag there would make the runner unrunnable on the only instance it is for. The maintenance-mode and non-web refusals, not the forbidden-key scan, are what keep it out of a serving process under those editions — and in a customer deployment the flag switches on nothing at all, because the runner is not in the artifact. That asymmetry is a decision the gate below must confirm, not a detail to discover during implementation.

So the design does require a validator change, one new maintenance mode, and one new posture/forbidden key, and the tracked issue owns all three.

#### It is not a `seedData` variant, and it cannot use the test classpath either

The runner copies the volume seeder's *posture* — operator-launched, one-shot, non-web, profile plus flag (see [`VOLUME_SEEDER.md`](VOLUME_SEEDER.md)) — and nothing else. Every further condition `SeederStartupConfigurationValidator.validateActivated` enforces is one this runner cannot meet: `seeder` must be the **only** active profile, `connex.maintenance.mode` must be `seeder`, `connex.deployment.profile` must be **unset**, and `SeederGuard` refuses a protected Connex catalog outright. The eval runner runs beside staging's declared edition against staging's own catalog. Reusing the seeder's launcher would mean weakening those checks, which is not on offer.

The classpath question follows from where the corpus lives, and it has exactly one answer. `seedData` runs on `sourceSets.main.output + configurations.productionRuntimeClasspath`, which carries no corpus at all, because the corpus and the category scorers are on the test source set. But `sourceSets.test.runtimeClasspath` is not available to a process deployed beside staging either: it carries `src/test/resources/application.properties`, which declares `spring.profiles.active=test` and hard-codes a test SSO key, mail key, secret-store master key and audit-integrity secret beside a set of operational overrides. A staging process holding those no longer inherits staging's posture — it answers with a test default where it should have refused for a missing staging setting, and it does so silently.

**So the runner, its corpus and its scorers live in a source set of their own**: one that compiles against `main`, takes neither `test`'s classpath nor `test`'s resources, and is not assembled into `bootWar`. That is a constraint on the build before it is a constraint on the runner, and the tracked issue owns it.

#### That source set, and not an edition check, is what makes this staging-only

**Edition selection does not identify staging.** [`DEPLOYMENT.md`](DEPLOYMENT.md) is explicit that one bundle serves a Connex-operated `silo` and a customer-operated `on-prem` install: those two *are* the customer-facing profiles. Any rule phrased as "permitted under `silo` and `on-prem`" is therefore a rule a customer operator can satisfy in full — the non-web launch, the profile, the flag, and a marker row they can insert with access to their own database. A design that leant on the edition would ship this runner, and its egress, into customer deployments.

The admission is physical instead: **a customer deployment receives the shipped artifact, and the shipped artifact does not contain the runner.** No source set, no runner class, no corpus — nothing for a profile or a flag to switch on. Staging is the only place the runner can exist, because staging is the only place built from source. Everything in the validator section above is therefore **defence in depth on the one instance that does have the source set**; it is not the admission, and no part of this design may be read as if it were.

### The synthetic-workspace refusal

**The runner must refuse to run unless its target workspace is marked synthetic by a marker a tenant user cannot set.** This is the load-bearing control: everything else about the design assumes the records the turn reads are invented.

An earlier sketch proposed keying on the organization slug. **That does not hold, and a future implementer should not reach for it.** `WorkspaceService.generateSlug` derives the organization slug from the member-supplied workspace name at self-service registration (lower-cased, punctuation collapsed, plus a random suffix), so a registrant who names a workspace "Synthetic Eval" gets a slug beginning `synthetic-eval-`. A tenant-settable string is not a marker.

**A workspace-scoped marker does not bound the blast radius, because the resources a run consumes are organization-scoped.** The provider row and its credential resolve through `AiProviderConfigService.isReadyForOrg(int orgId)` / `resolveForOrg(int orgId, int actorId)`, the spend is reserved through `AiOrganizationBudgetCoordinator.reserve(int orgId, …)`, and the disclosure mode and egress destination are the organization's. A synthetic workspace created inside a real customer organization — self-service workspace creation is on at staging — would satisfy a workspace-only marker while spending that customer's budget, using that customer's credential and egressing to that customer's configured destination under that customer's own attestation, with nobody at that customer having agreed to an evaluation run. The marker must therefore bind the **organization**.

The marker must be:

- **an organization-level admission, enforced over every workspace in it** — the marker row names the organization id *and* the workspace id, and the runner refuses unless the target workspace is marked, its organization is marked, and the organization holds **no** workspace without a marker row. That last clause is what stops a marked workspace from riding inside a real tenant;
- **a control-plane row with no API surface** — a small table beside the other operator-owned control-plane tables (`organization` and `workspace` are already control-plane, as are `tenant_operation_lease` and friends). The **migration creates the table and nothing else**: migrations run on every edition, so a migration that inserted marker rows would ship a synthetic-workspace admission into production SaaS, silo and on-prem instances. Rows are inserted per environment by an operator with database access, and by nothing else;
- **read-only to every tenant path** — no controller, no mapper statement reachable from a tenant request, no DTO field. If a workspace admin can flip it, it is not a marker;
- **registered where the repo's guards require**, which a new workspace-keyed control table is not allowed to skip: its mapper namespace in `TenantScopeInterceptor.CONTROL_PLANE_NAMESPACES` (`TenantRegistryCompletenessArchTest` fails the build otherwise), the table in `TablePlaneRegistry.CONTROL_PLANE_TABLES`, and exactly one lifecycle disposition — `CONTROL_PLANE_WORKSPACE_STATE_TABLES` or a `ControlWorkspaceLifecycleRegistry` declaration — so `TablePlaneArchTest.everyBaseTableIsClassifiedInExactlyOnePlane` and `everyDirectWorkspaceKeyedControlTableHasAnExplicitLifecycleDisposition` pass and tenant teardown, export and residual verification cover it. [`backend/MIGRATIONS.md`](backend/MIGRATIONS.md) states the same obligation, and [`MULTITENANCY_PLAN.md`](MULTITENANCY_PLAN.md) is authoritative. The teardown and export disposition is part of the acceptance criteria the [decision gate](#decision-gate) asks for, not an implementation detail;
- **checked before anything else**, and fail-closed: an absent row, an unreadable table, an ambiguous match, or a marker row whose workspace or organization no longer exists refuses the run. A dangling row is a stale admission, not a permissive one. The refusal names the workspace and the marker, so the operator's next action is obvious.

A second, independent condition should sit beside it rather than replacing it: the operator supplies the workspace id explicitly on the command line, so a marked workspace is necessary but not sufficient. Neither condition alone is allowed to start a run.

#### A marker is a label, not provenance

**The runner owns the data lifecycle of the organization it runs against.** A marker row records that an operator designated this organization once. It says nothing about what has been written to it since, and the organization the marker names persists between runs: self-service workspace creation is on at staging, an import or a connected capture writes records through ordinary tenant paths, and any member of the organization can create one by hand. A later run then passes the marker check and the all-workspaces-marked check unchanged and sends whatever has accumulated to the real provider.

So the seeding is the runner's job, not an operator's. It **refuses unless every record the marked organization holds was seeded by an evaluation run**, seeds what its cases need at the start of the run, and tears that data down at the end. How seed provenance is carried is not settled here — see [Open problems](#open-problems) — but no run may start without it, because it is the only thing that separates a synthetic organization from an organization somebody has since put real data in.

**An organization whose attestation resolves `UNMASKED` deserves its own sentence.** `privacyModeForOrg` yields `UNMASKED` only while a current attestation names the destination, and in that mode the values in the prompt are the record's own rather than placeholders. There the marker is the only thing between a real record and the provider: a run against an `UNMASKED` organization that holds anything unseeded is not a degraded run, it is a disclosure.

**The runner never sends real tenant data to a provider** is the invariant all of this buys. It is not achieved by filtering or redacting what the runner reads — that would be a second masking implementation, and a second implementation of a control is a second thing to get wrong. It is achieved by refusing to read anything that is not already synthetic, by refusing an organization that holds anything else, and by owning what "synthetic" means rather than trusting a label.

### Through the pipeline, never around it

The runner starts turns the same way the product does: `AiAssistantTurnService.start`, under the synthetic member's own authentication and tenant context, on the real generation worker. It calls no provider, no adapter, and no `AiInvocationService` method directly.

That is not a stylistic preference. Entering at the turn service is what makes every one of these run unchanged and un-bypassed:

- masking and the `OutboundLeakScan` server-envelope assertion before every send;
- `AiFeatureGate.isAiUsable`, workspace governance, and the organization budget reservation and settlement;
- `AiInvocationAdmissionService` admission and the restriction-epoch fence at egress;
- the `ai.llm.call` audit row on every model call, and the write-tool audit row on every write;
- the citation registry, skill authority, the context floor, the special-care screen, and the no-progress guard.

A runner that reached under the turn service to "save a step" would be measuring a pipeline nobody ships. And the runner never disables a control to get a score: a control refusal is recorded as what it is — see [Scoring](#scoring) for why it is recorded *unscored* rather than as a failing case, and when it stops the run instead.

### Scoring

**The live corpus has to be written, and `assistant-evaluation.json` is not it.** Of its twenty-eight cases, the ten `FACTUALITY` / `CITATION_CORRECTNESS` / `TOOL_SELECTION` / `REFUSAL` / `INJECTION_RESISTANCE` cases carry a prewritten `candidate` object and **no** `request` — there is nothing in them to send a model. The eighteen that do carry a `request` are all `SKILL_ROUTING`, and `AiAssistantEvaluationRegressionTest.evaluate` hands those straight to `skillRouter.route(...)`, the deterministic server-side router, before any candidate is read. A runner that reused the file as it stands would either be unable to start its model-facing cases or would report fixture text and server routing as a live-model score. So the live corpus is authored: an executable request per case, a binding to the records that run seeded, and scoring over the turn's actual terminal output. It is an acceptance criterion, not a reuse.

What *is* reused is the scoring vocabulary. Keep the tolerant category assertions — `FACTUALITY`, `CITATION_CORRECTNESS`, `TOOL_SELECTION`, `REFUSAL`, `INJECTION_RESISTANCE`, `SKILL_ROUTING` — and **do not score exact step equality against a scripted golden**: a real model legitimately takes a different, correct route to the same answer, and an exact-match score would report a green model as a regression while pushing a future author toward cases that describe one model's habits. Keep the `loadedToolsets` convention too — a case naming a non-core tool without declaring the toolsets the turn would have had to load fails the guard exactly as a live turn would.

**A case is scored only with evidence that something reached the provider.** The controls in front of egress all run inside `ProviderAttemptTracker.execute` *before* the deferred supplier — the restriction-epoch fence, `AiFeatureGate.requireAiUsable`, the provider guard, the admission commitment — and a refusal there writes an `ai.llm.call` row with outcome `blocked`. No model behaviour was observed, so there is nothing to score: the case is recorded **unscored**, naming the control that refused it. (The `attempt` row is written before that seam and is not evidence of dispatch; a `success` row is.) Scoring a refusal as a failure is not the conservative choice but the misleading one: the budget is organization-scoped and cumulative, so exhausting it partway through a corpus would turn every remaining case into a control refusal and make the aggregate a function of case order rather than a measurement of the model. **Losing a run-wide prerequisite stops the run** — budget exhaustion, a provider that no longer resolves or is no longer ready — instead of producing a tail of zeroes.

**Each score is bound to the provider snapshot its own calls used.** `resolveForOrg` runs per invocation, and the only fence is `requireCurrentProviderSnapshot`, which compares one invocation's snapshot at egress against the snapshot resolved for that same invocation. Nothing holds a snapshot across steps, let alone across cases, so an administrator changing the provider row mid-run legitimately gives one run two models, two destinations or two disclosure modes, and a single run-level provider/model/disclosure line would misattribute part of the score. The report therefore carries the snapshot **per case**, read from the calls that case actually made; a case whose calls did not all share one snapshot is **invalid** rather than averaged. The disclosure mode follows the same rule: what the turn ran under is what the report states, not what the organization resolves to when the report is written.

### Report

A single JSON document written to a path the operator names, containing: the run's start and end, its run id, the operator's identity, the target workspace and the marker row that admitted it, and one entry per case with its category, its terminal reason, its score or its unscored/invalid status, the provider snapshot its own calls used, the durable tool sequence, the turn id, and — for a refusal — the control that refused. Turn ids matter more than prose: they let an operator open `ai_chat_turn`, `ai_chat_tool_call` and `audit_log` for a disputed case instead of arguing with a summary.

The report carries **no model text and no record values**, only ids, tool names, terminal reasons and scores. A report file is the thing that gets pasted into an issue.

### Cleanup, and what cleanup cannot reach

**Every AUTO write the runner causes is undone**, through `AiAssistantWriteToolService.undo` on the same tool-call row, under the synthetic member's own identity. Four constraints on that, all in the service:

- `undo` accepts **AUTO** tier only. CONFIRM tools (`change_deal_stage`, `assign_owner`) execute nothing unless approved, but declining to approve is not a terminal state — see the next commitment.
- The undo window is **ten minutes** from the write. The runner must undo each case as it settles, not in a sweep at the end of a long run — a batch cleanup after a thirty-case run would find its first writes expired.
- The inverse is fingerprint-guarded: `undo` deletes the created activity, task or note only while it still matches the state the write recorded. A record edited in between refuses, which is correct and must be reported rather than forced.
- **`add_tag` has no inverse.** `undo` refuses it outright. The runner must report an un-reverted `add_tag` as a residue on the run rather than claiming a clean exit, and a case whose expected path adds a tag should not be in the live corpus in the first place.

**Every CONFIRM proposal is rejected as its case settles.** "The runner never approves a proposal" is not enough, because a CONFIRM tool has already written a durable `proposed` tool-call row by the time the model's turn ends, and the only decision that makes that row terminal without executing it is `AiAssistantWriteToolService.reject`. The session stays active and the synthetic member keeps exactly the authority `lockAuthorizedToolCall` checks — their own session, their own tool call — so an un-rejected proposal is a mutation that can still be applied by hand long after the report called the case clean. A proposal left `proposed` is a delayed mutation, not a clean exit, and reconciliation must clear the ones an interrupted run left behind just as deliberately as it undoes AUTO writes.

**Every tool call and proposal a run causes carries that run's provenance, and reconciliation touches nothing else.** A scan for "EXECUTED AUTO writes in this organization whose `undo.status` is still `available`" cannot tell crash residue from a write that another evaluation run — or an ordinary session in the same synthetic organization — made in the last ten minutes, and it would reverse that write whenever the evaluator identity can reach the session. Rows belonging to a different member's private session fail `lockAuthorizedToolCall` instead and would be reported as unrecoverable residue when they are simply somebody else's row. So each run stamps the tool calls it causes with its own run id, and reconciliation reverses and rejects rows carrying evaluation provenance from a prior run of this runner, and nothing else. Where that stamp lives is [an open problem](#open-problems), not a detail.

**A run that dies between a write and its undo leaves that write durable, and nothing reverses it unless the next launch does.** A killed JVM — a deploy, an operator interrupt, the host's own memory killer — is the ordinary case, not the exotic one. What a crash actually costs is time: `undo` refuses once the ten-minute window has passed. The other two checks it makes still pass after a crash — nothing archives a chat session automatically, so the session stays `active`, and the runner authenticates again as the same synthetic member, so the locked-membership `AI_USE` check is satisfied. A write is therefore recoverable only by a run that starts inside the window. So the design needs a startup step, not a hope:

- **Reconcile before scoring anything.** On launch, before the first case, the runner reverses every EXECUTED AUTO write **carrying a prior evaluation run's provenance** whose `undo.status` is still `available`, and rejects every `proposed` tool call carrying the same provenance, through the same service under the same identity. What it settled and what it could not, it reports.
- **State what happens past the window.** A row outside the ten-minute window is not undoable by any code path the product owns; the runner does not reach around the service to delete it. It is listed in the report as residue, with its tool-call id and workspace, for an operator to clear by hand.

The honest statement of the cleanup guarantee is therefore: *the runner reverses every AUTO write it can and rejects every proposal it made, immediately; reconciles what a previous run of its own left behind; and names every row it could not settle.*

**Teardown is the runner's, not a runbook's.** Because the runner seeds the organization's data itself (see [A marker is a label, not provenance](#a-marker-is-a-label-not-provenance)), it disposes of it too: the run ends by removing what it seeded, and the next run starts from an organization holding nothing else or refuses. Residue the service cannot reverse is reported and blocks the next run until an operator clears it — a stale write is not a thing to seed on top of.

### What it must refuse

Fail closed and stop, rather than adapting:

- an unmarked target workspace, or a marker row the runner could not read;
- a target organization with no enabled provider row, or one whose adapter cannot completely resolve its configuration;
- the `ai-scripted-provider` profile or flag being present;
- a target organization holding any workspace that is not itself marked — the second read that catches a marker pointed at a workspace inside a real tenant;
- a target organization holding any record an evaluation run did not seed, and any residue a previous run could not settle;
- a database schema that does not match the release the runner was launched from;
- any attempt to write the report anywhere a tenant request can serve it.

**Why that second read is an organization scan and not an ownership check.** An earlier sketch refused "a target workspace holding any record whose owner is not the synthetic member". That is not expressible against the schema: `owner_id` exists only on `deal`, `company` and `person` and is nullable on all three, while activities, tasks, notes and tags — everything the AUTO write tools create — carry no owner column at all. Read literally, the check refuses every candidate and the runner never starts; relaxed to "null or the synthetic member", it admits a bulk-imported customer workspace, which is exactly the case it was written to catch.

The pre-context startup refusals — the `ai-eval` profile in a web application, the profile with the maintenance mode `off`, the profile without the flag, the flag without the profile, and the flag under SaaS — are deliberately absent from this list, because they are not the runner's to make; see [the validator section](#the-activation-refusals-belong-in-the-validator-not-in-the-runner). By the time the runner has a bean, they have already passed.

### Open problems

Two things this design cannot settle on its own. Each needs a ruling before the runner is built, and neither may be answered by an implementer discovering it mid-change.

- **Nothing freezes the marked organization for the duration of a run.** The runner can refuse an organization that already holds something it did not seed, but the product has no per-organization write freeze to switch on, so a member, an import or a connected capture can write to it while cases are executing — after the admission check has passed. Either the runbook owns it (nobody else holds credentials into that organization, and connected capture is never enabled there), or the tracked issue specifies a mechanism. Writing "the marked organization receives no other ingress" without one of those two is a wish.
- **Nothing in the schema records who created a record or a tool call.** Both the seed-provenance refusal and provenance-scoped reconciliation depend on it, and the AUTO write tools create activities, tasks, notes and tags that carry no evaluation marking at all. Whether that becomes a column, a control-plane side table, or a convention over ids the runner allocates is a product decision with a migration attached — and, given that the marker table is already one migration, the two should be decided together.

### Decision gate

**Building this means real provider egress from staging, each time an operator chooses to run it and never on a timer, spending an organization's budget.** The design above is inside today's product posture — data the runner seeded and disposes of, a marker no tenant can set, nothing bypassed, every AUTO write reversed and every proposal rejected — which is why it can be written down without a ruling. Building it is a different decision.

Before any code:

- it needs **its own tracked issue**, owning the [open problems](#open-problems) above and carrying these as acceptance criteria:
  - the marker table's shape and its plane/lifecycle registrations;
  - the validator refusals, the new `ai-eval` maintenance mode, and the one new posture key — including the deliberate asymmetry that forbids the flag under SaaS but not under the edition staging declares;
  - the isolated source set, and evidence that the shipped artifact contains neither the runner nor its corpus;
  - launching from `.staging/deployed-sha` with Flyway disabled, and the schema check that refuses a mismatch;
  - the live corpus: executable requests, bindings to the records the run seeds, and scoring over the turn's own terminal output;
  - seed provenance, run provenance, and the seed/teardown steps that make the synthetic-organization refusal enforceable;
  - unscored pre-egress refusals, the run-wide prerequisites that stop a run, and the per-case provider snapshot;
  - reconciliation of both AUTO writes and pending proposals from an interrupted run, scoped by provenance;
  - the staging runbook entry: who launches it, against which organization, and what they do with reported residue;
- it needs **founder approval** for the egress itself;
- it needs the review a Tier 3 change gets: a security review for the egress and the marker, and a second reviewer for correctness and cleanup.

Nothing in this document authorizes a migration, a flag, a profile, or a line of Java.

## Related

- [`backend/AI_SECURITY.md`](backend/AI_SECURITY.md) — the authoritative AI gate, masking, adapter and scripted-seam contract.
- [`backend/MIGRATIONS.md`](backend/MIGRATIONS.md) — where a marker table would have to live, and how its version is allocated.
- [`STAGING_DEPLOY.md`](STAGING_DEPLOY.md) — the staging instance, its declared edition, and how it is deployed.
- [`VOLUME_SEEDER.md`](VOLUME_SEEDER.md) — the operator-invoked one-shot process this runner's shape copies.
- [`SPECIAL_CARE_DATA_POLICY.md`](SPECIAL_CARE_DATA_POLICY.md) — the screen two goldens pin, and the reason a live corpus stays synthetic.
