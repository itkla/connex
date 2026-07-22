package ooo.klae.connex.backend.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class DealRationaleDtoTest {
    private static final int DEAL_ID = 41;
    private static final String GENERATED_AT = "2026-07-09T18:30:00Z";

    @Test
    void of_createsAvailableRationale() {
        DealRationaleDto result = DealRationaleDto.of(
                DEAL_ID,
                "The deal is stalled.",
                List.of("Call the champion today.", "Escalate to the VP."),
                GENERATED_AT,
                2);

        assertEquals(DEAL_ID, result.getDealId());
        assertTrue(result.isAvailable());
        assertEquals("The deal is stalled.", result.getNarrative());
        assertEquals(List.of("Call the champion today.", "Escalate to the VP."), result.getActions());
        assertEquals(
                "The deal is stalled.\n• Call the champion today.\n• Escalate to the VP.",
                result.getRationale());
        assertEquals(GENERATED_AT, result.getGeneratedAt());
        assertEquals(2, result.getWarnings());
        assertNull(result.getReason());
    }

    @Test
    void of_withoutActions_flattensNarrativeOnly() {
        DealRationaleDto result = DealRationaleDto.of(DEAL_ID, "The deal is stalled.", List.of(), GENERATED_AT, 0);

        assertEquals("The deal is stalled.", result.getNarrative());
        assertEquals(List.of(), result.getActions());
        assertEquals("The deal is stalled.", result.getRationale());
    }

    @Test
    void unavailable_acceptsSupportedReasons() {
        for (String reason : List.of("not_configured", "provider_error", "not_at_risk")) {
            DealRationaleDto result = DealRationaleDto.unavailable(DEAL_ID, reason);

            assertEquals(DEAL_ID, result.getDealId());
            assertFalse(result.isAvailable());
            assertNull(result.getNarrative());
            assertNull(result.getActions());
            assertNull(result.getRationale());
            assertNull(result.getGeneratedAt());
            assertEquals(0, result.getWarnings());
            assertEquals(reason, result.getReason());
        }
    }

    @Test
    void unavailable_rejectsUnsupportedReason() {
        assertThrows(IllegalArgumentException.class, () -> DealRationaleDto.unavailable(DEAL_ID, "unknown"));
        assertThrows(IllegalArgumentException.class, () -> DealRationaleDto.unavailable(DEAL_ID, null));
    }
}
