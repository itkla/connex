package ooo.klae.connex.backend.ai.assistant;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import ooo.klae.connex.backend.ai.AiFeature;
import ooo.klae.connex.backend.tenant.Permission;

/**
 * The declare-once, versioned source of truth for Ask Connex skills.
 *
 * <p>A skill is a complete CRM job with a server-owned retrieval plan, not a prompt hint. Declaring
 * it here — rather than describing it to the model — is what keeps routine relationship, meeting,
 * pipeline, and data-quality work off the improvised tool loop: the server executes the declared
 * plan itself and the model is left with synthesis, which is the only step it is actually better at.
 *
 * <p>Keys are additive and replay-safe. A key that has been reserved but not yet implemented is
 * declared with {@link Availability#DECLARED} and an explicit reason, so a persisted turn can name a
 * skill this build cannot run without the catalog inventing a contract for it. Nothing in this
 * catalog is ever serialized into the fixed prompt envelope; only the selected skill's bounded
 * directive enters a turn, because the envelope on the smallest supported model is paid out of the
 * answer's own output budget.
 */
@Component
public class AiSkillCatalog {

    /** Whether this build can execute a declared key. */
    public enum Availability { AVAILABLE, DECLARED }

    /** The most a skill is ever permitted to do, independent of what its tools could do. */
    public enum Authority { READ, DRAFT, PROPOSE, EXECUTE_REVERSIBLE }

    /** Behaviour when part of a declared plan cannot be completed. */
    public enum PartialBehavior { BOUNDED_PARTIAL, FAIL_CLOSED }

    /** One closed server-owned retrieval operation a plan may contain. */
    public enum PlanStepKind {
        GET_RECORD("get_record"),
        LIST_ACTIVITIES("list_activities"),
        LIST_TASKS("list_tasks"),
        RELATIONSHIP_METRICS("relationship_metrics"),
        SCOPE_ACTIVITIES("list_scope_activities"),
        DEAL_ATTENTION("deal_attention"),
        WORK_COMMITMENTS("work_commitments"),
        WARMTH_MOVEMENT("warmth_movement"),
        UPCOMING_MEETINGS("upcoming_meetings");

        private final String toolName;

        PlanStepKind(String toolName) {
            this.toolName = toolName;
        }

        /** @return the durable tool name this step records and the progress trail projects */
        public String toolName() {
            return toolName;
        }
    }

    /**
     * One declared plan step.
     *
     * @param kind closed retrieval operation
     * @param rowLimit maximum rows the step may read, or 0 when the step reads a single record
     * @param perRecordLimit maximum rows per cohort record, or 0 when not a cohort step
     * @param required whether a failure of this step makes the skill unusable
     */
    public record PlanStep(PlanStepKind kind, int rowLimit, int perRecordLimit, boolean required) {
    }

    /**
     * Declared bounds a skill's retrieval may never exceed.
     *
     * @param maxRecords maximum cohort records
     * @param maxRows maximum rows across the whole plan
     * @param maxPeriodDays maximum date range covered
     * @param maxResultBytes maximum serialized bytes the plan may contribute to one model step
     */
    public record Bounds(int maxRecords, int maxRows, int maxPeriodDays, int maxResultBytes) {
    }

    /**
     * Declared cost and latency budgets.
     *
     * @param maxModelSteps model steps permitted after the server-owned plan has run
     * @param maxLatencyMillis wall-clock budget for the plan itself
     * @param maxAnswerBlocks answer-document blocks the skill's result contract permits
     */
    public record Budgets(int maxModelSteps, long maxLatencyMillis, int maxAnswerBlocks) {
    }

    /**
     * Declared golden evaluation gate.
     *
     * @param goldenSetId identifier of the case group inside the assistant evaluation set
     * @param minimumCasesPerLocale cases each of EN and JA must contribute before the skill ships
     * @param categories risk categories the golden set must cover
     */
    public record Evaluation(
            String goldenSetId, int minimumCasesPerLocale, Set<String> categories) {

        public Evaluation {
            categories = Set.copyOf(categories);
        }
    }

