package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/** Frozen, expiring exact scope and safe-to-display execution disclosure. */
@JsonInclude(Include.ALWAYS)
public record WorkflowManualPreparationDto(
    long invocationId,
    int workflowId,
    String workflowName,
    long workflowVersionId,
    int versionNumber,
    String definitionHash,
    String executionMode,
    Integer actorUserId,
    String scopeKind,
    String resolvedScopeKind,
    String sourceSurface,
    String recordType,
    String scopeToken,
    String scopeHash,
    LocalDateTime expiresAt,
    int exactCount,
    int readyCount,
    ExpectedSkips expectedSkips,
    List<Sample> samples,
    List<Action> actions,
    boolean confirmable,
    List<String> blockers
) {

    /** Expected per-record exclusions established during preparation. */
    public record ExpectedSkips(
        int permission,
        int staleState,
        int missingReference,
        int limit,
        int unsupportedContext
    ) { }

    /** Bounded sample used to verify the intended scope. */
    public record Sample(int recordId, String label) { }

    /** Action and persisted retry classification disclosure. */
    public record Action(String nodeId, String actionType, String retrySafety) { }
}
