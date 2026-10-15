package ooo.klae.connex.backend.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.util.OneTimeTokenDigest;
import ooo.klae.connex.backend.support.UnsubscribeCorrelationFixture;

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
        assertEquals(unrelated, CampaignDeliveryDiagnostics.correlationForm(unrelated, recorded(Set.of(digest))));
        for (String copy : List.of(bearer.toUpperCase(java.util.Locale.ROOT), "Ab09".repeat(16), bearer)) {
            assertEquals("é:" + digest + ":0" + digest + "f:" + unrelated,
                    CampaignDeliveryDiagnostics.correlationForm("é:" + copy + ":0" + copy + "f:" + unrelated, recorded(Set.of(digest))));
        }
        assertNull(CampaignDeliveryDiagnostics.correlationForm(null, recorded(Set.of(digest))));
    }

    @Test
    void everyOriginalWindowIsProbedAndOnlyScopedDigestsMatch() {
        String run = "0123456789abcdef".repeat(5);
        List<String> probes = new ArrayList<>();
        String matched = OneTimeTokenDigest.sha256(run.substring(5, 69));
        assertEquals(run.substring(0, 5) + matched + run.substring(69),
                CampaignDeliveryDiagnostics.correlationForm(run, hash -> {
                    probes.addAll(hash);
                    return hash.contains(matched) ? Set.of(matched) : Set.of();
                }));
        for (int start = 0; start + 64 <= run.length(); start++) {
            assertTrue(probes.contains(OneTimeTokenDigest.sha256(run.substring(start, start + 64))));
        }
        assertEquals(run, CampaignDeliveryDiagnostics.correlationForm(run, recorded(Set.of())));
        assertEquals("short-id", CampaignDeliveryDiagnostics.correlationForm("short-id", hash -> {
            throw new AssertionError("Short ids must not probe the database");
        }));
    }
    @Test
    void overlappingBearerCopiesUseTheSameLeftmostReplacementAsHistoricalRekeying() {
        String digest = OneTimeTokenDigest.sha256("a".repeat(64));
        assertEquals(digest + "a", CampaignDeliveryDiagnostics.correlationForm("a".repeat(65), recorded(Set.of(digest))));
        assertEquals(digest + digest, CampaignDeliveryDiagnostics.correlationForm("a".repeat(128), recorded(Set.of(digest))));
    }

    @Test
    void correlationRescansOutputUntilOverlappingBearersAreGone() {
        UnsubscribeCorrelationFixture fixture = UnsubscribeCorrelationFixture.create();
        Set<String> digests = Set.of(OneTimeTokenDigest.sha256(fixture.first()),
                OneTimeTokenDigest.sha256(fixture.second()));
        String expected = OneTimeTokenDigest.sha256(fixture.first()).substring(0, 63)
                + OneTimeTokenDigest.sha256(fixture.second());
        for (String identifier : List.of(fixture.identifier(), fixture.identifier().toUpperCase(java.util.Locale.ROOT))) {
            String result = CampaignDeliveryDiagnostics.correlationForm(identifier, recorded(digests));
            assertEquals(expected, result);
            assertTrue(!result.toLowerCase(java.util.Locale.ROOT).contains(fixture.first()));
            assertTrue(!result.toLowerCase(java.util.Locale.ROOT).contains(fixture.second()));
        }
    }

    @Test
    void correlationFailsClosedAfterFourReplacementPassesAndAResidualCheck() {
        List<String> probes = new ArrayList<>();
        String bearer = "a".repeat(64);
        List<String> expected = new ArrayList<>();
        String current = bearer;
        for (int pass = 0; pass < 5; pass++) {
            current = OneTimeTokenDigest.sha256(current);
            expected.add(current);
        }
        Set<String> recordedDigests = Set.copyOf(expected);
        assertNull(CampaignDeliveryDiagnostics.correlationForm(bearer, hash -> {
            probes.addAll(hash);
            return hash.stream().filter(recordedDigests::contains).collect(java.util.stream.Collectors.toSet());
        }));
        assertEquals(expected, probes);
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

    @Test
    void maximumHexIdentifierUsesOneBatchPerPassAndCachesAllMembership() {
        List<Set<String>> batches = new ArrayList<>();
        assertNull(CampaignDeliveryDiagnostics.correlationForm(UnsubscribeCorrelationFixture.longHexIdentifier(), hashes -> {
            batches.add(hashes);
            return hashes;
        }));
        assertEquals(5, batches.size());
        assertEquals(192, batches.getFirst().size());
        Set<String> seen = new HashSet<>();
        for (Set<String> batch : batches) {
            assertTrue(java.util.Collections.disjoint(seen, batch));
            seen.addAll(batch);
        }
        List<Set<String>> nextCall = new ArrayList<>();
        assertEquals(UnsubscribeCorrelationFixture.longHexIdentifier(), CampaignDeliveryDiagnostics.correlationForm(
                UnsubscribeCorrelationFixture.longHexIdentifier(), hashes -> {
                    nextCall.add(hashes);
                    return Set.of();
                }));
        assertEquals(List.of(batches.getFirst()), nextCall);
    }

    @Test
    void batchResultsMatchThePreviousSingleProbeImplementation() {
        var fixture = UnsubscribeCorrelationFixture.create();
        String bearer = "ab09".repeat(16);
        List<String> identifiers = new ArrayList<>(List.of("short-id", "CD".repeat(32), bearer,
                bearer.toUpperCase(Locale.ROOT), "Ab09".repeat(16), "é:0" + bearer + "f", fixture.identifier(),
                fixture.identifier().toUpperCase(Locale.ROOT), fixture.selfOverlappingIdentifier(),
                "a".repeat(65), "a".repeat(128), UnsubscribeCorrelationFixture.longHexIdentifier(), "😀".repeat(255)));
        identifiers.add(null);
        Set<String> chain = new HashSet<>();
        String current = "a".repeat(64);
        identifiers.add(current);
        for (int pass = 0; pass < 5; pass++) {
            current = OneTimeTokenDigest.sha256(current);
            chain.add(current);
        }
        for (Set<String> scope : List.of(Set.<String>of(), Set.of(OneTimeTokenDigest.sha256(bearer),
                OneTimeTokenDigest.sha256(fixture.first()), OneTimeTokenDigest.sha256(fixture.second()),
                OneTimeTokenDigest.sha256("a".repeat(64))), chain)) {
            for (String identifier : identifiers) {
                assertEquals(previousSingleProbeCorrelation(identifier, scope::contains),
                        CampaignDeliveryDiagnostics.correlationForm(identifier, recorded(scope)));
            }
        }
    }

    private static Function<Set<String>, Set<String>> recorded(Set<String> scope) {
        return hashes -> hashes.stream().filter(scope::contains).collect(java.util.stream.Collectors.toSet());
    }

    /** Frozen single-probe implementation from the reviewed baseline for compatibility checks. */
    private static String previousSingleProbeCorrelation(String identifier, Predicate<String> recordedDigest) {
        if (identifier == null) {
            return null;
        }
        if (!CampaignDeliveryDiagnostics.fitsIdentifierColumn(identifier)) {
            throw new IllegalArgumentException("Provider identifier exceeds storage limit");
        }
        Map<String, Boolean> matches = new HashMap<>();
        String current = identifier;
        for (int pass = 0; pass <= 4; pass++) {
            Matcher runs = Pattern.compile("[a-fA-F0-9]{64,}").matcher(current);
            char[] result = null;
            int replacedUntil = 0;
            while (runs.find()) {
                for (int start = runs.start(); start + 64 <= runs.end(); start++) {
                    String digest = OneTimeTokenDigest.sha256(
                            current.substring(start, start + 64).toLowerCase(Locale.ROOT));
                    if (matches.computeIfAbsent(digest, recordedDigest::test) && start >= replacedUntil) {
                        if (pass == 4) {
                            return null;
                        }
                        if (result == null) {
                            result = current.toCharArray();
                        }
                        digest.getChars(0, 64, result, start);
                        replacedUntil = start + 64;
                    }
                }
            }
            if (result == null) {
                return current;
            }
            current = new String(result);
        }
        return null;
    }

}
