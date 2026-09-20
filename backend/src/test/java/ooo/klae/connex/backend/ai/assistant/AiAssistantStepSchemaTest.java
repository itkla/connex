package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCatalog.Toolset;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AiAssistantStepSchemaTest {
    private final JsonMapper objectMapper = JsonMapper.builder().build();

    /**
     * The closing step's schema forbids a tool step structurally: the tool branch admits only
     * null and the final answer is a required object, so a provider that enforces the schema
     * cannot spend the closing step on another read.
     */
    @Test
    void closingSchemaAdmitsOnlyAFinalAnswer() {
        AiAssistantStepSchema schema = new AiAssistantStepSchema(
                objectMapper, new AiAssistantToolCatalog());

        JsonNode root = schema.closingResponseSchema().schema();
        assertEquals("ask_connex_closing_step", schema.closingResponseSchema().name());
        assertEquals("null", root.path("properties").path("tool").path("type").asString());
        assertFalse(root.path("properties").path("tool").has("anyOf"));
        JsonNode finalShape = root.path("properties").path("final");
        assertEquals("object", finalShape.path("type").asString());
        assertFalse(finalShape.has("anyOf"));
        assertEquals(4, finalShape.path("required").size());
        assertEquals(2, root.path("required").size());
        assertFalse(root.path("additionalProperties").asBoolean());
    }

    @Test
    void schemaConstrainsExclusiveToolAndFinalShapesFromTheCatalog() {
        AiAssistantStepSchema schema = new AiAssistantStepSchema(
                objectMapper, new AiAssistantToolCatalog());

        JsonNode root = schema.responseSchema(AiAssistantToolCatalog.ALL).schema();
        assertEquals("ask_connex_step", schema.responseSchema(AiAssistantToolCatalog.ALL).name());
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
        assertFalse(finalShape.path("properties").has("blocks"));
        assertFalse(finalShape.path("properties").has("coverage"));
    }

    /**
     * The strict step schema is a function of the turn's loaded toolsets: a core-only step cannot
     * even express a tool the turn has not loaded, and loading one widens the branches by exactly
     * that toolset's declared tools.
     */
    @Test
    void theToolBranchesTrackTheLoadedToolsets() {
        AiAssistantStepSchema schema = new AiAssistantStepSchema(
                objectMapper, new AiAssistantToolCatalog());

        String core = schema.responseSchema(AiAssistantToolCatalog.CORE)
                .schema().path("properties").path("tool").toString();
        assertTrue(core.contains("search_records"));
        assertTrue(core.contains("list_tasks"));
        assertTrue(core.contains(AiAssistantToolCatalog.FIND_TOOLS),
                "a core-only step must always be able to express the call that widens it");
        assertTrue(core.contains("write_pipeline"),
                "find_tools carries the loadable keys as a closed enum, never free text");
        assertFalse(core.contains("aggregate_metric"));
        assertFalse(core.contains("get_deal_brief"));
        assertFalse(core.contains("create_note"));

        Set<Toolset> withAnalytics = new LinkedHashSet<>(AiAssistantToolCatalog.CORE);
        withAnalytics.add(Toolset.ANALYTICS);
        String widened = schema.responseSchema(withAnalytics)
                .schema().path("properties").path("tool").toString();
        assertTrue(widened.contains("aggregate_metric"));
        assertTrue(widened.contains("get_deal_brief"));
        assertFalse(widened.contains("create_note"));
        assertEquals(
                coreBranches(schema) + 2,
                schema.responseSchema(withAnalytics).schema()
                        .path("properties").path("tool").path("anyOf").path(1).path("anyOf")
                        .size());

        String closing = schema.closingResponseSchema().schema()
                .path("properties").path("tool").toString();
        assertFalse(closing.contains("search_records"));
    }

    private static int coreBranches(AiAssistantStepSchema schema) {
        return schema.responseSchema(AiAssistantToolCatalog.CORE).schema()
                .path("properties").path("tool").path("anyOf").path(1).path("anyOf").size();
    }
}
