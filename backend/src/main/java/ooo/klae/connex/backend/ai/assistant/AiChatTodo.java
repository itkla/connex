package ooo.klae.connex.backend.ai.assistant;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.JsonNode;

/**
 * One step of the plan the assistant published for a turn.
 *
 * <p>The plan is the model's own working list, not tenant data: it is written by
 * {@code set_todos}, streamed so the member can watch the work advance, and persisted with the
 * answer so a reloaded transcript still shows what the assistant set out to do.
 *
 * @param label the step in the model's words
 * @param status {@code pending}, {@code active}, or {@code done}
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AiChatTodo(String label, String status) {

    /** The status a step carries when the model listed it without one. */
    public static final String PENDING = "pending";

    private static final List<String> STATUSES = List.of(PENDING, "active", "done");

    /**
     * Reads a {@code set_todos} argument pair into a plan.
     *
     * <p>The two arrays are read forgivingly on purpose: a status list that is short, long, or
     * carries an unknown value costs the model a step to correct and tells the member nothing, so
     * a missing or unrecognized status reads as {@code pending} rather than refusing the call.
     * The labels themselves are already bounded by the tool catalog.
     *
     * @param items declared plan steps in order
     * @param statuses declared per-step statuses, possibly absent or mismatched
     * @return the plan to publish
     */
    public static List<AiChatTodo> from(JsonNode items, JsonNode statuses) {
        if (items == null || !items.isArray()) {
            return List.of();
        }
        List<AiChatTodo> todos = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            JsonNode label = items.get(index);
            if (label == null || !label.isString() || label.asString().isBlank()) {
                continue;
            }
            todos.add(new AiChatTodo(
                    label.asString().strip(), statusAt(statuses, index)));
        }
        return List.copyOf(todos);
    }

    private static String statusAt(JsonNode statuses, int index) {
        if (statuses == null || !statuses.isArray() || index >= statuses.size()) {
            return PENDING;
        }
        JsonNode status = statuses.get(index);
        if (status == null || !status.isString()) {
            return PENDING;
        }
        String value = status.asString().strip().toLowerCase(Locale.ROOT);
        return STATUSES.contains(value) ? value : PENDING;
    }
}
