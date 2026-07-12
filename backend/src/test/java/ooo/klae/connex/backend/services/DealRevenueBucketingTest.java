package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.dto.DealMonthDecimalTotalDto;
import ooo.klae.connex.backend.dto.DealMonthTotalDto;
import ooo.klae.connex.backend.dto.DealRevenueMonthBoundary;
import ooo.klae.connex.backend.dto.DealRevenueRangeDto;

class DealRevenueBucketingTest {
    @Test
    void historicalOffsetsApplyPerTimestampWhileWonSchedulesKeepTheirDate() {
        List<DealRevenueMonthBoundary> boundaries = DealService.revenueMonthBoundaries(
            new DealRevenueRangeDto(
                LocalDateTime.of(2026, 1, 1, 4, 30),
                LocalDateTime.of(2026, 7, 1, 3, 30)),
            ZoneId.of("America/New_York"));

        List<DealMonthTotalDto> totals = DealService.revenueClosedByMonth(
            List.of(new DealMonthDecimalTotalDto(2026, 2, BigDecimal.valueOf(20.0))),
            List.of(
                new DealMonthDecimalTotalDto(2025, 12, BigDecimal.valueOf(90.0)),
                new DealMonthDecimalTotalDto(2026, 6, BigDecimal.valueOf(10.0))));

        assertEquals(new DealRevenueMonthBoundary(
            2025, 12, LocalDateTime.of(2025, 12, 1, 5, 0), LocalDateTime.of(2026, 1, 1, 5, 0)),
            boundaries.getFirst());
        assertEquals(new DealRevenueMonthBoundary(
            2026, 6, LocalDateTime.of(2026, 6, 1, 4, 0), LocalDateTime.of(2026, 7, 1, 4, 0)),
            boundaries.getLast());
        assertEquals(List.of(
            new DealMonthTotalDto(2025, 12, 90.0),
            new DealMonthTotalDto(2026, 2, 20.0),
            new DealMonthTotalDto(2026, 6, 10.0)
        ), totals);
    }

    @Test
    void aggregateMergeUsesDecimalAddition() {
        assertEquals(
            List.of(new DealMonthTotalDto(2026, 1, 0.3)),
            DealService.revenueClosedByMonth(
                List.of(new DealMonthDecimalTotalDto(2026, 1, BigDecimal.valueOf(0.1))),
                List.of(new DealMonthDecimalTotalDto(2026, 1, BigDecimal.valueOf(0.2)))));
    }

    @Test
    void aggregateMergePreservesLargeDecimalCents() {
        assertEquals(
            new BigDecimal("90000000000000.10"),
            DealService.revenueClosedByMonth(
                List.of(new DealMonthDecimalTotalDto(
                    2026, 1, new BigDecimal("90000000000000.09"))),
                List.of(new DealMonthDecimalTotalDto(
                    2026, 1, new BigDecimal("0.01"))))
                .getFirst()
                .total());
    }
}
