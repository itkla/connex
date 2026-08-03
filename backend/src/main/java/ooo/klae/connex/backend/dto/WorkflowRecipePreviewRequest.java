package ooo.klae.connex.backend.dto;

import java.util.Map;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/** Side-effect-free recipe parameterization request. */
public record WorkflowRecipePreviewRequest(
    @Size(max = 128) String name,
    @Size(max = 512) String description,
    @NotNull Map<String, JsonNode> parameters,
    @Positive Integer exampleRecordId
) { }
