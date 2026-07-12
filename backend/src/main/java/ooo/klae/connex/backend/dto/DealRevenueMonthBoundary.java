package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;

/** One viewer-local calendar month represented as a half-open UTC timestamp interval. */
public record DealRevenueMonthBoundary(
    int year,
    int month,
    LocalDateTime startUtc,
    LocalDateTime endUtc
) {}
