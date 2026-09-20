package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCatalog.ToolSpec;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCatalog.Toolset;
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
                catalog.tools(AiAssistantToolCatalog.ALL).stream().map(AiAssistantToolCatalog.ToolSpec::name).toList());
        assertEquals(15, catalog.tools(AiAssistantToolCatalog.ALL).stream()
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
        var definitions = catalog.nativeDefinitions(objectMapper, AiAssistantToolCatalog.ALL);

        assertEquals(15, definitions.size());
        assertEquals(
                catalog.tools(AiAssistantToolCatalog.ALL).stream()
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

    /**
     * The taxonomy is a partition: every declared tool names exactly one toolset, and the core
     * toolset is pinned by name because it is the vocabulary a turn can never be without.
     */
    @Test
    void everyDeclaredToolBelongsToExactlyOneToolsetAndCoreIsPinned() {
        Map<Toolset, List<String>> byToolset = new EnumMap<>(Toolset.class);
        for (ToolSpec spec : catalog.tools(AiAssistantToolCatalog.ALL)) {
            byToolset.computeIfAbsent(spec.toolset(), key -> new ArrayList<>())
                    .add(spec.name());
        }

        assertEquals(
                List.of(
                        "search_records", "get_record", "get_records", "set_todos",
                        "list_activities", "list_tasks", "list_scope_activities"),
                byToolset.get(Toolset.CORE));
        assertEquals(List.of("aggregate_metric", "get_deal_brief"),
                byToolset.get(Toolset.ANALYTICS));
        assertEquals(List.of("find_schedule_conflicts"), byToolset.get(Toolset.SCHEDULE));
        assertEquals(List.of("create_activity", "create_task"),
                byToolset.get(Toolset.WRITE_ACTIVITY));
        assertEquals(List.of("create_note", "add_tag"), byToolset.get(Toolset.WRITE_CONTENT));
        assertEquals(List.of("change_deal_stage", "assign_owner"),
                byToolset.get(Toolset.WRITE_PIPELINE));
        assertEquals(
                catalog.tools(AiAssistantToolCatalog.ALL).size(),
                byToolset.values().stream().mapToInt(List::size).sum());
        assertEquals(Toolset.CORE, catalog.toolsetOf("list_tasks"));
        assertNull(catalog.toolsetOf("delete_record"));
    }

    /**
     * A declaration without a toolset has to fail where it is written, not where it is filtered.
     *
     * <p>Every catalog view filters on the toolset, so a null one would drop the tool out of the
     * vocabulary, the native definitions and {@code isLoaded} for {@code ALL} while
     * {@code isKnown} still passed — the step guard would then reject it as {@code tool_name} — and
     * would throw from {@code CORE}, an immutable set whose {@code contains} dereferences its
     * argument, turning prompt assembly into an unhandled failure. No assertion over the built
     * catalog can observe either case, because the offending spec is already filtered away.
     */
    @Test
    void aToolDeclaredWithoutAToolsetIsRefusedAtDeclaration() {
        NullPointerException refused = assertThrows(NullPointerException.class,
                () -> new ToolSpec(
                        "orphan_tool",
                        null,
                        AiAssistantToolCatalog.ToolTier.READ,
                        true,
                        null,
                        List.of()));

        assertTrue(refused.getMessage().contains("orphan_tool"));
    }

    /**
     * The declared keys are the stable wire vocabulary a loaded set is named by, and {@code core}
     * is never one of them because it is always held.
     */
    @Test
    void toolsetKeysAreStableAndTheDirectoryCoversEveryLoadableSet() {
        assertEquals(
                List.of("core", "analytics", "schedule",
                        "write_activity", "write_content", "write_pipeline"),
                Arrays.stream(Toolset.values()).map(Toolset::key).toList());
        assertEquals(
                List.of("analytics", "schedule", "write_activity", "write_content",
                        "write_pipeline"),
                AiAssistantToolCatalog.LOADABLE.stream().map(Toolset::key).toList());
        assertEquals(AiAssistantToolCatalog.LOADABLE.size(), catalog.directory().size());
        for (Map.Entry<Toolset, String> entry : catalog.directory()) {
            assertFalse(entry.getKey() == Toolset.CORE,
                    "the directory lists loadable sets only");
            assertFalse(entry.getValue().isBlank(),
                    entry.getKey().key() + " needs a server-authored summary");
        }
        assertEquals(Set.of(Toolset.CORE), AiAssistantToolCatalog.CORE);
        assertEquals(6, AiAssistantToolCatalog.ALL.size());
    }

    /** Both prompt-facing views narrow to the loaded set and keep stable catalog order. */
    @Test
    void bothCatalogViewsReturnOnlyTheLoadedToolsInCatalogOrder() {
        assertEquals(
                List.of("search_records", "get_record", "get_records", "set_todos",
                        "list_activities", "list_tasks", "list_scope_activities"),
                catalog.tools(AiAssistantToolCatalog.CORE).stream().map(ToolSpec::name).toList());
        Set<Toolset> coreAndAnalytics = new LinkedHashSet<>(AiAssistantToolCatalog.CORE);
        coreAndAnalytics.add(Toolset.ANALYTICS);
        assertEquals(
                List.of("search_records", "get_record", "get_records", "set_todos",
                        "list_activities", "list_tasks", "list_scope_activities",
                        "aggregate_metric", "get_deal_brief"),
                catalog.tools(coreAndAnalytics).stream().map(ToolSpec::name).toList());
        assertEquals(
                List.of("search_records", "get_record", "get_records", "set_todos",
                        "list_activities", "list_tasks", "list_scope_activities",
                        "aggregate_metric"),
                catalog.nativeDefinitions(objectMapper, coreAndAnalytics).stream()
                        .map(definition -> definition.name())
                        .toList());
        assertTrue(catalog.isLoaded("aggregate_metric", coreAndAnalytics));
        assertFalse(catalog.isLoaded("aggregate_metric", AiAssistantToolCatalog.CORE));
        assertFalse(catalog.isLoaded("delete_record", AiAssistantToolCatalog.ALL));
        assertTrue(catalog.isLoaded("list_tasks", AiAssistantToolCatalog.CORE));
    }

    /**
     * The reservation is what one turn's whole prompt budget is sized against, so it must be the
     * same set on every call and must never exceed core plus the declared per-turn cap.
     */
    @Test
    void theReservationIsDeterministicAndBoundedByThePerTurnCap() {
        Set<Toolset> first = catalog.reservationToolsets();

        assertEquals(first, catalog.reservationToolsets());
        assertTrue(first.contains(Toolset.CORE));
        assertEquals(
                AiAssistantToolCatalog.MAX_ACTIVE_TOOLSETS_PER_TURN
                        + AiAssistantToolCatalog.RESERVATION_HEADROOM_TOOLSETS,
                first.stream().filter(toolset -> toolset != Toolset.CORE).count());
        assertTrue(AiAssistantToolCatalog.ALL.containsAll(first));
        assertFalse(first.containsAll(AiAssistantToolCatalog.LOADABLE),
                "reserving for the whole catalog would defeat the point of toolsets");
    }
}
