package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/** Bounded operational state for one canonical workflow. */
@JsonInclude(Include.ALWAYS)
public record WorkflowOperationsDetailDto(
    String recipeKey,
    WorkflowInfo workflow,
    Health health,
    ActiveVersion activeVersion,
    List<DefinitionChange> recentDefinitionChanges,
    Backlog backlog,
    List<WorkflowInterventionDto> openInterventions
) {

    /** Lifecycle and intake state for the selected workflow. */
    public record WorkflowInfo(
        int id,
        String name,
        boolean enabled,
        LocalDateTime archivedAt,
        LocalDateTime intakePausedAt,
        Integer intakePausedById,
        String runtimeOwner
    ) { }

    /** Current workflow health with stable machine-readable signals. */
    public record Health(String state, List<String> signals) { }

    /** Active immutable version summary. */
    @JsonInclude(Include.ALWAYS)
    public record ActiveVersion(
        long id,
        int number,
        String definitionHash,
        LocalDateTime publishedAt,
        Integer publishedById
    ) { }

    /** Bounded semantic change summary between adjacent versions. */
    public record DefinitionChange(
        Integer fromVersion,
        int toVersion,
        LocalDateTime publishedAt,
        Integer publishedById,
        List<String> addedNodeIds,
        List<String> removedNodeIds,
        List<String> changedNodeIds
    ) { }

    /** Queue and delay wait evidence for one workflow. */
    @JsonInclude(Include.ALWAYS)
    public record Backlog(
        int queuedCount,
        LocalDateTime oldestQueuedAt,
        int waitingCount,
        int dueNowCount,
        int overdueCount,
        LocalDateTime nextResumeAt,
        int recentFailureCount
    ) { }
}
