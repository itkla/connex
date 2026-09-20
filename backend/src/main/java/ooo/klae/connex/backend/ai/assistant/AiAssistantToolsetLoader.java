package ooo.klae.connex.backend.ai.assistant;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCatalog.ToolSpec;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCatalog.Toolset;
import tools.jackson.databind.JsonNode;

/**
 * Executes one {@code find_tools} call against the turn's own loaded toolsets.
 *
 * <p>Deliberately separate from {@link AiAssistantToolExecutor}, which is a stateless tenant-read
 * component and must stay that way: this is the only tool whose effect is per-turn state rather
 * than a read, so the agent loop owns the set and hands it here rather than letting the executor
 * acquire mutable turn state.
 *
 * <p>Loading is narrowing only. Every tool in every loadable toolset is already callable on a
 * routed or generic turn, so a load restores reach the taxonomy removed and grants nothing new;
 * write authority stays with {@code requireSkillAuthority} and scope honesty with
 * {@link AiChatScopedToolPolicy}. This loader reads no database, resolves no handle, and consults
 * no declared scope.
 */
@Component
@RequiredArgsConstructor
public class AiAssistantToolsetLoader {
    private static final String TOOLSET_ARGUMENT = "toolset";
    private static final String ALREADY_LOADED = "toolset_already_loaded";
    private static final String LOAD_LIMIT_REACHED = "toolset_load_limit_reached";
    private static final String INVALID_ARGUMENTS = "invalid_tool_arguments";

    private final AiAssistantToolCatalog toolCatalog;

    /**
     * Loads one named toolset into the turn's set and describes the whole set afterwards.
     *
     * <p>The result states the complete active set rather than a delta, so the durable
     * {@code ai_chat_tool_call} row is on its own enough to reconstruct what the turn held, and so
     * the model is never told it holds less than it does. Both refusals are recoverable and leave
     * the set untouched: the model can correct the key or stop asking.
     *
     * @param args validated raw arguments carrying the closed {@code toolset} enum
     * @param loadedToolsets the turn's mutable loaded set, widened in place on success
     * @return the server-authored result naming the active set after the load
     */
    public AiAssistantToolResult load(JsonNode args, Set<Toolset> loadedToolsets) {
        Toolset requested = requestedToolset(args);
        if (loadedToolsets.contains(requested)) {
            throw AiAssistantLoopException.refusedArguments(ALREADY_LOADED);
        }
        int loadable = loadableCount(loadedToolsets);
        if (loadable >= AiAssistantToolCatalog.MAX_ACTIVE_TOOLSETS_PER_TURN) {
            throw AiAssistantLoopException.refusedArguments(LOAD_LIMIT_REACHED);
        }
        loadedToolsets.add(requested);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("loaded", requested.key());
        data.put("active", loadedToolsets.stream()
                .sorted(Comparator.comparingInt(Toolset::ordinal))
                .map(Toolset::key)
                .toList());
        data.put("tools", toolCatalog.tools(Set.of(requested)).stream()
                .map(ToolSpec::name)
                .toList());
        data.put(
                "remainingLoads",
                AiAssistantToolCatalog.MAX_ACTIVE_TOOLSETS_PER_TURN - loadable - 1);
        return new AiAssistantToolResult(data, List.of());
    }

    /**
     * Re-maps the validated argument onto the declared enum.
     *
     * <p>The closed enum is already enforced by {@code permitsArguments}, the ReAct step schema and
     * the native parameter schema, so this lookup is a fourth, independent layer rather than the
     * first: model input is never string-matched against anything but a declared key.
     */
    private static Toolset requestedToolset(JsonNode args) {
        JsonNode requested = args == null ? null : args.get(TOOLSET_ARGUMENT);
        if (requested == null || !requested.isString()) {
            throw AiAssistantLoopException.refusedArguments(INVALID_ARGUMENTS);
        }
        String key = requested.asString();
        for (Toolset toolset : AiAssistantToolCatalog.LOADABLE) {
            if (toolset.key().equals(key)) {
                return toolset;
            }
        }
        throw AiAssistantLoopException.refusedArguments(INVALID_ARGUMENTS);
    }

    private static int loadableCount(Set<Toolset> loadedToolsets) {
        return (int) loadedToolsets.stream()
                .filter(toolset -> toolset != Toolset.CORE)
                .count();
    }
}