    /**
     * One declared skill.
     *
     * <p><strong>Enforced by this build:</strong> {@code availability} and {@code permissions} gate
     * routing and are re-asserted before the plan's first step; {@code contextKinds} and
     * {@code contextRequired} decide whether a subject anchors the plan; {@code plan} is executed
     * verbatim; {@code allowedTools} and {@code authority} together bound the WRITE
     * tools the model may cause to run after the plan (read tools carry no authority and stay
     * available to synthesis); {@code budgets.maxModelSteps} clamps the synthesis loop;
     * {@code scopePreviewRecords} drives the preview recommendation; {@code partialBehavior}
     * governs required-versus-optional step failure; {@code evaluation} is gated in CI by the
     * assistant evaluation regression; {@code triggers} and {@code directive} are used per turn.
     *
     * <p><strong>Declarative only, pending the #1335 follow-up:</strong> {@code requiredInputs},
     * {@code optionalInputs}, {@code requiredMetrics}, {@code resultBlockKinds},
     * {@code coverageSources}, {@code citationsRequired}, {@code minimumContextTokens},
     * {@code bounds}, and {@code budgets.maxLatencyMillis} and {@code budgets.maxAnswerBlocks} are
     * declared and read by nothing that rejects a violation. Retrieval is bounded by
     * {@link AiChatScopeBounds} and each plan step's own limits, the turn by
     * {@link AiAssistantTurnBudget}, and the answer document by its own schema — those are the real
     * ceilings today. These fields state intent for the result-contract validator that will enforce
     * them; do not read them as guarantees.
     *
     * @param key stable additive catalog key
     * @param version semantic version of this declaration
     * @param availability whether this build can execute the key
     * @param unavailableReason stable reason a declared key cannot run, or null when available
     * @param nameKey client i18n key for the product-language name
     * @param descriptionKey client i18n key for the product-language description
     * @param contextKinds record kinds the skill can be anchored to
     * @param contextRequired whether the skill refuses without an anchoring record
     * @param requiredInputs inputs that must resolve before the plan runs
     * @param optionalInputs inputs that narrow the plan when present
     * @param plan deterministic server-owned retrieval plan
     * @param allowedTools every tool key the skill may cause to run, plan or fallback
     * @param requiredMetrics server-computed figures the answer must be grounded in
     * @param resultBlockKinds answer-document block kinds the skill's result contract permits
     * @param coverageSources coverage source categories the skill may claim
     * @param citationsRequired whether every factual block must cite a retrieved record
     * @param permissions workspace permissions the asking member must hold
     * @param feature AI feature gate the skill runs under
     * @param minimumContextTokens provider context window the plan's results assume
     * @param authority the most this skill may do
     * @param bounds declared retrieval bounds
     * @param budgets declared cost and latency budgets
     * @param scopePreviewRecords cohort size at which the request is offered as an editable preview
     * @param partialBehavior behaviour when part of the plan fails
     * @param evaluation golden evaluation gate
     * @param triggers deterministic recognition patterns across supported locales
     * @param directive bounded per-turn contract text handed to the model with the plan results
     */
    public record SkillSpec(
            String key,
            String version,
            Availability availability,
            String unavailableReason,
            String nameKey,
            String descriptionKey,
            Set<String> contextKinds,
            boolean contextRequired,
            Set<String> requiredInputs,
            Set<String> optionalInputs,
            List<PlanStep> plan,
            Set<String> allowedTools,
            Set<String> requiredMetrics,
            Set<String> resultBlockKinds,
            Set<String> coverageSources,
            boolean citationsRequired,
            Set<Permission> permissions,
            AiFeature feature,
            int minimumContextTokens,
            Authority authority,
            Bounds bounds,
            Budgets budgets,
            int scopePreviewRecords,
            PartialBehavior partialBehavior,
            Evaluation evaluation,
            List<Pattern> triggers,
            String directive) {

        public SkillSpec {
            contextKinds = Set.copyOf(contextKinds);
            requiredInputs = Set.copyOf(requiredInputs);
            optionalInputs = Set.copyOf(optionalInputs);
            plan = List.copyOf(plan);
            allowedTools = Set.copyOf(allowedTools);
            requiredMetrics = Set.copyOf(requiredMetrics);
            resultBlockKinds = Set.copyOf(resultBlockKinds);
            coverageSources = Set.copyOf(coverageSources);
            permissions = Set.copyOf(permissions);
            triggers = List.copyOf(triggers);
        }

        /** @return whether this build can execute the skill */
        public boolean available() {
            return availability == Availability.AVAILABLE;
        }

        /** @return whether the skill needs a record to anchor to */
        public boolean needsSubject() {
            return contextRequired;
        }
    }

