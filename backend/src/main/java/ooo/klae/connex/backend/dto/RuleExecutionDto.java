package ooo.klae.connex.backend.dto;

/** Safe public projection of a recent rule execution. */
public record RuleExecutionDto(
    int id,
    String triggerEntityType,
    Integer triggerEntityId,
    String status,
    String executedAt
) { }
