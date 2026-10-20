package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import ooo.klae.connex.backend.beans.AuditLog;
import ooo.klae.connex.backend.exceptions.ConflictException;
import tools.jackson.databind.ObjectMapper;

/** Version parsing never broadens durable accepted authority. */
class AiAgentPurgeAcceptanceTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void v1ReplayCannotBecomeV2() {
        var acceptance = accepted(1);
        assertEquals(8, acceptance.metadata().size());
        assertEquals(acceptance, AiAgentPurgeAcceptance.from(receipt(acceptance.metadata()), json));
        assertThrows(ConflictException.class, () -> acceptance.requireAcknowledgments(
                AiAgentPurgeAcceptance.ASSISTANT_SCOPE, AiAgentPurgeAcceptance.ASSISTANT_CUTOFF_POLICY));
    }

    @Test
    void v2RecoveryUsesAcceptedCutoff() {
        var acceptance = accepted(2);
        assertEquals("9007199254740992", acceptance.metadata().get("cutoffGeneration"));
        assertEquals("9007199254740993", acceptance.metadata().get("invalidatedGeneration"));
        assertEquals(acceptance, AiAgentPurgeAcceptance.from(receipt(acceptance.metadata()), json));
        assertThrows(ConflictException.class, () -> acceptance.requireAcknowledgments(
                AiAgentPurgeAcceptance.SCOPE, AiAgentPurgeAcceptance.CUTOFF_POLICY));
    }

    @Test
    void onlyExactAcknowledgmentPairsAreAccepted() {
        assertTrue(AiAgentPurgeAcceptance.acknowledges(AiAgentPurgeAcceptance.ASSISTANT_SCOPE,
                AiAgentPurgeAcceptance.ASSISTANT_CUTOFF_POLICY));
        assertEquals(false, AiAgentPurgeAcceptance.acknowledges(AiAgentPurgeAcceptance.SCOPE,
                AiAgentPurgeAcceptance.ASSISTANT_CUTOFF_POLICY));
        assertEquals(false, AiAgentPurgeAcceptance.acknowledges(AiAgentPurgeAcceptance.ASSISTANT_SCOPE,
                AiAgentPurgeAcceptance.CUTOFF_POLICY));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "01", "1.0", "1e2", "9223372036854775808", "", " 1"})
    void refusesNonCanonicalGenerationMetadata(String generation) {
        Map<String, Object> metadata = new LinkedHashMap<>(accepted(2).metadata());
        metadata.put("cutoffGeneration", generation);
        assertThrows(IllegalStateException.class, () -> AiAgentPurgeAcceptance.from(receipt(metadata), json));
    }

    @Test
    void refusesUnknownOrNumericGenerationFields() {
        Map<String, Object> metadata = new LinkedHashMap<>(accepted(2).metadata());
        metadata.put("cutoffGeneration", 1);
        AuditLog numeric = receipt(metadata);
        assertThrows(IllegalStateException.class, () -> AiAgentPurgeAcceptance.from(numeric, json));
        metadata = new LinkedHashMap<>(accepted(1).metadata());
        metadata.put("cutoffGeneration", "0");
        AuditLog row = receipt(metadata);
        assertThrows(IllegalStateException.class, () -> AiAgentPurgeAcceptance.from(row, json));
    }

    private AiAgentPurgeAcceptance accepted(int version) {
        return new AiAgentPurgeAcceptance(42, 11, 19, UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), List.of(7), "2026-10-19T00:00:00Z", version,
                version == 1 ? AiAgentPurgeAcceptance.SCOPE : AiAgentPurgeAcceptance.ASSISTANT_SCOPE,
                version == 1 ? AiAgentPurgeAcceptance.CUTOFF_POLICY : AiAgentPurgeAcceptance.ASSISTANT_CUTOFF_POLICY,
                version == 1 ? null : 9_007_199_254_740_992L,
                version == 1 ? null : 9_007_199_254_740_993L);
    }

    private AuditLog receipt(Map<String, Object> metadata) {
        AuditLog row = new AuditLog();
        row.setId(42);
        row.setOrgId(11);
        row.setAction(AiAgentPurgeAcceptance.ACCEPTED);
        row.setChanges(json.writeValueAsString(metadata));
        return row;
    }
}
