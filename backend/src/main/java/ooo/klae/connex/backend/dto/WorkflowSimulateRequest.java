package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Revision-pinned selected-record simulation request. */
public record WorkflowSimulateRequest(
    @NotNull
    @PositiveOrZero
    @JsonDeserialize(using = StrictWorkflowScalarDeserializer.IntegerValue.class)
    Integer expectedRevision,
    @NotNull
    @Positive
    @JsonDeserialize(using = StrictWorkflowScalarDeserializer.IntegerValue.class)
    Integer recordId
) { }
