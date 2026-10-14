package ooo.klae.connex.backend.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.util.OneTimeTokenDigest;

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
    void correlationPreservesUnrelatedIdsAndRecognizesCaseVariantsInsideLongerRuns() {
        String bearer = "ab09".repeat(16);
        String digest = OneTimeTokenDigest.sha256(bearer);
        String unrelated = "CD".repeat(32);
        assertEquals(unrelated, CampaignDeliveryDiagnostics.correlationForm(unrelated, digest::equals));
        for (String copy : List.of(bearer.toUpperCase(java.util.Locale.ROOT), "Ab09".repeat(16), bearer)) {
            assertEquals("é:" + digest + ":0" + digest + "f:" + unrelated,
                    CampaignDeliveryDiagnostics.correlationForm("é:" + copy + ":0" + copy + "f:" + unrelated, digest::equals));
        }
        assertNull(CampaignDeliveryDiagnostics.correlationForm(null, digest::equals));
    }

    @Test
    void everyOriginalWindowIsProbedAndOnlyScopedDigestsMatch() {
        String run = "0123456789abcdef".repeat(5);
        List<String> probes = new ArrayList<>();
        String matched = OneTimeTokenDigest.sha256(run.substring(5, 69));
        assertEquals(run.substring(0, 5) + matched + run.substring(69),
                CampaignDeliveryDiagnostics.correlationForm(run, hash -> {
                    probes.add(hash);
                    return matched.equals(hash);
                }));
        for (int start = 0; start + 64 <= run.length(); start++) {
            assertTrue(probes.contains(OneTimeTokenDigest.sha256(run.substring(start, start + 64))));
        }
        assertEquals(run, CampaignDeliveryDiagnostics.correlationForm(run, Set.<String>of()::contains));
        assertEquals("short-id", CampaignDeliveryDiagnostics.correlationForm("short-id", hash -> {
            throw new AssertionError("Short ids must not probe the database");
        }));
    }
    @Test
    void overlappingBearerCopiesUseTheSameLeftmostReplacementAsHistoricalRekeying() {
        String digest = OneTimeTokenDigest.sha256("a".repeat(64));
        assertEquals(digest + "a", CampaignDeliveryDiagnostics.correlationForm("a".repeat(65), digest::equals));
        assertEquals(digest + digest, CampaignDeliveryDiagnostics.correlationForm("a".repeat(128), digest::equals));
    }

    @Test
    void oversizedIdsAreRejectedBeforeAnyDatabaseProbe() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> CampaignDeliveryDiagnostics.correlationForm("a".repeat(256), hash -> {
                    throw new AssertionError("Oversized ids must not probe the database");
                }));
        assertEquals("Provider identifier exceeds storage limit", exception.getMessage());
        assertEquals("😀".repeat(255), CampaignDeliveryDiagnostics.correlationForm("😀".repeat(255), hash -> {
            throw new AssertionError("Non-hex ids must not probe the database");
        }));
    }

}
