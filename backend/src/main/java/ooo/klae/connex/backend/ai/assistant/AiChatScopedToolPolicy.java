package ooo.klae.connex.backend.ai.assistant;

import java.util.Set;

/**
 * Decides which read tools a turn carrying a declared query scope is allowed to reach.
 *
 * <p>A declared scope is a promise about the query, restated to the requester as a scope chip and
 * persisted with the turn. Only {@code list_scope_activities} receives that scope and applies it, so
 * a cohort or aggregate read the model reaches for instead would answer from its own arguments while
 * the turn still echoed the declaration — an "owner: me" pipeline question answered with all-team
 * figures is the exact failure this class exists to prevent. Those tools are refused rather than
 * silently widened, because a scope this contract cannot intersect is a scope it must not claim.
 *
 * <p>Reads of one record the caller already reached through an authorized handle are exempt. They
 * select no cohort and compute no aggregate: the handle names the record, so there is no facet for
 * the declaration to narrow and nothing for a refusal to protect. {@code list_activities} is the one
 * exception that still consumes part of the declaration — it enumerates a time series, so the
 * declared period bounds it inside {@link AiAssistantToolExecutor} rather than being echoed and
 * dropped.
 */
final class AiChatScopedToolPolicy {

    /** Stable refusal reason for a read the turn's declared scope cannot be applied to. */
    static final String CANNOT_HONOR_DECLARED_SCOPE = "tool_cannot_honor_declared_scope";

    private static final Set<String> REFUSED_UNDER_DECLARED_SCOPE =
            Set.of("search_records", "aggregate_metric");

    private static final String DIRECTIVE = """
            This turn carries a server-declared query scope. Use list_scope_activities for any \
            question about a set of records: it is the only read the server applies that scope to. \
            search_records and aggregate_metric are refused for this turn because they cannot honour \
            it. Reads of a record you already hold a handle for remain available.""";

    private AiChatScopedToolPolicy() {
    }

    /**
     * Refuses a read that cannot apply the turn's declared scope.
     *
     * @param toolName declared tool key about to execute
     * @param scope validated declared turn scope
     */
    static void requireHonorsDeclaredScope(String toolName, AiChatQueryScope scope) {
        if (scope.declared() && REFUSED_UNDER_DECLARED_SCOPE.contains(toolName)) {
            throw AiAssistantLoopException.malformed(CANNOT_HONOR_DECLARED_SCOPE);
        }
    }

    /**
     * The per-turn instruction that keeps the model away from the reads above.
     *
     * <p>Per-turn rather than part of the fixed system envelope: on the smallest supported context
     * window the envelope is paid out of the answer's own output budget on every step of every turn,
     * and most turns declare no scope at all.
     *
     * @param scope validated declared turn scope
     * @return the instruction, or null when the turn declares no scope
     */
    static String directive(AiChatQueryScope scope) {
        return scope.declared() ? DIRECTIVE : null;
    }
}
