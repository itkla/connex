package ooo.klae.connex.backend.dto;

import java.util.List;

/**
 * Highest-value open and won deals in the active workspace.
 */
public record DealTopDto(
    List<DealSummaryDto> topOpen,
    List<DealSummaryDto> topWon
) {}
