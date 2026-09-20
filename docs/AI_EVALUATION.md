# Ask Connex Agent Evaluation

Two things live here, and keeping them apart is the point of the document.

1. **The map of the evaluation machinery that exists today** — the scripted provider, the trajectory harness, and the twenty goldens that run in CI on every backend change. This half is verified against the code and is safe to act on.
2. **The design of a live-evaluation runner that does not exist** — an operator-invoked process that replays the same requests against a *real* model provider on staging, with synthetic data only. Nothing in that half is built, and building it is decision-gated: see [Decision gate](#decision-gate).

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
| Data it reads | fixtures it seeded and deletes | a workspace marked synthetic |
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
- **The durable-partial purge under `restrictions_changed`.** The purge only has something to purge on a streamed turn whose partial is already durable, so the epoch would have to advance between the stream's last batch and the terminal write. The step interceptor fires *before* the emission, so the very step it arms is the step the fence refuses and no partial is ever written. Reaching it needs a post-emission hook. `AiChatTurnPersistenceServiceTest` covers it at the service layer; the end-to-end gap is a tracked residual.
- **Streamed delta ordering.** `AiAssistantTextDeltaProjector` confirms a `NATIVE_FINAL` answer only once the whole final object parses, so however many fragments the provider writes, the batcher is handed the answer once. Ordering and the projector's half-placeholder withholding have no observable effect on a native streamed trajectory, and the streamed golden deliberately claims neither.

The live runner does not close these either. It changes who writes the model's words; it does not add a seam.

## Live evaluation runner — design only

**Nothing below is built.** This section exists so that the thing, if it is ever built, is built this way.

### What it is for

The harness answers "does the server do the right thing with this model output?". It cannot answer "does a real model produce output the server can work with?" — tool-name accuracy, citation discipline, refusal behaviour under an injected tool result, whether the closing directive is honoured, whether a repair round trip converges. Those are properties of a model against this prompt envelope, and they change when the model changes, when the envelope changes, and when a provider silently updates a snapshot behind a model id. Only a real call measures them.

### Invocation

**An operator launches it, by hand, and nothing else can.** Concretely:

- A one-shot non-web process — `spring.main.web-application-type=none` — launched from the staging checkout, in the shape `seedData` already uses for the volume seeder (see [`VOLUME_SEEDER.md`](VOLUME_SEEDER.md)). A dedicated `ai-eval` Spring profile gates the runner bean, and a `connex.ai.eval.enabled=true` flag gates it a second time, exactly as the scripted seam pairs a profile with a flag.
- **No controller, no endpoint, no job queue, no `@Scheduled` trigger, and no automation-rule action.** There must be no tenant-reachable path to it and no unattended path to it. A scheduled evaluator is a standing authorization to spend an organization's budget and egress to a provider with nobody watching, and that is not a thing the product should be able to do by accident.
- It runs under the **same** `CONNEX_DEPLOYMENT_PROFILE` the staging instance declares, so it inherits the edition's posture rather than escaping it, and it needs no new forbidden key and no validator change.
- It refuses to start if `ai-scripted-provider` is active or its flag is set. Under a declared edition that combination already refuses; the runner's own refusal is the message an operator reads when they have mixed the two up.

### The synthetic-workspace refusal

**The runner must refuse to run unless its target workspace is marked synthetic by a marker a tenant user cannot set.** This is the load-bearing control: everything else about the design assumes the records the turn reads are invented.

An earlier sketch proposed keying on the organization slug. **That does not hold, and a future implementer should not reach for it.** `WorkspaceService.generateSlug` derives the organization slug from the member-supplied workspace name at self-service registration (lower-cased, punctuation collapsed, plus a random suffix), so a registrant who names a workspace "Synthetic Eval" gets a slug beginning `synthetic-eval-`. A tenant-settable string is not a marker.

The marker must therefore be:

- **a control-plane row with no API surface** — a small table beside the other operator-owned control-plane tables (`organization` and `workspace` are already control-plane, as are `tenant_operation_lease` and friends), naming the workspace ids the runner may target, written by a migration or by an operator with database access and by nothing else;
- **read-only to every tenant path** — no controller, no mapper statement reachable from a tenant request, no DTO field. If a workspace admin can flip it, it is not a marker;
- **checked before anything else**, and fail-closed: an absent row, an unreadable table, or an ambiguous match refuses the run. The refusal names the workspace and the marker, so the operator's next action is obvious.

A second, independent condition should sit beside it rather than replacing it: the operator supplies the workspace id explicitly on the command line, so a marked workspace is necessary but not sufficient. Neither condition alone is allowed to start a run.

**The runner never sends real tenant data to a provider** is the invariant this buys. It is not achieved by filtering or redacting what the runner reads — that would be a second masking implementation, and a second implementation of a control is a second thing to get wrong. It is achieved by refusing to read anything that is not already synthetic.

### Through the pipeline, never around it

The runner starts turns the same way the product does: `AiAssistantTurnService.start`, under the synthetic member's own authentication and tenant context, on the real generation worker. It calls no provider, no adapter, and no `AiInvocationService` method directly.

That is not a stylistic preference. Entering at the turn service is what makes every one of these run unchanged and un-bypassed:

- masking and the `OutboundLeakScan` server-envelope assertion before every send;
- `AiFeatureGate.isAiUsable`, workspace governance, and the organization budget reservation and settlement;
- `AiInvocationAdmissionService` admission and the restriction-epoch fence at egress;
- the `ai.llm.call` audit row on every model call, and the write-tool audit row on every write;
- the citation registry, skill authority, the context floor, the special-care screen, and the no-progress guard.

A runner that reached under the turn service to "save a step" would be measuring a pipeline nobody ships. If a control refuses the run, that refusal **is** the result: the report records it and the run continues to the next case rather than the runner disabling anything to get a score.

### Scoring

Score with the tolerant category assertions `AiAssistantEvaluationRegressionTest` already defines — `FACTUALITY`, `CITATION_CORRECTNESS`, `TOOL_SELECTION`, `REFUSAL`, `INJECTION_RESISTANCE`, `SKILL_ROUTING` — reading the same case set from `backend/src/test/resources/ai/assistant-evaluation.json`. **Do not score exact step equality against a scripted golden.** A real model legitimately takes a different, correct route to the same answer; an exact-match score would report a green model as a regression and would push a future author toward fixtures that describe one model's habits.

The same file's `loadedToolsets` convention already encodes the core-toolset rule: a case whose candidate names a non-core tool without declaring the toolsets the turn would have had to load fails the guard, exactly as a live turn would. A live runner reusing this set inherits that for free.

Two figures belong in the report beside the score, because they change what the score means: the resolved provider and model id, and the resolved disclosure mode for the organization (`MASKED` unless a current attestation for the exact destination says otherwise). A demasking assertion means something different in each.

### Report

A single JSON document written to a path the operator names, containing: the run's start and end, the operator's identity, the target workspace and the marker row that admitted it, the resolved provider/model/disclosure, and one entry per case with its category, its terminal reason, its score, the durable tool sequence, the turn id, and — for a refusal — the control that refused. Turn ids matter more than prose: they let an operator open `ai_chat_turn`, `ai_chat_tool_call` and `audit_log` for a disputed case instead of arguing with a summary.

The report carries **no model text and no record values**, only ids, tool names, terminal reasons and scores. A report file is the thing that gets pasted into an issue.

### Cleanup, and what cleanup cannot reach

**Every AUTO write the runner causes is undone**, through `AiAssistantWriteToolService.undo` on the same tool-call row, under the synthetic member's own identity. Four constraints on that, all in the service:

- `undo` accepts **AUTO** tier only. CONFIRM tools (`change_deal_stage`, `assign_owner`) are proposals that execute nothing unless approved, so the rule is simpler: **the runner never approves a proposal.** A proposal left in `proposed` has changed nothing.
- The undo window is **ten minutes** from the write. The runner must undo each case as it settles, not in a sweep at the end of a long run — a batch cleanup after a thirty-case run would find its first writes expired.
- The inverse is fingerprint-guarded: `undo` deletes the created activity, task or note only while it still matches the state the write recorded. A record edited in between refuses, which is correct and must be reported rather than forced.
- **`add_tag` has no inverse.** `undo` refuses it outright. The runner must report an un-reverted `add_tag` as a residue on the run rather than claiming a clean exit, and a case whose expected path adds a tag should not be in the live corpus in the first place.

The honest statement of the cleanup guarantee is therefore: *the runner reverses every AUTO write it can, immediately, and names every one it could not.* The disposable synthetic workspace — not the undo path — is what makes a residue survivable.

### What it must refuse

Fail closed and stop, rather than adapting:

- an unmarked target workspace, or a marker row the runner could not read;
- a target organization with no enabled provider row, or one whose adapter cannot completely resolve its configuration;
- the `ai-scripted-provider` profile or flag being present;
- a `connex.ai.eval.*` flag set without the `ai-eval` profile, or the profile without the flag;
- a target workspace holding any record whose owner is not the synthetic member — a cheap second read that catches a marker pointed at the wrong workspace;
- any attempt to write the report anywhere a tenant request can serve it.

### Decision gate

**Building this means real provider egress from staging, on a schedule an operator chooses, spending an organization's budget.** The design above is inside today's product posture — synthetic data only, a marker no tenant can set, nothing bypassed, every AUTO write reversed — which is why it can be written down without a ruling. Building it is a different decision.

Before any code:

- it needs **its own tracked issue**, with the marker table's shape, the corpus, and the staging runbook entry as acceptance criteria;
- it needs **founder approval** for the egress itself;
- it needs the review a Tier 3 change gets: a security review for the egress and the marker, and a second reviewer for correctness and cleanup.

Nothing in this document authorizes a migration, a flag, a profile, or a line of Java.

## Related

- [`backend/AI_SECURITY.md`](backend/AI_SECURITY.md) — the authoritative AI gate, masking, adapter and scripted-seam contract.
- [`backend/MIGRATIONS.md`](backend/MIGRATIONS.md) — where a marker table would have to live, and how its version is allocated.
- [`STAGING_DEPLOY.md`](STAGING_DEPLOY.md) — the staging instance, its declared edition, and how it is deployed.
- [`VOLUME_SEEDER.md`](VOLUME_SEEDER.md) — the operator-invoked one-shot process this runner's shape copies.
- [`SPECIAL_CARE_DATA_POLICY.md`](SPECIAL_CARE_DATA_POLICY.md) — the screen two goldens pin, and the reason a live corpus stays synthetic.