    private static final int MAX_DIRECTIVE_BYTES = 1_024;
    private static final String COOLING = "relationship_cooling_explanation_v1";
    private static final String DIGEST = "activity_digest_v1";
    private static final String BRIEF = "relationship_brief_v1";
    private static final String PIPELINE = "pipeline_attention_review_v1";
    private static final String WORK_BRIEF = "daily_work_brief_v1";
    private static final String NOT_YET_IMPLEMENTED = "skill_not_yet_implemented";

    private static final Map<String, SkillSpec> SKILLS = buildSkills();

    /** @return every declared skill, available first, in deterministic routing order */
    public List<SkillSpec> skills() {
        return List.copyOf(SKILLS.values());
    }

    /** @return the declared skill for a key, or empty when the key is unknown */
    public Optional<SkillSpec> find(String key) {
        return Optional.ofNullable(SKILLS.get(key));
    }

    /** @return whether the key is declared, including reserved not-yet-implemented keys */
    public boolean isKnown(String key) {
        return key != null && SKILLS.containsKey(key);
    }

    /** @return whether this build can execute the key */
    public boolean isAvailable(String key) {
        SkillSpec spec = SKILLS.get(key);
        return spec != null && spec.available();
    }

    /** @return the maximum serialized size a per-turn skill directive may reach */
    public static int maxDirectiveBytes() {
        return MAX_DIRECTIVE_BYTES;
    }

    private static Map<String, SkillSpec> buildSkills() {
        Map<String, SkillSpec> skills = new LinkedHashMap<>();
        add(skills, coolingExplanation());
        add(skills, activityDigest());
        // The personal work brief is recognized before the relationship brief because its triggers
        // are strictly narrower: the Japanese relationship-brief trigger accepts the bare word
        // "ブリーフ", which would otherwise swallow "今日のブリーフ" and answer a request about the
        // member's own day by asking which record they meant.
        add(skills, dailyWorkBrief());
        add(skills, relationshipBrief());
        add(skills, pipelineAttentionReview());
        declared(skills, "relationship_change_summary_v1");
        declared(skills, "introduction_path_explanation_v1");
        declared(skills, "meeting_preparation_v1");
        declared(skills, "meeting_follow_up_extraction_v1");
        declared(skills, "follow_up_draft_v1");
        declared(skills, "deal_risk_review_v1");
        declared(skills, "stakeholder_gap_analysis_v1");
        declared(skills, "company_review_v1");
        declared(skills, "commitment_extraction_v1");
        declared(skills, "data_quality_review_v1");
        declared(skills, "natural_language_report_v1");
        return Collections.unmodifiableMap(skills);
    }

    private static SkillSpec coolingExplanation() {
        return new SkillSpec(
                COOLING,
                "1.0.0",
                Availability.AVAILABLE,
                null,
                "askConnex.skills.relationshipCoolingExplanation.name",
                "askConnex.skills.relationshipCoolingExplanation.description",
                Set.of("person", "company"),
                true,
                Set.of("subject"),
                Set.of("period"),
                List.of(
                        new PlanStep(PlanStepKind.RELATIONSHIP_METRICS, 0, 0, true),
                        new PlanStep(PlanStepKind.GET_RECORD, 0, 0, true),
                        new PlanStep(PlanStepKind.LIST_ACTIVITIES, 20, 0, false)),
                Set.of("relationship_metrics", "get_record", "list_activities"),
                Set.of("warmth_score", "warmth_band", "warmth_trend", "days_since_touch"),
                Set.of("answer", "fact", "metric", "inference", "recommendation", "limitation"),
                Set.of("records", "activities", "metrics"),
                true,
                Set.of(Permission.AI_USE),
                AiFeature.ASSISTANT_CHAT,
                32_768,
                Authority.READ,
                new Bounds(1, 20, AiChatScopeBounds.MAX_PERIOD_DAYS, 8_192),
                new Budgets(3, 45_000L, 8),
                Integer.MAX_VALUE,
                PartialBehavior.BOUNDED_PARTIAL,
                new Evaluation(
                        COOLING, 1, Set.of("factuality", "citation_correctness")),
                // Recognition deliberately requires an explanatory framing rather than the mere
                // presence of a warmth word: "list activity for cool accounts" is a bounded digest,
                // not a request to explain one relationship, and the two must not collide.
                List.of(
                        Pattern.compile(
                                "\\b(why|explain|reason|cause)\\b[^?.]{0,80}"
                                        + "\\b(cool|cooling|cold|colder|quiet|less active"
                                        + "|gone quiet|cooled off)\\b",
                                Pattern.CASE_INSENSITIVE),
                        Pattern.compile(
                                "\\b(cooling|gone cold|going cold|gone quiet|cooled off"
                                        + "|losing (?:steam|momentum))\\b[^?.]{0,60}"
                                        + "\\b(why|reason|cause|explain)\\b",
                                Pattern.CASE_INSENSITIVE),
                        Pattern.compile(
                                "(なぜ|理由|原因|どうして)[^。]{0,40}"
                                        + "(冷め|冷え|疎遠|反応がな|関係.{0,6}悪化)"),
                        Pattern.compile(
                                "(冷め|冷え|疎遠|反応がな|関係.{0,6}悪化)[^。]{0,40}"
                                        + "(なぜ|理由|原因|どうして)")),
                """
                Server-owned skill: relationship cooling explanation. The relationship metrics in \
                CRM_DATA are the authoritative warmth figures; never recompute or estimate them. \
                Explain the decline using the retrieved activity, separate fact from inference, \
                cite the subject record, and state plainly when the evidence is too sparse.""");
    }

