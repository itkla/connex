package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import tools.jackson.databind.JsonNode;

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
    String actorLabel,
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
    List<Sample> skippedSamples,
    List<Action> actions,
    boolean confirmable,
    List<String> blockers,
    List<ResolvedInput> resolvedInputs,
    List<EffectSample> effectSamples,
    List<BlockerDetail> blockerDetails
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

    /** One launch input frozen into this preparation. */
    public record ResolvedInput(
        String key,
        String label,
        WorkflowInputType type,
        JsonNode value,
        String displayValue,
        String source
    ) { }

    /** One record-specific rendered action preview. */
    public record EffectSample(
        int recordId,
        String nodeId,
        String actionType,
        String title,
        String body,
        Integer targetUserId,
        String targetLabel,
        String dueDate,
        String retrySafety
    ) { }

    /** Structured blocker evidence for values such as cooldown eligibility time. */
    public record BlockerDetail(String code, LocalDateTime eligibleAt) { }
}
