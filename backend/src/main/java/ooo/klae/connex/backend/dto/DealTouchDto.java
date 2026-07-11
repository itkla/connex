package ooo.klae.connex.backend.dto;

/**
 * Most recent non-future interaction timestamp for a deal.
 */
public record DealTouchDto(int dealId, String touchedAt) {}