    private static SkillSpec activityDigest() {
        return new SkillSpec(
                DIGEST,
                "1.0.0",
                Availability.AVAILABLE,
                null,
                "askConnex.skills.activityDigest.name",
                "askConnex.skills.activityDigest.description",
                Set.of("person", "company", "deal"),
                false,
                Set.of(),
                Set.of("scope", "period", "warmth", "records"),
                List.of(new PlanStep(
                        PlanStepKind.SCOPE_ACTIVITIES,
                        AiChatScopeBounds.MAX_ACTIVITY_ROWS,
                        AiChatScopeBounds.DEFAULT_ACTIVITY_ROWS_PER_RECORD,
                        true)),
                Set.of("list_scope_activities"),
                Set.of("matching_activity_count", "matched_record_count"),
                Set.of("answer", "timeline", "list", "metric", "limitation"),
                Set.of("records", "activities"),
                true,
                Set.of(Permission.AI_USE),
                AiFeature.ASSISTANT_CHAT,
                32_768,
                Authority.READ,
                new Bounds(
                        AiChatScopeBounds.MAX_COHORT_RECORDS,
                        AiChatScopeBounds.MAX_ACTIVITY_ROWS,
                        AiChatScopeBounds.MAX_PERIOD_DAYS,
                        16_384),
                new Budgets(3, 60_000L, 12),
                AiChatScopeBounds.SCOPE_PREVIEW_RECORD_THRESHOLD,
                PartialBehavior.BOUNDED_PARTIAL,
                new Evaluation(DIGEST, 1, Set.of("factuality", "tool_selection")),
                List.of(
                        Pattern.compile(
                                "\\b(recent|latest|last)\\b[^?]{0,40}"
                                        + "\\b(activit|interaction|touchpoint|contact)",
                                Pattern.CASE_INSENSITIVE),
                        Pattern.compile(
                                "\\b(activity|activities)\\b[^?]{0,40}"
                                        + "\\b(digest|summary|across|for (?:all|every|cool|cold))",
                                Pattern.CASE_INSENSITIVE),
                        Pattern.compile(
                                "\\blist\\b[^?]{0,30}\\bactivit",
                                Pattern.CASE_INSENSITIVE),
                        Pattern.compile("(最近|直近).{0,10}(活動|やり取り|接触|アクティビティ)"),
                        Pattern.compile("活動.{0,6}(一覧|まとめ|サマリ)")),
                """
                Server-owned skill: bounded activity digest. The scope, counts, and caps in CRM_DATA \
                are exact; report them rather than implying complete coverage. Group the rows by \
                record, keep them newest first, cite every record you name, and set coverage to \
                partial with bounded_results whenever the result declares truncation.""");
    }

