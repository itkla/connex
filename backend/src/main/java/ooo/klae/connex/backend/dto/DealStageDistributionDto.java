package ooo.klae.connex.backend.dto;

/**
 * Open and closed deal totals for a stage in a pipeline.
 */
public record DealStageDistributionDto(
    Integer stageId,
    Integer pipelineId,
    long openCount,
    double openValue,
    long closedCount,
    double closedValue
) {}
