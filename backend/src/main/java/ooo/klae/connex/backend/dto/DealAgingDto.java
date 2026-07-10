package ooo.klae.connex.backend.dto;

/**
 * Open-deal aging counts grouped by stage.
 */
public record DealAgingDto(
    Integer stageId,
    long fresh,
    long active,
    long aging,
    long stalled
) {}
