package ooo.klae.connex.backend.dto;

import java.math.BigDecimal;

/**
 * Database-precision deal value aggregated for one calendar month.
 */
public record DealMonthDecimalTotalDto(
    int year,
    int month,
    BigDecimal total
) {}
