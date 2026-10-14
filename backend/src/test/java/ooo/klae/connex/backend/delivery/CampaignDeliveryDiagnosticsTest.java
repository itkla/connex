package ooo.klae.connex.backend.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** Pins exact-bearer removal and defensive redaction without discarding surrounding diagnostics. */
class CampaignDeliveryDiagnosticsTest {
    @Test
    void scrubsEveryOccurrenceOfTheExactBearerBeforeDefensiveHexFiltering() {
        String bearer = "current-attempt-bearer";
        assertEquals("554 rejected [redacted]; echoed [redacted] retained",
                CampaignDeliveryDiagnostics.scrub("554 rejected " + bearer + "; echoed " + bearer + " retained", bearer));
    }

    @Test
    void scrubsMixedCaseHexRunsAndLongRunsWithoutRequiringWordBoundaries() {
        assertEquals("token=[redacted] sibling=[redacted] retained abc123",
                CampaignDeliveryDiagnostics.scrub("token=" + "aB09".repeat(16)
                        + " sibling=" + "C".repeat(128) + " retained abc123", null));
    }

    @Test
    void preservesNullAndOrdinaryDiagnosticText() {
        assertNull(CampaignDeliveryDiagnostics.scrub(null, "a".repeat(64)));
        assertEquals("554 rate limit; retry tomorrow", CampaignDeliveryDiagnostics.scrub(
                "554 rate limit; retry tomorrow", "a".repeat(64)));
    }

    @Test
    void identifierScrubbingPreservesOpaqueHexIdsAndRemovesOnlyExactBearerOccurrences() {
        String bearer = "a".repeat(64);
        String identifier = "AB".repeat(32);
        assertEquals(identifier, CampaignDeliveryDiagnostics.scrubIdentifier(identifier, bearer));
        assertEquals("[redacted]:" + identifier + ":[redacted]",
                CampaignDeliveryDiagnostics.scrubIdentifier(bearer + ":" + identifier + ":" + bearer, bearer));
        assertNull(CampaignDeliveryDiagnostics.scrubIdentifier(null, bearer));
        assertEquals(identifier, CampaignDeliveryDiagnostics.scrubIdentifier(identifier, null));
        assertEquals(identifier, CampaignDeliveryDiagnostics.scrubIdentifier(identifier, ""));
    }
}
