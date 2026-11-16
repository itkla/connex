package ooo.klae.connex.backend.ai.mission;

import java.util.Set;

import ooo.klae.connex.backend.beans.AiAgentRun;

/** Closed mission/runtime compatibility and execution ceilings; versions are exact matches. */
public final class AiAgentMissionCatalog {
    private static final Set<String> LEGACY_TOOLS = Set.of(
            "get_record", "get_records", "list_activities", "list_tasks", "find_tools");
    private static final Set<String> CONTINUATION_TOOLS = Set.of(
            "get_record", "get_records", "list_activities", "list_tasks", "find_tools",
            "set_todos", "checkpoint_segment");

    private AiAgentMissionCatalog() {
    }

    /** Recognizes only the two shipped definition/runtime combinations. */
    public static boolean supports(AiAgentRun run) {
        return run != null && supports(run.getMissionType(), run.getDefinitionVersion(),
                run.getRuntimeContractVersion());
    }

    /** Recognizes a version triple without interpreting any serialized content. */
    public static boolean supports(String type, int definition, int runtime) {
        return definition == 1 && (("record_review_v1".equals(type) && (runtime == 1 || runtime == 2))
                || ("record_preparation_v1".equals(type) && runtime == 3));
    }

    /** Returns the maximum committed logical segments for a supported run. */
    public static int maxSegments(AiAgentRun run) {
        requireSupported(run);
        return maxSegments(run.getRuntimeContractVersion());
    }

    /** Returns the exact runtime ceiling, refusing unknown versions. */
    public static int maxSegments(int runtime) {
        return switch (runtime) {
            case 1 -> 1;
            case 2, 3 -> 8;
            default -> throw new IllegalArgumentException("mission_version_refused");
        };
    }

    /** Each logical segment has three attempts, including runtime-1's only segment. */
    public static int maxAttempts(AiAgentRun run) {
        requireSupported(run);
        return 3;
    }

    /** Returns the exact runtime attempt ceiling. */
    public static int maxAttempts(int runtime) {
        maxSegments(runtime);
        return 3;
    }

    /** Returns only tools executable by the exact supported runtime. */
    public static Set<String> tools(AiAgentRun run) {
        requireSupported(run);
        return proposals(run) ? preparationTools(scopeKind(run)) : tools(run.getRuntimeContractVersion());
    }

    /** Returns the runtime-specific tool ceiling independently of prompt declarations. */
    public static Set<String> tools(int runtime) {
        return switch (runtime) {
            case 1 -> LEGACY_TOOLS;
            case 2 -> CONTINUATION_TOOLS;
            case 3 -> preparationTools("person");
            default -> Set.of();
        };
    }

    /** Identifies the exact runtime with durable continuation support. */
    public static boolean continuation(int runtime) {
        return runtime == 2 || runtime == 3;
    }

    /** Identifies the server-owned singleton proposal capability. */
    public static boolean proposals(AiAgentRun run) {
        return supports(run) && run.getRuntimeContractVersion() == 3;
    }

    /** Returns the closed selected-record CONFIRM ceiling. */
    public static Set<String> confirmTools(String kind) {
        return switch (kind) {
            case "person" -> Set.of("assign_owner", "remove_tag", "set_response_due", "update_record_fields");
            case "company" -> Set.of("assign_owner", "remove_tag", "update_record_fields");
            case "deal" -> Set.of("assign_owner", "remove_tag", "change_deal_stage", "update_record_fields");
            default -> Set.of();
        };
    }

    /** Returns read, bookkeeping and selected-record preparation tools only. */
    public static Set<String> preparationTools(String kind) {
        var permitted = new java.util.HashSet<>(CONTINUATION_TOOLS);
        permitted.addAll(confirmTools(kind));
        return Set.copyOf(permitted);
    }

    private static String scopeKind(AiAgentRun run) {
        var scope = tools.jackson.databind.json.JsonMapper.builder().build().readTree(run.getScopeJson());
        return scope.path("recordKind").asString();
    }

    /** Only an orphaned/interrupted read can be queued automatically. */
    public static boolean automaticRetryReason(String reason) {
        return "interrupted".equals(reason);
    }

    /** Refuses unsupported combinations before any reconstruction or provider work. */
    public static void requireSupported(AiAgentRun run) {
        if (!supports(run)) {
            throw new IllegalArgumentException("mission_version_refused");
        }
    }
}
