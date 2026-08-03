package ooo.klae.connex.backend.dto;

/** Installed workflow and immutable recipe provenance. */
public record WorkflowRecipeInstallDto(
    String recipeKey,
    int recipeVersion,
    String templateHash,
    WorkflowDto workflow
) { }
