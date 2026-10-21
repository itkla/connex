package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.assistant.AiAssistantToolResult;
import ooo.klae.connex.backend.ai.assistant.AiChatResourceRegistry;
import tools.jackson.databind.json.JsonMapper;

class AiAgentSourceObservationsTest {
    private static final Instant ORIGINAL = Instant.parse("2026-10-19T00:00:00.123456Z");
    private static final String HASH = "a".repeat(64);
    private final MutableClock clock = new MutableClock(ORIGINAL);
    private final JsonMapper json = JsonMapper.builder().build();
    private final AiAgentEvidenceService evidence = new AiAgentEvidenceService(json, clock);

    @AfterEach
    void clear() {
        evidence.clear();
    }

    @Test
    void capturesObservationBeforeRetrieval() {
        var policy = policy();
        evidence.begin(policy);
        policy.execute(() -> {
            AiAgentEvidenceService.beforeRead("get_record");
            clock.current = ORIGINAL.plusSeconds(30);
            AiAgentEvidenceService.capture("get_record", json.createObjectNode(),
                    new AiAssistantToolResult(Map.of("name", "Selected"), List.of()), new AiChatResourceRegistry());
            assertEquals(ORIGINAL, AiAgentEvidenceService.currentAnchors().firstObservedAt());
            return null;
        });
    }

    @Test
    void continuationPreservesEarliestObservation() {
        var original = observations(ORIGINAL);
        var recovered = original.merge(observations(ORIGINAL.plusSeconds(300)));
        assertEquals(original, recovered);
        var codec = new AiAgentSourceObservationsCodec();
        assertEquals(original, codec.decode(codec.encode(recovered)));
    }

    @Test
    void revalidationIgnoresObservationTimeForHashEquality() {
        var result = new AiAssistantToolResult(Map.of("name", "Selected"), List.of());
        var reference = evidence.reference("person", 41, "record", 0, result);
        var first = new AiAgentSourceObservations.Observation("person", 41, "record", 0,
                reference.version().sha256(), ORIGINAL);
        var later = new AiAgentSourceObservations.Observation("person", 41, "record", 0,
                reference.version().sha256(), ORIGINAL.plusSeconds(600));
        assertEquals(first.reference(), later.reference());
        assertEquals(List.of(result), evidence.revalidate(List.of(first.reference()), ignored -> result));
    }

    @Test
    void rejectsMissingFutureAndContradictoryAnchors() {
        assertThrows(IllegalArgumentException.class,
                () -> new AiAgentSourceObservations(1, List.of()).requireComplete("person", 41, ORIGINAL));
        assertThrows(IllegalArgumentException.class,
                () -> observations(ORIGINAL.plusSeconds(1)).requireComplete("person", 41, ORIGINAL));
        var changed = new AiAgentSourceObservations(1, List.of(new AiAgentSourceObservations.Observation(
                "person", 41, "record", 0, "b".repeat(64), ORIGINAL)));
        assertThrows(IllegalArgumentException.class, () -> observations(ORIGINAL).merge(changed));
        assertThrows(IllegalArgumentException.class, () -> new AiAgentSourceObservationsCodec().decode(
                "{\"schemaVersion\":1,\"sources\":[],\"extra\":true}"));
        assertThrows(IllegalArgumentException.class, () -> new AiAgentSourceObservations.Observation(
                "person", 41, "record", 0, HASH, ORIGINAL.plusNanos(1)));
    }

    @Test
    void duplicateKeysNormalizeToWithheldContentRefusal() {
        var failure = assertThrows(IllegalArgumentException.class, () -> new AiAgentSourceObservationsCodec().decode(
                "{\"schemaVersion\":1,\"sources\":[],\"schemaVersion\":1}"));
        assertEquals("mission_continuation_refused", failure.getMessage());
    }

    private static AiAgentSourceObservations observations(Instant at) {
        return new AiAgentSourceObservations(1, List.of(new AiAgentSourceObservations.Observation(
                "person", 41, "record", 0, HASH, at)));
    }

    private static AiAgentExecutionPolicy policy() {
        return new AiAgentExecutionPolicy(31, 37, 1, null, null, new AiAgentScopePolicy("person", 41),
                4, ORIGINAL.plusSeconds(180), null, null, null, null, 3, 1, 1, null, true, 65536);
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
