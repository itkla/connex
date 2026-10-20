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
        return "record_review_v1".equals(type) && definition == 1 && (runtime == 1 || runtime == 2);
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
            case 2 -> 8;
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
        return tools(run.getRuntimeContractVersion());
    }

    /** Returns the runtime-specific tool ceiling independently of prompt declarations. */
    public static Set<String> tools(int runtime) {
        return switch (runtime) {
            case 1 -> LEGACY_TOOLS;
            case 2 -> CONTINUATION_TOOLS;
            default -> Set.of();
        };
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
