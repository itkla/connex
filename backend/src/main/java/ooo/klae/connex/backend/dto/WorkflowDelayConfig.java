package ooo.klae.connex.backend.dto;

import tools.jackson.databind.annotation.JsonDeserialize;

/** Schema-v1 duration Delay configuration expressed only in integer seconds. */
public record WorkflowDelayConfig(
    @JsonDeserialize(using = StrictWorkflowScalarDeserializer.IntegerValue.class)
    Integer durationSeconds
) { }
