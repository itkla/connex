package ooo.klae.connex.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.JsonNode;

/** One typed schema-v2 workflow launch input. */
public record WorkflowInputDefinition(
    String key,
    String label,
    WorkflowInputType type,
    boolean required,
    @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode defaultValue
) {

    public WorkflowInputDefinition {
        if (defaultValue != null && defaultValue.isNull()) {
            defaultValue = null;
        }
    }
}
