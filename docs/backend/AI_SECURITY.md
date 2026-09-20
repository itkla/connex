# AI Security and Provider-Egress Contract

This document is authoritative for Connex's customer-BYOP AI boundary. Read it before changing AI gates, prompt construction, masking, media admission, provider adapters, endpoint validation, budgets, streaming, assistant tools, or collaborative transcripts.

## Fail-closed enablement

AI is disabled unless every applicable gate passes:

- the deployment master switch;
- the feature-specific switch;
- the actor's `Permission.AI_USE`;
- an enabled organization provider configuration;
- the provider adapter supports and can completely resolve the configuration;
- the organization has made the required no-training/ZDR attestation for the exact destination where applicable;
- credentials exist when the selected provider requires them;
- workspace governance, organization budget, and invocation admission permit the call.

`connex.ai.enabled` defaults false. A feature switch cannot override the master switch. Deterministic Connex features continue to function when AI is unavailable; callers receive an explicit unavailable/terminal result rather than a hidden fallback that changes semantics.

Ask Connex (`ASSISTANT_CHAT`) additionally requires a model whose context window is at least `AiAssistantPromptBudget.ASSISTANT_MIN_CONTEXT_TOKENS` (65,536 tokens). A smaller model produces an honest per-turn `context_window_too_small` refusal — asserted at budget derivation and re-asserted before every model step, so a mid-turn provider change to a smaller model refuses identically instead of degrading. Other AI features assemble far smaller envelopes and keep serving smaller models unchanged (the settled decision and rationale live on issue #1420).

Provider credentials remain envelope-encrypted with the existing AI-provider secret purpose. Provider configuration changes remain organization-admin plus recent-authentication/step-up gated.

## Invocation choke point

Every model call goes through `AiInvocationService`. Do not call `AiProvider`, a provider client, or a raw HTTP client directly from a feature.

The invocation boundary owns:

- feature/permission/provider/gov/budget admission;
- credential resolution;
- masking/outbound leak screening;
- provider routing and bounded transport;
- demasking/screening of the final result;
- metadata-only `ai.llm.call` audit for attempt/success/failure/blocked outcomes;
- media admission leases when images are embedded;
- hard wall-clock cancellation in addition to socket/inactivity timeouts.

Prompts, responses, credentials, masked-token maps, source content, and raw provider payloads never enter logs or audit rows.

## Masking and prompt assembly

Features do not build raw prompts.

- Tokenize person/company identifiers through `MaskingEngine.maskField`.
- Route free text through `MaskingEngine.maskFreeText` so special-care screening and identifier substitution are applied.
- Assemble prompts through `PromptAssembly`.
- Keep `MaskedPrompt` construction confined to the masking package; do not widen its constructor/bypass path.
- Email addresses, phone numbers, postal addresses, and other fields excluded by `IdentifierPolicy` do not enter ordinary text prompts.
- `special_care` text is excluded.
- The request-scoped masking map is never persisted. Masking reduces provider exposure but does not change Connex's APPI role because Connex can re-identify the tokens.

Any new feature must preserve the same masking and leak-scan boundary for every provider/protocol path.

## Image/media exception

Pixels cannot be masked like text. Image-powered AI is an explicit policy exception and must use `AiInputImage` plus all of the following:

- conservative byte/dimension/decoded-memory admission;
- a static prompt assembled through `PromptAssembly`;
- `AI_USE` and all provider/no-training gates;
- customer-facing disclosure for the feature;
- metadata-only audit;
- review-only output rather than silent autonomous mutation;
- direct embedded bytes, never a provider-fetchable URL.

Hold the established global, per-organization, and estimated-memory media leases through provider parsing.

Business-card image fallback has additional rules in `docs/backend/BUSINESS_CARD_SCANNING.md`.

## Provider egress

Provider requests are bounded, redirect-free, and destination-validated immediately before every send.

- Bedrock destinations derive from the closed reviewed region model.
- Azure OpenAI uses reconstructed HTTPS URLs on approved Azure OpenAI hosts.
- Vertex uses the exact supported model/location combinations and the established Google authentication path.
- OpenAI-compatible endpoints resolve through `AiEndpointAddressValidator` before every send and honor the organization-controlled internal-endpoint posture.
- Fixed-provider clients pin the resolved final address and share one monotonic deadline across resolution/auth/model/provider phases where the adapter requires it.
- Redirects remain disabled.
- NAT64/network-specific translation prefixes must be explicitly configured so translated addresses are classified correctly; ambiguous organization-controlled IPv6 that could target blocked IPv4 fails closed.
- A new provider is implemented behind `AiProvider`, registered through `AiProviderRouter`, and receives the same gate/masking/audit/deadline/address-validation treatment.

Do not add remote-image fetching or place provider I/O inside a database transaction.

## Unmasked disclosure and streaming

`UNMASKED` disclosure is durable fail-closed posture. It may resolve only when the deployment permits it and the exact resolved destination has a current organization-admin attestation. Destination changes invalidate the attestation, and the snapshot is rechecked at provider egress.

Streaming remains inside `AiInvocationService`:

- publish only decoded terminal answer text to user-facing streams;
- persist replay data through the existing exact offset/batch protocol;
- replace durable partial output with screened terminal content in the locked completion path;
- keep cancellation authorization and terminal state database-backed;
- treat JVM transport-abort hooks as an optimization, not the source of truth.

## Assistant tools and collaboration

Native assistant tools are capability-declared transport choices, not a second authorization model.

- Tool definitions project only the static `AiAssistantToolCatalog`, never tenant/customer data.
- **Every declared tool names one `AiAssistantToolCatalog.Toolset`.** `CORE` — record, activity, task, and plan reads, including `list_scope_activities`, `list_tasks` and the `find_tools` meta-tool — is the toolset a turn always holds; the rest group by object family (`analytics`, `schedule`, `write_activity`, `write_content`, `write_pipeline`) so one family can grow without dragging every other write tool along. The keys are stable wire vocabulary: add, never rename. A declaration without a toolset is refused in the `ToolSpec` constructor, because every catalog view filters on it.
- **A turn starts from `CORE` and widens itself with `find_tools`.** The prompt vocabulary, the strict ReAct step schema, the native function definitions and the raw step guard are all functions of one per-turn `loadedToolsets` set. `find_tools` is a READ-tier core tool whose single argument is a closed enum of the loadable keys (`core` is never legal — it is always held), and the agent loop executes it itself: `AiAssistantToolExecutor` is a stateless tenant-read component, so its closed switch refuses `find_tools` with `find_tools_requires_turn_state` rather than holding per-turn state. `AiAssistantToolsetLoader` resolves the load and returns the **whole** active set, which is why the loaded set is reconstructible from the `ai_chat_tool_call` rows a turn already writes and this needed no migration. The loader does **not** mutate: the loop commits the widening only after `finishTool(..., "executed", ...)` returns true, so a step that settles any other way cannot leave the turn holding a toolset no durable row records. Its `tools` list names only *executable* tools — a reserved declaration such as `get_deal_brief` stays in the rendered vocabulary with its unavailable reason, but advertising it as freshly unlocked would invite a non-recoverable executor refusal that forfeits the step the load cost.
- **`MAX_ACTIVE_TOOLSETS_PER_TURN` is one budget, and the set's own size is its counter.** There is no separate load counter, so toolsets a routed skill seeds and toolsets `find_tools` loads spend from the same allowance. Both over-budget cases are recoverable refusals the model can act on — `toolset_already_loaded`, `toolset_load_limit_reached` — as is `tool_not_loaded`, the loop backstop for a tool name that survived the raw guard. All three settle under `malformed_output`, which is closable, so an escape still spends the closing step.
- **Loading is narrowing; it is never an authorization event.** Every tool in every loadable toolset is already reachable on a routed or generic turn, so a load restores reach the taxonomy removed. `requireSkillAuthority` runs **first** and still owns write authority: a write tool that is both unloaded and outside its skill's declaration audits as `tool_outside_skill_authority`, never as `tool_not_loaded`. `requireToolsetLoaded` is raised from inside the `validateReferences` try so the refusal is recoverable and the turn keeps its closing step, and it runs **before** `recordNativeCall`: the refusal leaves the loaded set alone, so replaying a refused exchange for a name the next step's definitions still exclude would make `AiNativeToolRequest` throw an `IllegalArgumentException` — an internal error in place of the recoverable refusal. Keep both orders.
- **The budget is measured once, from `reservationToolsets()`.** Core plus the `MAX_ACTIVE_TOOLSETS_PER_TURN + RESERVATION_HEADROOM_TOOLSETS` weightiest loadable sets, picked by a pure-catalog weight proxy. Re-deriving the budget when a toolset loads would shrink `toolResultBytes` mid-turn and strand tool results the turn had already admitted and executed writes against, so the reservation is deliberately a bounded worst case a turn may never reach. **Adding a tool to a non-core toolset therefore grows the reservation, not every turn's envelope** — but it can still breach the 64k context floor. `AiAssistantPromptEnvelopeTest` measures the true envelope for every reachable loaded set on both protocols, including core-only, and fails with the numbers printed if the proxy stops dominating them; its per-toolset ceiling is derived from the floor margin the reservation has to fund, as an advance warning for the floor assertion it protects.
- **`find_tools` is exempt from the tool-result cache and publishes no step frame.** The cache is keyed on name plus arguments, so a repeated key would replay a stale `active`/`remainingLoads` and tell the model it holds fewer toolsets than it does; the exemption also keeps exactly one durable row per `find_tools` step. It reads nothing, so it has no coverage category: the loop publishes no `step` frame and `AiChatProgressService.project` skips its rows, which is what keeps the live coverage strip and the reloaded transcript identical. `sourceForTool` stays untouched.
- **The `find_tools` result is server-authored and is replayed verbatim.** It is the authoritative statement of what the turn holds, built entirely from catalog constants. Running it through the tenant-data replacer like any other tool result would let a workspace record named after a toolset key — "Analytics", "Core" — tokenize or redact the `loaded`/`active`/`tools` values, so the model would be told it holds a placeholder it can never name again and would burn its no-progress budget re-asking. `AiAssistantPromptAssembler.maskedToolResult` therefore skips masking for this one tool and fails closed through `AiAssistantToolCatalog.isDeclaredVocabulary` if the result ever carries a string the catalog did not author. The outbound leak scan still passes because those keys are trusted server text the system prompt emits on every step.
- **The model-visible cap is rendered from the constant, never written out.** `AiAssistantToolCatalog.capSentence()` is the single source for both the `find_tools` description and the system-prompt directive, because those are the only two places a model learns the limit and a hard-coded numeral beside a changed constant would quietly contradict the loader.
- **A non-core trajectory costs one extra governance step.** The effective floor is three `assistantMaxSteps` — load, call, close. `assistantMaxSteps` is operator-settable down to 1, so a workspace below that floor reaches no non-core tool at all; that is a declared consequence of the taxonomy rather than a bug, and the turn still answers from what it has instead of failing. Pinned by `AiChatAgentLoopServiceTest`. If a family proves to be on most turns, promote its tool into `CORE` against the envelope ceiling rather than raising this floor.
- Native arguments pass the same assistant step guard as the JSON/ReAct path.
- Tool results reuse the same masked `CRM_DATA` representation, except the server-authored `find_tools` result above.
- A provider without native-tool capability retains the existing ReAct protocol; do not silently change its prompt/request semantics.
- Each tool step executes as the member who authored that turn.
- Shared transcript access requires joined membership in the same workspace.
- Citations are re-authorized and filtered per viewer.
- Realtime fanout resolves current joined recipients after commit; cached/caller-supplied recipient sets are not authorization.
- Presence/typing signals are bounded ephemeral hints and never authorization evidence.

## Skills, declared scope, and bounded bulk reads

Ask Connex routes routine CRM jobs through `AiSkillCatalog` rather than letting the model invent a retrieval plan. The catalog is a declaration, not authority.

- A skill declares its key/version, supported context, retrieval plan, bounds, required server-computed metrics, allowed tools, result-block contract, permissions, budgets, authority level, golden-evaluation gate, and partial-result behaviour. Keys are additive and replay-safe; a reserved key is `DECLARED` with a stable reason rather than given an invented contract.
- Routing is deterministic and server-side. A skill executes only its declared plan, through the same tool executor, domain services, and RBAC as a model-chosen step, and every plan step is persisted as an ordinary durable tool call.
- A skill never gains authority its tools do not have. The declared authority level is a ceiling, not a grant, and it is enforced: after a routed plan runs, a model-proposed WRITE tool ends the turn with `tool_outside_skill_authority` unless the skill both lists it in `allowedTools` and carries an authority above `READ`. Read tools stay available to synthesis — they grant nothing the caller's generic loop would not, and their scope honesty is governed by the declared-scope tool policy. (Refusing reads here originally made every synthesis-time lookup fatal for plan-only skills.) The skill's declared permissions are re-asserted on the generation thread before the plan's first step, because the routing-time check is a pre-lock snapshot.
- Several `SkillSpec` fields are **declarative only** pending the #1335 result-contract follow-up (`bounds`, `requiredMetrics`, `resultBlockKinds`, `coverageSources`, `citationsRequired`, `minimumContextTokens`, `budgets.maxLatencyMillis`, `budgets.maxAnswerBlocks`). Retrieval is bounded by `AiChatScopeBounds` and each plan step's own limits, and the turn by `AiAssistantTurnBudget`. The record's Javadoc states which fields enforce and which only declare; do not read a declaration as a guarantee.
- **The skill catalog never enters the fixed prompt envelope.** On the smallest supported context window that envelope is paid out of the answer's own output-token budget, so only the selected skill's bounded per-turn directive travels with a turn. `AiAssistantPromptEnvelopeTest` guards the remaining budget.
- Declared query scope (`AiChatQueryScopeRequest`) is a **request** filter, validated and authorized server-side and then applied to the retrieval, so the scope the caller was shown and the query the server ran cannot diverge. Owners resolve through active workspace membership, stages through the workspace pipeline catalog, saved views through their own accessibility rules. A saved view whose config contains a facet the server cannot execute, or which carries no server-evaluable segment at all, is refused — at admission *and* again at execution, so a view edited in between fails the read rather than silently widening the cohort. An accepted view binds its own record type into the scope. Facets only a deal cohort can honour (stages, deal statuses) are refused for a non-deal cohort rather than echoed and dropped. Owner selection is presentational per `docs/DEAL_VALUE_CONTRACT.md`; it is not an access boundary.
- A model argument may only **narrow** a declared scope. Warmth bands intersect with the declared bands and refuse when the intersection is empty; a record kind outside the declared kinds is refused rather than substituted. The anchoring page record is server-derived context and fills the cohort kind only where the declaration leaves it open. `AiChatCohortKind` is the single rule, used by both the scope preview and the executed read, so the preview refuses exactly what the retrieval refuses and the confirmed count describes the set the turn reads.
- An argument refusal is **recoverable, not terminal**. When the server refuses model-proposed tool arguments (`AiAssistantLoopException.refusedArguments` — an unknown metric, a warmth filter on a deal cohort, a hallucinated handle), nothing was computed or exposed, so the loop persists the call as failed and returns `{"error": reason}` to the model as a correctable tool result instead of ending the turn. Refusals that no argument change can cure — revoked access, exhausted budgets, a changed saved view, a write outside skill authority — stay terminal. Refused calls never count as progress, so an uncorrecting model lands in the closing step via the no-progress bound rather than looping.
- Durable turn scope (`ai_chat_turn.scope_json`) stores **identifiers only**. Owner, stage, and saved-view labels are re-resolved on read under the reader's own authorization, so an offboarded member is never left named inside a stored turn and a renamed saved view is never restated under its old name.
- `POST /api/ai/assistant/sessions/scope-preview` evaluates a whole smart segment and spends no model tokens, so no generation budget bounds it. It is metered by `AiChatScopePreviewRateLimiter`, a fixed window per workspace and member.
- Bulk reads apply their row, per-record, and date bounds **inside the mapper query**. Materializing history and truncating afterwards is the defect class #1189 exists to prevent. Every bulk result states the interpreted scope, true match counts, applied caps, truncation, freshness, and exclusion categories. It deliberately offers **no** continuation handle: the per-record cap re-partitions rows on every read, so a second page is not the remainder of the first, and any "rows remaining" figure would name rows no follow-up could reach.

## Governance, budgets, and admission

Workspace governance and organization budgets are independent gates.

- Missing workspace governance uses the documented default; an explicitly disabled workspace terminates an in-flight turn before another provider step.
- Organization token budgets reserve conservatively in the control-plane ledger before egress and settle against provider-reported actual usage.
- The database ledger is the cluster coordinator; do not replace it with JVM-local counters.
- Zero retains its documented meaning as unlimited rather than disabled.
- Cache-miss generation uses `AiInvocationAdmissionService`; single-flight losers wait for the leader and re-read persistent cache rather than becoming artificial rate-limit failures.
- Deal briefs, deal-risk rationales, and introduction rationales call `AiInvocationAdmissionService.precheck` before assembling and masking context whenever the request can only end in a provider attempt: a forced refresh, or a request with no stored output row. Deal briefs run it before any CRM load. Deal-risk rationales run it after the deterministic risk assessment (the deal, its stakeholders, their latest touches, and their warmth), because that assessment decides `not_at_risk` and the rationale panel on every deal page hides on `not_at_risk` rather than reporting rate limiting; a refused rationale therefore still pays for the assessment, the same read the deal page's risk panel already performs. Introduction rationales run it before the workspace-wide suggestion ranking, so under an exhausted quota a pair with no stored row reports rate limiting rather than whether it is still suggested. The precheck applies `acquire`'s leader rejection rules without reserving quota, recording a refresh, or registering a flight, and never refuses an identity that has an active flight it may join. A request with a stored row always assembles, because only a fresh assembly can validate the row's content hash, so a valid cache hit is never refused for quota. A non-forced request that found no row and is refused probes the row again before returning the refusal and assembles when one now exists, because a concurrent caller can publish a row and release its flight between the first probe and the precheck, and that caller's completed attempt may be what filled the quota. Generation runs outside any transaction, so each probe reads committed rows in its own MyBatis session rather than a session-cached empty result. `acquire` remains the binding, reserving decision.
- The precheck mirrors only the JVM-local admission quota, refresh throttle, and registry capacity. Refusals decided after assembly (organization budget exhaustion, the outbound leak scan, and the context-window check) release the admission reservation without filling the quota window, so a request that always ends in one of them still pays for its assembly each time; see #1703.
- A generation timeout interrupts its worker. Context assembly calls `AiCancellation.throwIfInterrupted()` immediately after each CRM load on a generation path, so an interrupt during one load stops the next from starting: each deal, summary, stage-history, stakeholder, activity, note, and task load, each person-lookup batch, the company-profile and account-history loads, each stakeholder's employment and connection loads, the warmth rescore, the deal-risk assessment, each introduction-suggestion load, the suggestion ranking, and each introduction endpoint lookup. It also checks per digest item, and masking checks per dictionary identifier while screening free text, so a timed-out generation releases its fixed-pool worker. Only introduction rationales rank suggestions through `IntroductionService.computeCancellableSuggestions`; the request and scheduled-sweep callers of the same ranking never check for cancellation. A single-flight follower's wait for its leader is interruptible too: an interrupted follower stops waiting and leaves the leader's flight untouched. The interrupt status stays set, so a best-effort helper that swallows the exception and reports degraded context is still stopped at the next checkpoint. A single load or rescore is not interruptible once started. Raw text is never truncated before masking to save time; the replacer must keep parity with the outbound leak scan.

## Failure behavior

Keep provider failure, rate limit, timeout, cancellation, blocked, and unavailable states distinct. Do not recover a lost response by issuing a second generation request when the established handle/polling protocol can continue the original operation.

Deterministic warmth, risk, report figures, and other non-AI behavior remain available without AI.

## Review checklist

- Every model call enters through `AiInvocationService`.
- All gates are independent and fail closed.
- Text follows masking + `PromptAssembly`; media follows the explicit `AiInputImage` exception.
- No prompt/response/credential/content is logged or audited.
- Destination is re-resolved/revalidated immediately before egress; redirects are off.
- One monotonic wall-clock deadline bounds the complete provider invocation.
- No provider/network I/O occurs inside database transactions.
- Unmasked/streaming/collaboration behavior preserves durable authorization and screening.
- Budgets/governance/admission are database-coordinated and checked before egress.
- Provider, masking, leak-scan, timeout, cancellation, budget, and cross-workspace tests pass.
