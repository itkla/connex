package ooo.klae.connex.backend.dto;

import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/** Exact-scope manual workflow preparation request. */
public record WorkflowManualPrepareRequest(
    @NotBlank @Size(max = 24) String sourceSurface,
    @NotNull WorkflowManualScope scope,
    Map<String, JsonNode> inputs
) {

    /** Preserves the previous preparation constructor for callers without launch inputs. */
    public WorkflowManualPrepareRequest(String sourceSurface, WorkflowManualScope scope) {
        this(sourceSurface, scope, null);
    }
}
