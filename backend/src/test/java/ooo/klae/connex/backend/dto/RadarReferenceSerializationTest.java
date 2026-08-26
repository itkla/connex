package ooo.klae.connex.backend.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

/**
 * Radar evidence references resolve their label at read time, so a detector must never write one
 * into stored evidence and an older stored payload must still deserialize.
 */
class RadarReferenceSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void anUnresolvedReferenceSerializesExactlyAsEarlierBinariesWroteIt() {
        String json = objectMapper.writeValueAsString(
            new RadarResponseDto.Reference("person", 18));

        assertEquals("{\"type\":\"person\",\"id\":18}", json);
        assertFalse(json.contains("label"));
    }

    @Test
    void detectorBuiltEvidenceNeverPersistsAReferenceLabel() {
        String json = objectMapper.writeValueAsString(List.of(
            new RadarResponseDto.Evidence(
                "stakeholder_cold",
                Map.of("severity", "high", "personId", 18),
                List.of(
                    new RadarResponseDto.Reference("deal", 4),
                    new RadarResponseDto.Reference("person", 18)))));

        assertFalse(json.contains("\"label\""));
    }

    @Test
    void storedEvidenceWithoutLabelsDeserializesWithUnresolvedReferences() {
        List<RadarResponseDto.Evidence> evidence = objectMapper.readValue(
            "[{\"type\":\"relationship_temperature\",\"parameters\":{},"
                + "\"references\":[{\"type\":\"person\",\"id\":18}]}]",
            new tools.jackson.core.type.TypeReference<List<RadarResponseDto.Evidence>>() { });

        RadarResponseDto.Reference reference = evidence.getFirst().references().getFirst();
        assertEquals("person", reference.type());
        assertEquals(18, reference.id());
        assertNull(reference.label());
    }

    @Test
    void aResolvedReferenceExposesTheLabelToClients() {
        String json = objectMapper.writeValueAsString(
            new RadarResponseDto.Reference("person", 18).withLabel("Aiko Tanaka"));

        assertTrue(json.contains("\"label\":\"Aiko Tanaka\""));
    }
}
