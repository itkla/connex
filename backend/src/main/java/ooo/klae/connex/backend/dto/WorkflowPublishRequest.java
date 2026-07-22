package ooo.klae.connex.backend.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Optimistic revision precondition for publishing a workflow draft. */
@Data
@NoArgsConstructor
public class WorkflowPublishRequest {

    @NotNull
    @PositiveOrZero
    @JsonDeserialize(using = StrictWorkflowScalarDeserializer.IntegerValue.class)
    private Integer expectedRevision;

    @JsonAnySetter
    public void rejectUnknownField(String field, JsonNode value) {
        throw new IllegalArgumentException("Unknown workflow field: " + field);
    }
}
