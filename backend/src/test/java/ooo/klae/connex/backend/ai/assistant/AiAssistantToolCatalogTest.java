package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class AiAssistantToolCatalogTest {
    private final JsonMapper objectMapper = JsonMapper.builder().build();
    private final AiAssistantToolCatalog catalog = new AiAssistantToolCatalog();

    @Test
    void catalogKeepsReadAndWriteSafetyTiersExplicit() throws Exception {
        assertEquals(
                List.of(
                        "search_records", "get_record", "get_records", "set_todos", "list_activities", "list_tasks",
                        "list_scope_activities",
                        "aggregate_metric", "find_schedule_conflicts", "get_deal_brief",
                        "create_activity", "create_task", "create_note", "add_tag",
                        "change_deal_stage", "assign_owner"),
                catalog.tools().stream().map(AiAssistantToolCatalog.ToolSpec::name).toList());
        assertEquals(15, catalog.tools().stream()
                .filter(AiAssistantToolCatalog.ToolSpec::executable)
                .count());
        assertTrue(catalog.isExecutable("find_schedule_conflicts"));
        assertEquals(AiAssistantToolCatalog.ToolTier.AUTO, catalog.tier("create_activity"));
        assertEquals(AiAssistantToolCatalog.ToolTier.AUTO, catalog.tier("add_tag"));
        assertEquals(AiAssistantToolCatalog.ToolTier.CONFIRM, catalog.tier("change_deal_stage"));
        assertEquals(AiAssistantToolCatalog.ToolTier.CONFIRM, catalog.tier("assign_owner"));
        assertFalse(catalog.isExecutable("get_deal_brief"));
        assertEquals(
                "deal_brief_nested_generation_unavailable",
                catalog.unavailableReason("get_deal_brief"));
        assertTrue(catalog.permitsArguments(
                "get_record", objectMapper.readTree("{\"handle\":\"r1\"}")));
        assertFalse(catalog.permitsArguments(
                "get_record", objectMapper.readTree("{\"handle\":\"r1\",\"id\":9}")));
        assertFalse(catalog.permitsArguments(
                "get_record", objectMapper.readTree("{\"handle\":\"987654321\"}")));
        assertFalse(catalog.permitsArguments(
                "aggregate_metric",
                objectMapper.readTree("{\"metric\":\"activity_volume\",\"days\":31}")));
        assertTrue(catalog.permitsArguments(
                "create_note",
                objectMapper.readTree("{\"handle\":\"r1\",\"content\":\"Follow up\"}")));
        assertFalse(catalog.permitsArguments(
                "create_note",
                objectMapper.readTree(
                        "{\"handle\":\"r1\",\"content\":\"Follow up\","
                                + "\"idempotency_key\":\"model-controlled\"}")));
    }

    @Test
    void theBulkActivityToolAcceptsOnlyNarrowingArgumentsAndNeverARawScope() throws Exception {
        assertTrue(catalog.isExecutable("list_scope_activities"));
        assertEquals(
                AiAssistantToolCatalog.ToolTier.READ, catalog.tier("list_scope_activities"));
        assertTrue(catalog.permitsArguments(
                "list_scope_activities", objectMapper.readTree("{}")));
        assertTrue(catalog.permitsArguments(
                "list_scope_activities",
                objectMapper.readTree(
                        "{\"records\":\"company\",\"warmth\":[\"cool\",\"cold\"],"
                                + "\"days\":90}")));
        assertFalse(catalog.permitsArguments(
                "list_scope_activities",
                objectMapper.readTree("{\"records\":\"note\"}")));
        assertFalse(catalog.permitsArguments(
                "list_scope_activities",
                objectMapper.readTree("{\"warmth\":[\"lukewarm\"]}")));
        assertFalse(catalog.permitsArguments(
                "list_scope_activities",
                objectMapper.readTree("{\"days\":366}")));
        assertFalse(catalog.permitsArguments(
                "list_scope_activities",
                objectMapper.readTree("{\"ownerIds\":[4]}")));
        assertFalse(catalog.permitsArguments(
                "list_scope_activities",
                objectMapper.readTree("{\"limit\":500}")));
    }

    @Test
    void nativeDefinitionsMirrorExecutableCatalogSchemasWithoutReservedTools() {
        var definitions = catalog.nativeDefinitions(objectMapper);

        assertEquals(15, definitions.size());
        assertEquals(
                catalog.tools().stream()
                        .filter(AiAssistantToolCatalog.ToolSpec::executable)
                        .map(AiAssistantToolCatalog.ToolSpec::name)
                        .toList(),
                definitions.stream().map(definition -> definition.name()).toList());
        assertFalse(definitions.stream()
                .anyMatch(definition -> "get_deal_brief".equals(definition.name())));
        var search = definitions.stream()
                .filter(definition -> "search_records".equals(definition.name()))
                .findFirst()
                .orElseThrow();
        assertEquals("object", search.parametersSchema().path("type").asString());
        assertFalse(search.parametersSchema().path("additionalProperties").asBoolean());
        assertEquals(2, search.parametersSchema().path("required").size());
        assertEquals("null", search.parametersSchema()
                .path("properties").path("kinds").path("anyOf").path(1).path("type")
                .asString());
    }
}
