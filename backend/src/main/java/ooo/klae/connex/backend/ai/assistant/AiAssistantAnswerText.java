package ooo.klae.connex.backend.ai.assistant;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;

/**
 * Renders a model-declared answer document's blocks into plain text so the guard can bound the
 * declared answer's overall length. The persisted transcript keeps the streamed model text itself,
 * so this rendering is a measurement, not a stored representation.
 */
final class AiAssistantAnswerText {
    private AiAssistantAnswerText() {
    }

    static String render(JsonNode blocks) {
        List<String> rendered = new ArrayList<>();
        for (JsonNode block : blocks) {
            List<String> items = new ArrayList<>();
            for (JsonNode item : block.path("items")) {
                items.add(item.asString());
            }
            List<String> rows = new ArrayList<>();
            for (JsonNode row : block.path("rows")) {
                rows.add(renderRow(
                        nullableText(row.get("at")),
                        nullableText(row.get("label")),
                        nullableText(row.get("value")),
                        nullableText(row.get("detail"))));
            }
            rendered.add(renderBlock(
                    nullableText(block.get("title")),
                    nullableText(block.get("body")),
                    items,
                    rows));
        }
        return String.join("\n\n", rendered);
    }

    private static String nullableText(JsonNode value) {
        return value == null || !value.isString() ? null : value.asString();
    }

    private static String renderBlock(
            String title, String body, List<String> items, List<String> rows) {
        List<String> lines = new ArrayList<>();
        if (title != null) {
            lines.add(title);
        }
        if (body != null) {
            lines.add(body);
        }
        items.stream().map(item -> "- " + item).forEach(lines::add);
        lines.addAll(rows);
        return String.join("\n", lines);
    }

    private static String renderRow(String at, String label, String value, String detail) {
        StringBuilder line = new StringBuilder("- ");
        if (at != null) {
            line.append(at).append(" — ");
        }
        if (label != null) {
            line.append(label);
        }
        if (value != null) {
            line.append(": ").append(value);
        }
        if (detail != null) {
            line.append(" (").append(detail).append(')');
        }
        return line.toString();
    }
}
