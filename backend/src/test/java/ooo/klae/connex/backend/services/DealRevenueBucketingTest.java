package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.dto.DealRevenueMonthBoundary;
import ooo.klae.connex.backend.dto.DealRevenueRangeDto;

class DealRevenueBucketingTest {
    @Test
    void historicalOffsetsApplyToCanonicalCloseTimestamps() {
        List<DealRevenueMonthBoundary> boundaries = DealService.revenueMonthBoundaries(
            new DealRevenueRangeDto(
                LocalDateTime.of(2026, 1, 1, 4, 30),
                LocalDateTime.of(2026, 7, 1, 3, 30)),
            ZoneId.of("America/New_York"));

        assertEquals(new DealRevenueMonthBoundary(
            2025, 12, LocalDateTime.of(2025, 12, 1, 5, 0), LocalDateTime.of(2026, 1, 1, 5, 0)),
            boundaries.getFirst());
        assertEquals(new DealRevenueMonthBoundary(
            2026, 6, LocalDateTime.of(2026, 6, 1, 4, 0), LocalDateTime.of(2026, 7, 1, 4, 0)),
            boundaries.getLast());
    }
}
