package ooo.klae.connex.backend.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/** Published workflow choices and stable eligibility reasons for one manual launch surface. */
@JsonInclude(Include.ALWAYS)
public record WorkflowManualOptionsDto(
    String recordType,
    Integer recordId,
    String createHref,
    List<Option> options
) {

    /** One published manual-entry candidate and its current availability. */
    public record Option(
        int workflowId,
        String workflowName,
        long workflowVersionId,
        int versionNumber,
        String definitionHash,
        int schemaVersion,
        String manualEntryMode,
        boolean available,
        List<String> reasons,
        Execution execution,
        List<WorkflowInputDefinition> inputs,
        List<Action> actions
    ) { }

    /** Configured execution identity, separate from action targets. */
    public record Execution(String mode, Integer actorUserId, String actorLabel) { }

    /** Bounded action disclosure using server retry classification. */
    public record Action(String nodeId, String actionType, String retrySafety) { }
}
