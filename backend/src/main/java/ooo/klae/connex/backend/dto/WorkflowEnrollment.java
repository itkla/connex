package ooo.klae.connex.backend.dto;

/** Schema-v2 pre-run condition and repeat policy. */
public record WorkflowEnrollment(
    SegmentDefinition condition,
    Boolean oneActiveRun,
    Integer cooldownMinutes
) { }
