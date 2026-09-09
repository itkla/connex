package ooo.klae.connex.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Schema-v2 pre-run condition and repeat policy. */
public record WorkflowEnrollment(
    @JsonInclude(JsonInclude.Include.NON_NULL) SegmentDefinition condition,
    Boolean oneActiveRun,
    Integer cooldownMinutes
) { }
