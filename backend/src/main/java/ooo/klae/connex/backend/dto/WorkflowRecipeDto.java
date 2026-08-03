package ooo.klae.connex.backend.dto;

import java.util.List;

/** Stable disclosure for one curated deterministic workflow recipe. */
public record WorkflowRecipeDto(
    String recipeKey,
    int recipeVersion,
    int schemaVersion,
    String titleKey,
    String descriptionKey,
    String sourceEvent,
    String actorModel,
    List<String> dataRead,
    List<String> dataWritten,
    List<String> requiredParameters,
    List<String> requiredPermissions,
    List<String> lockedFields,
    List<String> editableFields,
    List<String> sideEffects,
    List<Action> actions,
    String disableBehavior,
    String removeBehavior
) {

    /** Per-action retry disclosure for a recipe template. */
    public record Action(String actionType, String retrySafety) { }
}
