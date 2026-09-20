package ooo.klae.connex.backend.ai.assistant;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * <p>The result names only the <em>executable</em> tools of the set it loaded. A reserved
 * declaration such as {@code get_deal_brief} is part of the vocabulary the schema and the prompt
 * still render, with its unavailable reason attached, but advertising it here as something the
 * model just unlocked would invite a call that {@code AiAssistantToolExecutor} refuses
 * non-recoverably — spending the governance step the load cost and forcing the turn to close
 * without the read it loaded the set for.
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
    /**
     * The result key naming the whole active set after the load.
     *
     * <p>Shared with the prompt assembler: its presence is what marks a {@code find_tools} result
     * as the server's own statement of the loaded set, which is replayed verbatim, rather than the
     * loop's ordinary {@code {"error": reason}} refusal shape, which is masked like any other.
     */
    static final String ACTIVE_TOOLSETS = "active";

    private static final String TOOLSET_ARGUMENT = "toolset";
    private static final String ALREADY_LOADED = "toolset_already_loaded";
    private static final String LOAD_LIMIT_REACHED = "toolset_load_limit_reached";
    private static final String INVALID_ARGUMENTS = "invalid_tool_arguments";

    private final AiAssistantToolCatalog toolCatalog;

    /**
     * One prospective load: the toolset the turn may commit and the result that describes it.
     *
     * @param loaded the toolset the step is entitled to add once the step settles as executed
     * @param result the server-authored result naming the active set the commit will produce
     */
    public record Load(Toolset loaded, AiAssistantToolResult result) {
    }

    /**
     * Resolves one named toolset and describes the set the turn will hold once it commits.
     *
     * <p>Deliberately does not mutate. The loop commits the widening only after
     * {@code finishTool(..., "executed", ...)} has written the durable row the result belongs to,
     * so a step that settles any other way — refused replay capacity, an inactive turn, an escaped
     * runtime failure — cannot leave the turn holding a toolset no durable row records. That
     * coupling is the whole of the reconstruction contract: the last executed {@code find_tools}
     * row's {@code active} is the loaded set, with no in-memory-only widening beside it.
     *
     * <p>The result states the complete active set rather than a delta, so one row is enough to
     * reconstruct what the turn held, and so the model is never told it holds less than it does.
     * Both refusals are recoverable: the model can correct the key or stop asking.
     *
     * @param args validated raw arguments carrying the closed {@code toolset} enum
     * @param loadedToolsets the turn's current loaded set, read but never written
     * @return the prospective load
     */
    public Load load(JsonNode args, Set<Toolset> loadedToolsets) {
        Toolset requested = requestedToolset(args);
        if (loadedToolsets.contains(requested)) {
            throw AiAssistantLoopException.refusedArguments(ALREADY_LOADED);
        }
        int loadable = loadableCount(loadedToolsets);
        if (loadable >= AiAssistantToolCatalog.MAX_ACTIVE_TOOLSETS_PER_TURN) {
            throw AiAssistantLoopException.refusedArguments(LOAD_LIMIT_REACHED);
        }
        Set<Toolset> prospective = new LinkedHashSet<>(loadedToolsets);
        prospective.add(requested);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("loaded", requested.key());
        data.put(ACTIVE_TOOLSETS, prospective.stream()
                .sorted(Comparator.comparingInt(Toolset::ordinal))
                .map(Toolset::key)
                .toList());
        data.put("tools", toolCatalog.tools(Set.of(requested)).stream()
                .filter(ToolSpec::executable)
                .map(ToolSpec::name)
                .toList());
        data.put(
                "remainingLoads",
                AiAssistantToolCatalog.MAX_ACTIVE_TOOLSETS_PER_TURN - loadable - 1);
        return new Load(requested, new AiAssistantToolResult(data, List.of()));
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
        Toolset resolved = AiAssistantToolCatalog.loadableByKey(requested.asString());
        if (resolved == null) {
            throw AiAssistantLoopException.refusedArguments(INVALID_ARGUMENTS);
        }
        return resolved;
    }

    private static int loadableCount(Set<Toolset> loadedToolsets) {
        return (int) loadedToolsets.stream()
                .filter(toolset -> toolset != Toolset.CORE)
                .count();
    }
}
