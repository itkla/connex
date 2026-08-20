package ooo.klae.connex.backend.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.exceptions.BadRequestException;

/** Validates the browser's warmth request syntax before any warmth aggregate is computed. */
class WarmthFilterTest {
    private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");

    @Test
    void anUnfilteredUnsortedRequestNeedsNoWarmthComputation() {
        assertNull(WarmthFilter.fromRequest(null, false, null, null, NOW));
        assertNull(WarmthFilter.fromRequest(List.of(), false, null, "name", NOW));
    }

    @Test
    void aWarmthSortAloneStillResolvesTheModelWithoutRestrictingAnyRow() {
        WarmthFilter filter = WarmthFilter.fromRequest(null, false, null, "WARMTH", NOW);

        assertNotNull(filter);
        assertFalse(filter.restrictsBands());
        assertTrue(filter.bands().isEmpty());
        assertNull(filter.goesColdWithinDays());
        assertNotNull(filter.model());
    }

    @Test
    void bandKeysNormalizeAndTheNoHistoryKeyResolvesToItsOwnFlag() {
        WarmthFilter filter = WarmthFilter.fromRequest(
            List.of(" Hot ", "COOL", WarmthFilter.NO_WARMTH_KEY), false, null, null, NOW);

        assertEquals(Set.of("hot", "cool"), filter.bands());
        assertTrue(filter.noWarmth());
        assertTrue(filter.restrictsBands());
    }

    @Test
    void unknownBandsAndOutOfRangeHorizonsAreRefusedAtTheBoundary() {
        assertThrows(BadRequestException.class,
            () -> WarmthFilter.fromRequest(List.of("lukewarm"), false, null, null, NOW));
        assertThrows(BadRequestException.class,
            () -> WarmthFilter.fromRequest(List.of("rising"), false, null, null, NOW));
        assertThrows(BadRequestException.class,
            () -> WarmthFilter.fromRequest(null, false, 0, null, NOW));
        assertThrows(BadRequestException.class,
            () -> WarmthFilter.fromRequest(null, false, 3651, null, NOW));
    }

    @Test
    void theBrowserHorizonsTheDecayDrillThroughOffersAreAccepted() {
        for (int horizon : List.of(30, 60, 90)) {
            WarmthFilter filter = WarmthFilter.fromRequest(null, false, horizon, null, NOW);
            assertEquals(horizon, filter.goesColdWithinDays());
        }
    }

    @Test
    void theModelPublishesStrictlyOrderedBandBoundariesForSql() {
        WarmthFilter filter = WarmthFilter.forScoring(NOW);

        assertTrue(filter.model().coolMinimumRawWeight() < filter.model().warmMinimumRawWeight());
        assertTrue(filter.model().warmMinimumRawWeight() < filter.model().hotMinimumRawWeight());
        assertTrue(filter.model().coldRawWeight() > 0.0);
    }

    @Test
    void aFilterCannotBeConstructedWithAnUnknownBandOrImpossibleHorizon() {
        assertThrows(IllegalArgumentException.class, () -> new WarmthFilter(
            Set.of("tepid"), false, null,
            WarmthFilter.forScoring(NOW).reference(), WarmthFilter.forScoring(NOW).model()));
        assertThrows(IllegalArgumentException.class, () -> new WarmthFilter(
            Set.of("hot"), false, -1,
            WarmthFilter.forScoring(NOW).reference(), WarmthFilter.forScoring(NOW).model()));
    }
}
