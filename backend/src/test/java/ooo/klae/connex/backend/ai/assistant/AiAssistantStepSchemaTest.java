package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AiAssistantStepSchemaTest {
    private final JsonMapper objectMapper = JsonMapper.builder().build();

    @Test
    void schemaConstrainsExclusiveToolAndFinalShapesFromTheCatalog() {
        AiAssistantStepSchema schema = new AiAssistantStepSchema(
                objectMapper, new AiAssistantToolCatalog());

        JsonNode root = schema.responseSchema().schema();
        assertEquals("ask_connex_step", schema.responseSchema().name());
        assertEquals("object", root.path("type").asString());
        assertEquals(2, root.path("required").size());
        assertFalse(root.path("additionalProperties").asBoolean());
        assertFalse(root.has("anyOf"));
        JsonNode toolAlternatives = root.path("properties").path("tool").path("anyOf");
        assertEquals(2, toolAlternatives.size());
        assertTrue(toolAlternatives.toString().contains("search_records"));
        assertTrue(toolAlternatives.toString().contains("aggregate_metric"));
        JsonNode finalAlternatives = root.path("properties").path("final").path("anyOf");
        assertEquals("null", finalAlternatives.path(0).path("type").asString());
        JsonNode finalShape = finalAlternatives.path(1);
        assertFalse(finalShape.path("additionalProperties").asBoolean());
        assertEquals(4, finalShape.path("required").size());
        assertEquals(3, finalShape.path("properties").path("suggestions").path("maxItems").asInt());
        assertEquals(160, finalShape.path("properties").path("suggestions")
                .path("items").path("maxLength").asInt());
        assertEquals(2, finalShape.path("properties").path("title").path("anyOf").size());
    }
}
