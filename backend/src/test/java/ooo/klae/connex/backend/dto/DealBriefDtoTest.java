package ooo.klae.connex.backend.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class DealBriefDtoTest {
    private static final int DEAL_ID = 41;
    private static final String GENERATED_AT = "2026-07-09T18:30:00Z";

    @Test
    void of_createsAvailableBrief() {
        List<DealBriefDto.Section> sections = List.of(
                new DealBriefDto.Section(
                        "Who they are",
                        "Acme Corp. renewal.",
                        List.of(new DealBriefDto.Citation("deal", DEAL_ID))),
                new DealBriefDto.Section(
                        "Next step",
                        "Call the champion.",
                        List.of(new DealBriefDto.Citation("person", 73))));

        DealBriefDto result = DealBriefDto.of(DEAL_ID, sections, GENERATED_AT, 3, true);

        assertEquals(DEAL_ID, result.getDealId());
        assertTrue(result.isAvailable());
        assertEquals(sections, result.getSections());
        assertEquals(
                "Who they are\nAcme Corp. renewal.\n\nNext step\nCall the champion.",
                result.getBrief());
        assertEquals(GENERATED_AT, result.getGeneratedAt());
        assertEquals(3, result.getWarnings());
        assertTrue(result.isDegraded());
        assertNull(result.getReason());
    }

    @Test
    void unavailable_acceptsSupportedReasons() {
        for (String reason : List.of(
                "not_configured", "provider_error", "rate_limited", "insufficient_data")) {
            DealBriefDto result = DealBriefDto.unavailable(DEAL_ID, reason);

            assertEquals(DEAL_ID, result.getDealId());
            assertFalse(result.isAvailable());
            assertNull(result.getSections());
            assertNull(result.getBrief());
            assertNull(result.getGeneratedAt());
            assertEquals(0, result.getWarnings());
            assertFalse(result.isDegraded());
            assertEquals(reason, result.getReason());
        }
    }

    @Test
    void unavailable_rejectsUnsupportedReason() {
        assertThrows(IllegalArgumentException.class, () -> DealBriefDto.unavailable(DEAL_ID, "not_at_risk"));
        assertThrows(IllegalArgumentException.class, () -> DealBriefDto.unavailable(DEAL_ID, "unknown"));
    }
}
