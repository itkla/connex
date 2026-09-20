package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCatalog.Toolset;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AiAssistantToolsetLoaderTest {
    private final JsonMapper objectMapper = JsonMapper.builder().build();
    private final AiAssistantToolCatalog catalog = new AiAssistantToolCatalog();
    private final AiAssistantToolsetLoader loader = new AiAssistantToolsetLoader(catalog);

    /**
     * The result states the whole active set after the load, which is what makes the durable
     * {@code ai_chat_tool_call} row self-sufficient for reconstruction and what stops the model
     * being told it holds less than it does.
     */
    @Test
    void aFirstLoadWidensTheSetAndNamesTheWholeActiveSetAfterwards() throws Exception {
        Set<Toolset> loaded = turnSet();

        AiAssistantToolResult result = loader.load(args("write_activity"), loaded);

        assertEquals(Set.of(Toolset.CORE, Toolset.WRITE_ACTIVITY), loaded);
        assertEquals("write_activity", result.data().get("loaded"));
        assertEquals(List.of("core", "write_activity"), result.data().get("active"));
        assertEquals(List.of("create_activity", "create_task"), result.data().get("tools"));
        assertEquals(
                AiAssistantToolCatalog.MAX_ACTIVE_TOOLSETS_PER_TURN - 1,
                result.data().get("remainingLoads"));
        assertTrue(result.identifiers().isEmpty());
    }

    /** A second load of a held set is recoverable, so the model can correct it and carry on. */
    @Test
    void loadingAToolsetTwiceIsARecoverableRefusalThatLeavesTheSetAlone() throws Exception {
        Set<Toolset> loaded = turnSet();
        loader.load(args("analytics"), loaded);

        AiAssistantLoopException refused = assertThrows(
                AiAssistantLoopException.class,
                () -> loader.load(args("analytics"), loaded));

        assertEquals("toolset_already_loaded", refused.detailReason());
        assertEquals("malformed_output", refused.terminalReason());
        assertTrue(refused.recoverable());
        assertEquals(Set.of(Toolset.CORE, Toolset.ANALYTICS), loaded);
    }

    /**
     * The set's own size is the counter, so a turn that arrived at the cap by any route is at the
     * cap. A separate load counter would let a seeded turn spend the budget twice.
     */
    @Test
    void aTurnAtTheCapIsRefusedHoweverItGotThere() throws Exception {
        Set<Toolset> loadedByCalls = turnSet();
        loader.load(args("analytics"), loadedByCalls);
        loader.load(args("schedule"), loadedByCalls);
        Set<Toolset> seededToTheCap = turnSet();
        seededToTheCap.add(Toolset.WRITE_CONTENT);
        seededToTheCap.add(Toolset.WRITE_PIPELINE);

        AiAssistantLoopException afterLoads = assertThrows(
                AiAssistantLoopException.class,
                () -> loader.load(args("write_content"), loadedByCalls));
        AiAssistantLoopException afterSeeding = assertThrows(
                AiAssistantLoopException.class,
                () -> loader.load(args("analytics"), seededToTheCap));

        assertEquals("toolset_load_limit_reached", afterLoads.detailReason());
        assertTrue(afterLoads.recoverable());
        assertEquals("toolset_load_limit_reached", afterSeeding.detailReason());
        assertTrue(afterSeeding.recoverable());
        assertEquals(
                Set.of(Toolset.CORE, Toolset.ANALYTICS, Toolset.SCHEDULE), loadedByCalls);
        assertEquals(
                Set.of(Toolset.CORE, Toolset.WRITE_CONTENT, Toolset.WRITE_PIPELINE),
                seededToTheCap);
        assertEquals(0, secondLoadRemaining());
    }

    /**
     * The catalog's closed enum already refuses everything outside the loadable keys, so this
     * lookup is a fourth independent layer rather than the first. It never string-matches model
     * input against anything but a declared key, and {@code core} is not one.
     */
    @Test
    void anArgumentOutsideTheDeclaredEnumNeverReachesAToolset() throws Exception {
        Set<Toolset> loaded = turnSet();

        for (JsonNode rejected : List.of(
                args("core"),
                args("everything"),
                args("WRITE_PIPELINE"),
                objectMapper.readTree("{\"toolset\":7}"),
                objectMapper.readTree("{}"))) {
            AiAssistantLoopException refused = assertThrows(
                    AiAssistantLoopException.class, () -> loader.load(rejected, loaded));
            assertEquals("invalid_tool_arguments", refused.detailReason());
            assertTrue(refused.recoverable());
        }

        assertEquals(AiAssistantToolCatalog.CORE, loaded);
    }

    /**
     * The loop branches before {@code AiAssistantToolExecutor.execute}, which is where the
     * declared-scope gate lives, so {@code find_tools} is the first model-callable tool to skip it.
     * That is correct — it reads nothing and takes no handle — and pinned here so the next tool
     * added to that branch cannot inherit the exemption silently.
     */
    @Test
    void theLoaderTakesNoHandleReadsNothingAndConsultsNoScope() throws Exception {
        AiAssistantToolCatalog.ToolSpec spec = catalog.tools(AiAssistantToolCatalog.CORE).stream()
                .filter(tool -> AiAssistantToolCatalog.FIND_TOOLS.equals(tool.name()))
                .findFirst()
                .orElseThrow();
        assertTrue(spec.arguments().stream()
                .noneMatch(argument -> "handle".equals(argument.name())
                        || "handles".equals(argument.name())));

        Set<Toolset> loaded = turnSet();
        AiAssistantToolResult result = loader.load(args("schedule"), loaded);

        assertEquals(List.of("find_schedule_conflicts"), result.data().get("tools"));
    }

    private int secondLoadRemaining() throws JacksonException {
        Set<Toolset> loaded = turnSet();
        loader.load(args("analytics"), loaded);
        AiAssistantToolResult second = loader.load(args("schedule"), loaded);
        return (int) second.data().get("remainingLoads");
    }

    private static Set<Toolset> turnSet() {
        return new LinkedHashSet<>(AiAssistantToolCatalog.CORE);
    }

    private JsonNode args(String toolset) throws JacksonException {
        return objectMapper.readTree("{\"toolset\":\"" + toolset + "\"}");
    }
}
