package ooo.klae.connex.backend.dto;

import java.math.BigDecimal;

/**
 * Server-computed roll-up of a deal's line items. Recurring and one-time totals are kept separate
 * so downstream consumers never double-count them.
 *
 * @param currency       the deal currency all line items share
 * @param subtotal       sum of line subtotals (pre-tax, post-discount)
 * @param tax            sum of line taxes
 * @param oneTimeTotal   sum of line totals classified one_time
 * @param recurringTotal sum of line totals classified recurring
 * @param grandTotal     sum of all line totals
 */
public record DealLineItemTotalsDto(
    String currency,
    BigDecimal subtotal,
    BigDecimal tax,
    BigDecimal oneTimeTotal,
    BigDecimal recurringTotal,
    BigDecimal grandTotal
) {}
