package ooo.klae.connex.backend.dto;

import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/** Installs the exact recipe preview the operator reviewed. */
public record WorkflowRecipeInstallRequest(
    @NotBlank @Size(max = 64) String previewHash,
    @Size(max = 128) String name,
    @Size(max = 512) String description,
    @NotNull Map<String, JsonNode> parameters
) { }
