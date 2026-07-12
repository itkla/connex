package ooo.klae.connex.backend.dto;

import java.math.BigDecimal;

/**
 * Deal value aggregated for one calendar month.
 */
public record DealMonthTotalDto(
    int year,
    int month,
    BigDecimal total
) {
    public DealMonthTotalDto(int year, int month, double total) {
        this(year, month, BigDecimal.valueOf(total));
    }
}
