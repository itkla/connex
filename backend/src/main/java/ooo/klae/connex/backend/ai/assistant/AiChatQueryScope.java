package ooo.klae.connex.backend.ai.assistant;

import java.time.LocalDate;
import java.util.List;

import ooo.klae.connex.backend.dto.MemberScope;

/**
 * The server-resolved declared query scope for one assistant turn.
 *
 * <p>Produced once by {@link AiChatQueryScopeResolver} on the request thread, where the caller's
 * session can authorize owners, stages, and saved views, and then carried unchanged through the
 * turn so every retrieval reads the same interpretation the caller was shown.
 *
 * @param declared whether the caller supplied any scope
 * @param periodStart inclusive resolved start date, or null when unbounded
 * @param periodEnd inclusive resolved end date, or null when unbounded
 * @param periodDays resolved trailing window in days, or null when unbounded
 * @param memberScope canonical owner filter, never an access boundary
 * @param warmthBands resolved warmth bands, empty when unfiltered
 * @param recordKinds resolved record kinds, empty when unfiltered
 * @param stageIds authorized deal stage ids, empty when unfiltered
 * @param dealStatuses resolved deal statuses, empty when unfiltered
 * @param activityTypes resolved activity types, empty when unfiltered
 * @param savedViewId authorized saved view whose segment definition bounds the cohort, or null
 */
public record AiChatQueryScope(
        boolean declared,
        LocalDate periodStart,
        LocalDate periodEnd,
        Integer periodDays,
        MemberScope memberScope,
        List<String> warmthBands,
        List<String> recordKinds,
        List<Integer> stageIds,
        List<String> dealStatuses,
        List<String> activityTypes,
        Integer savedViewId) {

    public AiChatQueryScope {
        memberScope = memberScope == null ? MemberScope.allTeam() : memberScope;
        warmthBands = warmthBands == null ? List.of() : List.copyOf(warmthBands);
        recordKinds = recordKinds == null ? List.of() : List.copyOf(recordKinds);
        stageIds = stageIds == null ? List.of() : List.copyOf(stageIds);
        dealStatuses = dealStatuses == null ? List.of() : List.copyOf(dealStatuses);
        activityTypes = activityTypes == null ? List.of() : List.copyOf(activityTypes);
    }

    /** Returns the empty scope used when a turn declares none. */
    public static AiChatQueryScope none() {
        return new AiChatQueryScope(
                false, null, null, null, MemberScope.allTeam(),
                List.of(), List.of(), List.of(), List.of(), List.of(), null);
    }

    /** @return whether the scope constrains which records a cohort may contain */
    public boolean constrainsCohort() {
        return !warmthBands.isEmpty() || !stageIds.isEmpty() || !dealStatuses.isEmpty()
                || savedViewId != null || memberScope.mode() != MemberScope.Mode.ALL_TEAM;
    }
}
