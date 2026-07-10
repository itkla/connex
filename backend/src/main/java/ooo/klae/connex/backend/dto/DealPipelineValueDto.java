package ooo.klae.connex.backend.dto;

/**
 * Realized won value and current open pipeline grouped by pipeline.
 */
public record DealPipelineValueDto(
    Integer pipelineId,
    double wonValue,
    double openValue,
    long openCount
) {}
