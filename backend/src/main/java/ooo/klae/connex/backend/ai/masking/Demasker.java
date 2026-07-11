package ooo.klae.connex.backend.ai.masking;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Re-identifies provider output by replacing issued request-local placeholders with their original
 * display values. Unknown placeholder-shaped references are replaced with a fixed marker and
 * counted as warnings so callers can discard low-integrity output.
 */
public final class Demasker {
    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\{\\{\\s*([A-Z][1-9][0-9]*)\\s*}}");
    private static final String UNKNOWN_REFERENCE = "[unknown reference]";

    private Demasker() {
    }

    /**
     * Demasked provider output.
     * @param text output after token replacement
     * @param warnings number of unknown placeholder references encountered
     */
    public record DemaskResult(String text, int warnings) {

        public DemaskResult {
            Objects.requireNonNull(text, "text");
        }
    }

    /**
     * Replaces issued placeholders such as {@code {{P1}}} and {@code {{ P1 }}}. Bare identifiers
     * such as {@code P1} are never replaced.
     * @param modelOutput provider output
     * @param ctx request-local masking context
     * @return demasked text plus warning count
     */
    public static DemaskResult demask(String modelOutput, MaskingContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        if (modelOutput == null || modelOutput.isBlank()) {
            return new DemaskResult("", 0);
        }
        return demaskString(modelOutput, ctx);
    }

    /**
     * Demasks every textual value and property name in a parsed JSON tree in place. Operating on the
     * parsed tree rather than the raw string keeps re-identification escape-safe: an original value
     * containing a {@code "} or {@code \} can never corrupt the surrounding JSON.
     * @param node parsed JSON node, mutated in place
     * @param ctx request-local masking context
     * @return total number of unknown placeholder references encountered across the tree
     */
    public static int demaskTree(JsonNode node, MaskingContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        if (node == null) {
            return 0;
        }
        int warnings = 0;
        if (node instanceof ObjectNode object) {
            List<String> names = new ArrayList<>(object.propertyNames());
            for (String name : names) {
                JsonNode child = object.get(name);
                if (child != null && child.isString()) {
                    DemaskResult value = demaskString(child.asString(), ctx);
                    warnings += value.warnings();
                    object.put(name, value.text());
                } else {
                    warnings += demaskTree(child, ctx);
                }
                DemaskResult renamed = demaskString(name, ctx);
                if (!renamed.text().equals(name)) {
                    object.set(renamed.text(), object.remove(name));
                    warnings += renamed.warnings();
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (int index = 0; index < array.size(); index++) {
                JsonNode child = array.get(index);
                if (child != null && child.isString()) {
                    DemaskResult value = demaskString(child.asString(), ctx);
                    warnings += value.warnings();
                    array.set(index, array.textNode(value.text()));
                } else {
                    warnings += demaskTree(child, ctx);
                }
            }
        }
        return warnings;
    }

    private static DemaskResult demaskString(String masked, MaskingContext ctx) {
        Matcher matcher = TOKEN_PATTERN.matcher(masked);
        StringBuilder demasked = new StringBuilder();
        int lastEnd = 0;
        int warnings = 0;
        while (matcher.find()) {
            demasked.append(masked, lastEnd, matcher.start());
            String token = "{{" + matcher.group(1) + "}}";
            String originalValue = ctx.originalValueForToken(token);
            if (originalValue == null) {
                demasked.append(UNKNOWN_REFERENCE);
                warnings++;
            } else {
                demasked.append(originalValue);
            }
            lastEnd = matcher.end();
        }
        demasked.append(masked, lastEnd, masked.length());
        return new DemaskResult(demasked.toString(), warnings);
    }
}
