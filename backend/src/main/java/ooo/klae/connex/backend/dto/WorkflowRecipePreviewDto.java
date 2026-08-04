package ooo.klae.connex.backend.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/** Side-effect-free, fully validated recipe preview. */
@JsonInclude(Include.ALWAYS)
public record WorkflowRecipePreviewDto(
    WorkflowRecipeDto recipe,
    String previewHash,
    WorkflowDefinition definition,
    WorkflowCanvas canvas,
    List<String> unresolvedParameters,
    WorkflowValidationDto validation,
    List<PlannedAction> plannedActions,
    WorkflowSimulationDto exampleResult,
    boolean writesCreated
) {

    /** One planned action and its runtime retry classification. */
    public record PlannedAction(
        String nodeId,
        String actionType,
        String retrySafety
    ) { }
}
