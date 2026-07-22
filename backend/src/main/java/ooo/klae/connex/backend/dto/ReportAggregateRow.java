package ooo.klae.connex.backend.dto;

import java.math.BigDecimal;

/**
 * One grouped deterministic aggregate returned by the report mapper.
 * @param groupKey stable group key
 * @param groupLabel display label
 * @param unit value unit or currency
 * @param value aggregate value
 */
public record ReportAggregateRow(String groupKey, String groupLabel, String unit, BigDecimal value) {
}