    private static SkillSpec relationshipBrief() {
        return new SkillSpec(
                BRIEF,
                "1.0.0",
                Availability.AVAILABLE,
                null,
                "askConnex.skills.relationshipBrief.name",
                "askConnex.skills.relationshipBrief.description",
                Set.of("person", "company"),
                true,
                Set.of("subject"),
                Set.of("period"),
                List.of(
                        new PlanStep(PlanStepKind.GET_RECORD, 0, 0, true),
                        new PlanStep(PlanStepKind.RELATIONSHIP_METRICS, 0, 0, false),
                        new PlanStep(PlanStepKind.LIST_ACTIVITIES, 10, 0, false),
                        new PlanStep(PlanStepKind.LIST_TASKS, 5, 0, false)),
                Set.of("get_record", "relationship_metrics", "list_activities", "list_tasks"),
                Set.of("warmth_score", "warmth_band"),
                Set.of("answer", "fact", "metric", "list", "recommendation", "limitation"),
                Set.of("records", "activities", "tasks", "notes", "metrics"),
                true,
                Set.of(Permission.AI_USE),
                AiFeature.ASSISTANT_CHAT,
                32_768,
                Authority.READ,
                new Bounds(1, 35, AiChatScopeBounds.MAX_PERIOD_DAYS, 12_288),
                new Budgets(3, 45_000L, 10),
                Integer.MAX_VALUE,
                PartialBehavior.BOUNDED_PARTIAL,
                new Evaluation(BRIEF, 1, Set.of("factuality", "citation_correctness")),
                // Recognition requires the briefing framing to name a record or relationship, not
                // merely to open with a briefing verb: "tell me about the pricing on this deal" is a
                // question about a field, and answering it with a whole relationship brief on a
                // three-step synthesis budget is worse than letting the generic loop handle it.
                List.of(
                        Pattern.compile(
                                "\\b(?:catch me up|bring me up to speed)\\b",
                                Pattern.CASE_INSENSITIVE),
                        Pattern.compile(
                                "\\b(?:brief|briefing|overview|tell me about"
                                        + "|what do (?:we|i) know about)\\b"
                                        + "\\s+(?:me\\s+)?(?:on|about|of|for)?\\s*"
                                        + "(?:this|that|the|our|their|his|her)?\\s*"
                                        + "(?:accounts?|companies|company|contacts?|customers?"
                                        + "|clients?|people|person|relationships?"
                                        + "|organi[sz]ations?|them|him|her)\\b",
                                Pattern.CASE_INSENSITIVE),
                        Pattern.compile("(概要|ブリーフ|どんな(?:会社|人|関係)|まとめて教えて)")),
                """
                Server-owned skill: relationship brief. Build a short, specific picture from the \
                retrieved record, warmth metrics, activity, and tasks. The warmth figures in \
                CRM_DATA are authoritative. Cite the subject record, keep recommendations separate \
                from fact, and say plainly which parts of the picture the data does not cover.""");
    }

    private static SkillSpec pipelineAttentionReview() {
        return new SkillSpec(
                PIPELINE,
                "1.0.0",
                Availability.AVAILABLE,
                null,
                "askConnex.skills.pipelineAttentionReview.name",
                "askConnex.skills.pipelineAttentionReview.description",
                Set.of("deal", "company"),
                false,
                Set.of(),
                Set.of("scope", "owners", "stages"),
                List.of(new PlanStep(
                        PlanStepKind.DEAL_ATTENTION,
                        AiChatScopeBounds.MAX_ATTENTION_DEALS, 0, true)),
                Set.of("deal_attention"),
                Set.of("risk_level", "risk_score", "risk_factors"),
                Set.of("answer", "list", "metric", "recommendation", "limitation"),
                Set.of("deals", "metrics"),
                true,
                Set.of(Permission.AI_USE),
                AiFeature.ASSISTANT_CHAT,
                32_768,
                Authority.READ,
                new Bounds(
                        AiChatScopeBounds.MAX_COHORT_RECORDS,
                        AiChatScopeBounds.MAX_ATTENTION_DEALS,
                        AiChatScopeBounds.MAX_PERIOD_DAYS,
                        12_288),
                new Budgets(3, 60_000L, 12),
                AiChatScopeBounds.SCOPE_PREVIEW_RECORD_THRESHOLD,
                PartialBehavior.BOUNDED_PARTIAL,
                new Evaluation(PIPELINE, 1, Set.of("factuality", "tool_selection")),
                List.of(
                        Pattern.compile(
                                "\\b(deals?|pipeline|opportunit)\\w*\\b[^?]{0,50}"
                                        + "\\b(need|needs|require|attention|at risk|risky"
                                        + "|stalled|slipping|stuck|focus on)\\b",
                                Pattern.CASE_INSENSITIVE),
                        Pattern.compile(
                                "\\bwhich\\b[^?]{0,30}\\bdeals?\\b",
                                Pattern.CASE_INSENSITIVE),
                        Pattern.compile(
                                "(案件|商談|パイプライン)[^。]{0,20}(リスク|注意|停滞|遅れ|要対応)"),
                        Pattern.compile(
                                "(リスク|停滞|遅れ|要対応)[^。]{0,20}(案件|商談|パイプライン)")),
                """
                Server-owned skill: pipeline attention review. The risk level, score, and factor \
                codes in CRM_DATA come from the deterministic Connex risk model; never invent a \
                risk or reorder the deals by your own judgement. Explain each flagged deal from its \
                factor codes, cite it, and disclose when the review was bounded.""");
    }

