package ooo.klae.connex.backend.dto;

/** Safe list projection of a rule's latest execution. */
public record RuleExecutionSummaryDto(
    String status,
    String executedAt
) { }
