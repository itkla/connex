package ooo.klae.connex.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Schema-v2 terminal outcome and optional stable reason. */
public record WorkflowEndConfig(
    String outcome,
    @JsonInclude(JsonInclude.Include.NON_NULL) String reason
) { }