    /**
     * The member's own bounded work brief, assembled entirely from source-owned state.
     *
     * <p>Every section is a projection of a system that already owns it: commitments come from the
     * task projection behind My Work, cooling relationships from the deterministic warmth model
     * behind Radar, deal risk from the deterministic risk model, and scheduled activities from the
     * member's own logged future-dated activities. The skill anchors to no record because the
     * subject is the member; its period is carried by the turn's declared scope, so the same
     * declaration produces a one-day brief or a seven-day review without a second contract.
     *
     * <p>The commitment step is the only required one. If the task projection cannot be read the
     * brief has no spine and the generic loop is a better answer than a brief that silently omits
     * the member's work; every other section degrades to a stated gap.
     */
    private static SkillSpec dailyWorkBrief() {
        return new SkillSpec(
                WORK_BRIEF,
                "1.0.0",
                Availability.AVAILABLE,
                null,
                "askConnex.skills.dailyWorkBrief.name",
                "askConnex.skills.dailyWorkBrief.description",
                Set.of(),
                false,
                Set.of(),
                Set.of("period"),
                List.of(
                        new PlanStep(
                                PlanStepKind.WORK_COMMITMENTS,
                                AiChatScopeBounds.MAX_BRIEF_COMMITMENTS, 0, true),
                        new PlanStep(
                                PlanStepKind.UPCOMING_MEETINGS,
                                AiChatScopeBounds.MAX_BRIEF_MEETINGS, 0, false),
                        new PlanStep(
                                PlanStepKind.WARMTH_MOVEMENT,
                                AiChatScopeBounds.MAX_BRIEF_WARMTH_MOVES, 0, false),
                        new PlanStep(
                                PlanStepKind.DEAL_ATTENTION,
                                AiChatScopeBounds.MAX_ATTENTION_DEALS, 0, false)),
                Set.of("work_commitments", "upcoming_meetings",
                        "warmth_movement", "deal_attention"),
                Set.of("overdue_commitment_count", "warmth_band", "risk_level"),
                Set.of("answer", "fact", "metric", "list", "inference",
                        "recommendation", "limitation"),
                Set.of("tasks", "activities", "metrics", "deals"),
                true,
                Set.of(Permission.AI_USE),
                AiFeature.ASSISTANT_CHAT,
                32_768,
                Authority.READ,
                new Bounds(
                        AiChatScopeBounds.MAX_COHORT_RECORDS,
                        AiChatScopeBounds.MAX_BRIEF_COMMITMENTS
                                + AiChatScopeBounds.MAX_BRIEF_MEETINGS
                                + (AiChatScopeBounds.MAX_BRIEF_WARMTH_MOVES * 2)
                                + AiChatScopeBounds.MAX_ATTENTION_DEALS,
                        AiChatScopeBounds.MAX_PERIOD_DAYS,
                        16_384),
                new Budgets(3, 90_000L, 14),
                Integer.MAX_VALUE,
                PartialBehavior.BOUNDED_PARTIAL,
                new Evaluation(WORK_BRIEF, 1, Set.of("factuality", "tool_selection")),
                // Recognition requires the request to be about the member's own period of work.
                // "brief me on Acme" is a relationship brief and "which deals need attention" is a
                // pipeline review; capturing either here would answer a record question with a
                // personal day plan. "Catch me up" is deliberately absent: the relationship brief
                // already claims it, and two skills competing for one phrase is worse than one.
                //
                // Because this skill is matched before the relationship brief, every pattern must
                // end at the member's own period rather than run on into a record. That is what the
                // trailing lookahead and the bare Japanese forms enforce: "today's summary of Acme"
                // and 「今日のAcme社のまとめ」 name a record immediately after the period word, so
                // they fall through to the record skill that can actually anchor them, while "my
                // daily brief" and 「今日のブリーフ」 do not and are recognized here. "review" stays
                // in the alternation because a scheduled weekly run sends the literal sentence
                // "Give me my weekly review." and must take the same routed path a typed one does.
                List.of(
                        Pattern.compile(
                                "\\b(?:daily|morning|weekly|today'?s|this\\s+week'?s)\\s+"
                                        + "(?:brief|briefing|digest|summary|rundown|review)\\b"
                                        + "(?!\\s+(?:of|on|for|about|with|regarding)\\b)",
                                Pattern.CASE_INSENSITIVE),
                        Pattern.compile(
                                "\\bwhat\\s+(?:should|do)\\s+i\\s+"
                                        + "(?:focus\\s+on|work\\s+on|prioriti[sz]e|do)\\s+"
                                        + "(?:first\\s+)?(?:today|this\\s+week|now|first)\\b",
                                Pattern.CASE_INSENSITIVE),
                        Pattern.compile(
                                "\\bwhat'?s\\s+on\\s+my\\s+plate\\b",
                                Pattern.CASE_INSENSITIVE),
                        Pattern.compile("(今日|本日|今週)の?"
                                + "(ブリーフ|サマリー|サマリ|まとめ|やること|優先事項|予定とタスク)"),
                        Pattern.compile("(私|自分)の?(今日|本日|今週)の?"
                                + "(ブリーフ|やること|優先事項|状況)")),
                """
                Server-owned skill: personal work brief. Every figure in CRM_DATA is authoritative \
                and already owned elsewhere — commitments by tasks, warmth by the Connex warmth \
                model, deal risk by the Connex risk model. Never recompute, re-rank by your own \
                judgement, or invent a section the plan did not return. Report the exact counts, \
                keep facts, inferences, and recommendations separate, cite every record you name, \
                and state which sections were unavailable, bounded, or empty. Meeting preparation \
                state does not exist in Connex: describe scheduled activities as scheduled, never \
                as prepared or unprepared. When the evidence is thin, say the day looks quiet \
                rather than manufacturing significance from a handful of rows.""");
    }

