package ooo.klae.connex.backend.ai.masking;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes raw provider output before re-identification by separating a leading model reasoning
 * preamble from the answer. Some providers emit reasoning wrapped in {@code <thought>},
 * {@code <thinking>}, or {@code <think>} tags ahead of the answer; the boundary is resolved at the
 * single response choke point before either region is demasked.
 *
 * <p>Stripping is anchored to the leading position because a reasoning preamble always precedes the
 * answer. Reasoning tags that appear later in the body are deliberately left untouched, so that
 * legitimate content echoing a literal {@code <thought>} token — for example a transcript pasted
 * into a CRM note and summarized back — is never silently truncated. When the leading reasoning
 * region is malformed (an unbalanced closing tag makes the reasoning/answer boundary ambiguous) the
 * method fails closed and discards the whole output rather than risk leaking reasoning; downstream
 * features already degrade a blank completion to an explicit unavailable result.
 */
public final class CompletionNormalizer {
    private static final Pattern LEADING_REASONING_OPEN = Pattern.compile(
            "^\\s*<(?:think(?:ing)?|thoughts?)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern REASONING_TAG = Pattern.compile(
            "<(/?)(think(?:ing)?|thoughts?)\\b[^>]*>",
            Pattern.CASE_INSENSITIVE);

    private CompletionNormalizer() {
    }

    /** Fail-closed separation of one provider completion into answer and display-only reasoning. */
    public record CapturedCompletion(String answer, String reasoning, boolean ambiguous) {
    }

    /**
     * Strips a leading reasoning-tag preamble from provider output. Consecutive leading blocks are
     * removed and nesting is respected, so an outer block is discarded whole. A leading opening tag
     * with no matching close, or a leading region whose tags are unbalanced, discards the entire
     * output.
     * @param modelOutput raw provider completion text
     * @return output with any leading reasoning preamble removed and surrounding whitespace stripped,
     *         or an empty string when the input is null, blank, entirely reasoning, or malformed
     */
    public static String stripReasoning(String modelOutput) {
        CapturedCompletion captured = captureReasoning(modelOutput, "");
        return captured.ambiguous() ? "" : captured.answer();
    }

    /**
     * Captures either a native reasoning channel or consecutive leading tagged blocks. Native and
     * tagged reasoning in the same response, or an unbalanced tagged boundary, is ambiguous and
     * discards both regions.
     * @param modelOutput provider answer channel
     * @param nativeReasoning provider-native reasoning channel, or an empty string
     * @return separated answer and reasoning with an explicit ambiguity signal
     */
    public static CapturedCompletion captureReasoning(
            String modelOutput, String nativeReasoning) {
        String nativeText = nativeReasoning == null ? "" : nativeReasoning.strip();
        if (modelOutput == null || modelOutput.isBlank()) {
            return nativeText.isEmpty()
                    ? new CapturedCompletion("", "", false)
                    : new CapturedCompletion("", nativeText, false);
        }
        String remaining = modelOutput;
        StringBuilder taggedReasoning = new StringBuilder();
        while (LEADING_REASONING_OPEN.matcher(remaining).lookingAt()) {
            int openStart = remaining.indexOf('<');
            int afterClose = leadingBlockEnd(remaining, openStart);
            if (afterClose < 0) {
                return new CapturedCompletion("", "", true);
            }
            String block = remaining.substring(openStart, afterClose);
            Matcher tags = REASONING_TAG.matcher(block);
            if (!tags.find()) {
                return new CapturedCompletion("", "", true);
            }
            int contentStart = tags.end();
            int contentEnd = block.lastIndexOf('<');
            if (contentEnd < contentStart) {
                return new CapturedCompletion("", "", true);
            }
            String content = REASONING_TAG.matcher(
                    block.substring(contentStart, contentEnd)).replaceAll("").strip();
            if (!content.isEmpty()) {
                if (!taggedReasoning.isEmpty()) {
                    taggedReasoning.append("\n\n");
                }
                taggedReasoning.append(content);
            }
            remaining = remaining.substring(afterClose);
        }
        if (!nativeText.isEmpty() && !taggedReasoning.isEmpty()) {
            return new CapturedCompletion("", "", true);
        }
        String reasoning = nativeText.isEmpty() ? taggedReasoning.toString() : nativeText;
        return new CapturedCompletion(remaining.strip(), reasoning, false);
    }

    /** Returns whether text contains any reasoning protocol tag. */
    public static boolean containsReasoningTag(String content) {
        return content != null && REASONING_TAG.matcher(content).find();
    }

    private static int leadingBlockEnd(String text, int openStart) {
        Matcher matcher = REASONING_TAG.matcher(text);
        Deque<String> openTags = new ArrayDeque<>();
        int firstBalancedEnd = -1;
        int searchFrom = openStart;
        while (matcher.find(searchFrom)) {
            searchFrom = matcher.end();
            if (matcher.group(1).isEmpty()) {
                openTags.push(matcher.group(2));
                continue;
            }
            if (openTags.isEmpty()
                    || !openTags.pop().equalsIgnoreCase(matcher.group(2))) {
                return -1;
            }
            if (openTags.isEmpty() && firstBalancedEnd < 0) {
                firstBalancedEnd = matcher.end();
            }
        }
        return openTags.isEmpty() ? firstBalancedEnd : -1;
    }
}
