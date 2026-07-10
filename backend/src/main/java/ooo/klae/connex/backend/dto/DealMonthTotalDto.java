package ooo.klae.connex.backend.dto;

/**
 * Deal value aggregated for one calendar month.
 */
public record DealMonthTotalDto(
    int year,
    int month,
    double total
) {}
