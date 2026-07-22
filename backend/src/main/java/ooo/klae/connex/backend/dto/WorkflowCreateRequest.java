package ooo.klae.connex.backend.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Creates a disabled workflow with a bounded, potentially incomplete schema-v1 draft. */
@Data
@NoArgsConstructor
public class WorkflowCreateRequest {

    @NotBlank
    @Size(max = 128)
    @JsonDeserialize(using = StrictWorkflowScalarDeserializer.StringValue.class)
    private String name;

    @Size(max = 512)
    @JsonDeserialize(using = StrictWorkflowScalarDeserializer.StringValue.class)
    private String description;

    @Size(max = 16)
    @JsonDeserialize(using = StrictWorkflowScalarDeserializer.StringValue.class)
    private String recordType;

    @NotBlank
    @Size(max = 8)
    @JsonDeserialize(using = StrictWorkflowScalarDeserializer.StringValue.class)
    private String executionMode;

    @NotNull
    private JsonNode definition;

    @NotNull
    private JsonNode canvas;

    @JsonAnySetter
    public void rejectUnknownField(String field, JsonNode value) {
        throw new IllegalArgumentException("Unknown workflow field: " + field);
    }
}
