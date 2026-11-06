package ooo.klae.connex.backend.dto;

/** Currency choices and one complete, currency-partitioned personal KPI window. */
public record MyWorkDealKpisDto(String currency, DealMetricsDto metrics, DealKpisDto kpis) {}
