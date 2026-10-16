package ooo.klae.connex.backend.ai.masking;

import java.util.ArrayList;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Re-identifies provider output only when a request-local binding is valid and its token was
 * exposed by a provider dispatch in the current run. Unknown placeholder-shaped references receive
 * a fixed marker and warnings so callers can discard low-integrity output.
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
     * Replaces issued and exposed placeholders such as {@code {{P1}}} and {@code {{ P1 }}}. Bare identifiers
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
     * Demasks every textual value in a parsed JSON tree in place. Operating on the parsed tree rather
     * than the raw string keeps re-identification escape-safe: an original value containing a
     * {@code "} or {@code \} can never corrupt the surrounding JSON. Property names are left
     * untouched: feature content binds to a fixed schema, so a placeholder that appears as a key is
     * an unknown property that binding discards rather than a value that reaches a user.
     * @param node parsed JSON node, mutated in place
     * @param ctx request-local masking context
     * @return total number of unknown placeholder references encountered across the tree
     */
    public static int demaskTree(JsonNode node, MaskingContext ctx) {
        return demaskTree(node, ctx, (path, value) -> { });
    }

    /**
     * Demasks a tree and reports replaced MEMBER values and matching raw candidates at their
     * textual JSON pointers. Exact token provenance remains independent of surrounding text.
     * @param node parsed JSON tree, mutated in place
     * @param ctx request-local masking context
     * @param memberProvenance recipient of textual-value pointers and raw member labels
     * @return total unknown-placeholder warnings
     */
    public static int demaskTree(
            JsonNode node, MaskingContext ctx, BiConsumer<String, String> memberProvenance) {
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(memberProvenance, "memberProvenance");
        return demaskTree(node, ctx, memberProvenance, "");
    }

    private static int demaskTree(
            JsonNode node, MaskingContext ctx, BiConsumer<String, String> memberProvenance, String path) {
        if (node == null) {
            return 0;
        }
        int warnings = 0;
        if (node instanceof ObjectNode object) {
            for (String name : new ArrayList<>(object.propertyNames())) {
                JsonNode child = object.get(name);
                String childPath = path + "/" + name.replace("~", "~0").replace("/", "~1");
                if (child != null && child.isString()) {
                    DemaskResult value = demaskString(child.asString(), ctx,
                            member -> memberProvenance.accept(childPath, member));
                    warnings += value.warnings();
                    object.put(name, value.text());
                } else {
                    warnings += demaskTree(child, ctx, memberProvenance, childPath);
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (int index = 0; index < array.size(); index++) {
                JsonNode child = array.get(index);
                String childPath = path + "/" + index;
                if (child != null && child.isString()) {
                    DemaskResult value = demaskString(child.asString(), ctx,
                            member -> memberProvenance.accept(childPath, member));
                    warnings += value.warnings();
                    array.set(index, array.textNode(value.text()));
                } else {
                    warnings += demaskTree(child, ctx, memberProvenance, childPath);
                }
            }
        }
        return warnings;
    }

    private static DemaskResult demaskString(String masked, MaskingContext ctx) {
        return demaskString(masked, ctx, member -> { });
    }

    private static DemaskResult demaskString(
            String masked, MaskingContext ctx, Consumer<String> memberProvenance) {
        var refused = ctx.refusedPlaceholders(masked);
        StringBuilder canonical = new StringBuilder();
        int sourceEnd = 0;
        for (var span : refused) {
            canonical.append(masked, sourceEnd, span.start()).append(UNKNOWN_REFERENCE);
            sourceEnd = span.end();
        }
        canonical.append(masked, sourceEnd, masked.length());
        masked = canonical.toString();
        Matcher matcher = TOKEN_PATTERN.matcher(masked);
        StringBuilder demasked = new StringBuilder();
        int lastEnd = 0;
        int warnings = refused.size();
        var replacedMembers = new java.util.LinkedHashSet<String>();
        while (matcher.find()) {
            demasked.append(masked, lastEnd, matcher.start());
            String token = "{{" + matcher.group(1) + "}}";
            String originalValue = ctx.demaskableValueForToken(token);
            if (originalValue == null) {
                demasked.append(UNKNOWN_REFERENCE);
                warnings++;
            } else {
                demasked.append(originalValue);
                if (ctx.isExposedMemberToken(token)) {
                    memberProvenance.accept(originalValue);
                    replacedMembers.add(originalValue);
                }
            }
            lastEnd = matcher.end();
        }
        demasked.append(masked, lastEnd, masked.length());
        String text = demasked.toString();
        ctx.memberCandidatesIn(text).stream().filter(member -> !replacedMembers.contains(member))
                .forEach(memberProvenance);
        return new DemaskResult(text, warnings);
    }
}
