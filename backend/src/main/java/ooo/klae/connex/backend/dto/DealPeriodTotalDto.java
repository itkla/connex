package ooo.klae.connex.backend.dto;

/**
 * Public deal total for one viewer-local analytics period.
 */
public record DealPeriodTotalDto(
    String periodStart,
    double total
) {}
