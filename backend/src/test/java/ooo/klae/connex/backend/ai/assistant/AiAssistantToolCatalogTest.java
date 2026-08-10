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
    void catalogKeepsFiveExecutableAndTwoReservedStableKeys() throws Exception {
        assertEquals(
                List.of(
                        "search_records", "get_record", "list_activities", "list_tasks",
                        "aggregate_metric", "find_schedule_conflicts", "get_deal_brief"),
                catalog.tools().stream().map(AiAssistantToolCatalog.ToolSpec::name).toList());
        assertEquals(5, catalog.tools().stream()
                .filter(AiAssistantToolCatalog.ToolSpec::executable)
                .count());
        assertFalse(catalog.isExecutable("find_schedule_conflicts"));
        assertEquals(
                "schedule_conflicts_unavailable",
                catalog.unavailableReason("find_schedule_conflicts"));
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
    }
}