    private static SkillSpec declaredOnly(String key) {
        return new SkillSpec(
                key,
                "1.0.0",
                Availability.DECLARED,
                NOT_YET_IMPLEMENTED,
                "askConnex.skills." + camelCase(key) + ".name",
                "askConnex.skills." + camelCase(key) + ".description",
                Set.of(),
                false,
                Set.of(),
                Set.of(),
                List.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                true,
                Set.of(Permission.AI_USE),
                AiFeature.ASSISTANT_CHAT,
                32_768,
                Authority.READ,
                new Bounds(0, 0, 0, 0),
                new Budgets(0, 0L, 0),
                Integer.MAX_VALUE,
                PartialBehavior.FAIL_CLOSED,
                new Evaluation(key, 0, Set.of()),
                List.of(),
                "");
    }

    /**
     * Converts a catalog key into the camel-case fragment its client copy is keyed by, dropping the
     * trailing declaration version so the i18n key survives a semantic-version bump.
     */
    private static String camelCase(String key) {
        String[] parts = key.split("_");
        StringBuilder camel = new StringBuilder();
        for (int index = 0; index < parts.length; index++) {
            String part = parts[index];
            if (part.isEmpty() || (index == parts.length - 1 && part.matches("v\\d+"))) {
                continue;
            }
            if (camel.isEmpty()) {
                camel.append(part);
                continue;
            }
            camel.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return camel.toString();
    }

    private static void add(Map<String, SkillSpec> skills, SkillSpec spec) {
        skills.put(spec.key(), spec);
    }

    private static void declared(Map<String, SkillSpec> skills, String key) {
        add(skills, declaredOnly(key));
    }
}
