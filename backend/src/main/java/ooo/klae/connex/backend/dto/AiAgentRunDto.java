package ooo.klae.connex.backend.dto;

import java.time.Instant;
import java.util.List;

/** Current requester-authorized projection, with withheld content represented explicitly. */
public record AiAgentRunDto(int schemaVersion, int id, Integer sessionId, String missionType,
        int definitionVersion, String state, String reasonCode, String stateVersion, String goal,
        AiAgentRunCreateRequest.Scope scope, Instant createdAt, Instant executionDeadlineAt,
        Instant hardDeadlineAt, Instant startedAt, Instant finishedAt, boolean outputAvailable,
        boolean contentWithheld, List<String> availableActions, Progress progress,
        String proposalMembershipRevision, Instant proposalFrozenAt, Instant proposalFirstPreparedAt,
        Instant reviewExpiresAt, AiAgentOutputDto.Counts proposalCounts, String outputRevision) {
    /** Preserves the metadata constructor used by read-only mission consumers. */
    public AiAgentRunDto(int schemaVersion, int id, Integer sessionId, String missionType,
            int definitionVersion, String state, String reasonCode, String stateVersion, String goal,
            AiAgentRunCreateRequest.Scope scope, Instant createdAt, Instant executionDeadlineAt,
            Instant hardDeadlineAt, Instant startedAt, Instant finishedAt, boolean outputAvailable,
            boolean contentWithheld, List<String> availableActions, Progress progress) {
        this(schemaVersion, id, sessionId, missionType, definitionVersion, state, reasonCode, stateVersion,
                goal, scope, createdAt, executionDeadlineAt, hardDeadlineAt, startedAt, finishedAt,
                outputAvailable, contentWithheld, availableActions, progress, null, null, null, null, null,
                outputAvailable ? "1" : null);
    }

    /** Requester-only one-record progress. */
    public record Progress(String phase, int completedRecords, int totalRecords) {}
}
