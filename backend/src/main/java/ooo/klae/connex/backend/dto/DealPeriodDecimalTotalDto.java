package ooo.klae.connex.backend.dto;

import java.math.BigDecimal;

/**
 * Database-precision deal value aggregated for one analytics period.
 */
public record DealPeriodDecimalTotalDto(
    int bucketIndex,
    BigDecimal total
) {}
