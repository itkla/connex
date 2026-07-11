package ooo.klae.connex.backend.ai.masking;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes raw provider output before re-identification by stripping a leading model
 * chain-of-thought preamble. Some providers emit reasoning wrapped in {@code <thought>},
 * {@code <thinking>}, or {@code <think>} tags ahead of the answer; that reasoning must never reach a
 * user surface, so any such block at the start of the completion is removed at the single response
 * choke point before demasking.
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
            "<(/?)(?:think(?:ing)?|thoughts?)\\b[^>]*>",
            Pattern.CASE_INSENSITIVE);

    private CompletionNormalizer() {
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
        if (modelOutput == null || modelOutput.isBlank()) {
            return "";
        }
        String remaining = modelOutput;
        while (LEADING_REASONING_OPEN.matcher(remaining).lookingAt()) {
            int afterClose = leadingBlockEnd(remaining, remaining.indexOf('<'));
            if (afterClose < 0) {
                return "";
            }
            remaining = remaining.substring(afterClose);
        }
        return remaining.strip();
    }

    private static int leadingBlockEnd(String text, int openStart) {
        Matcher matcher = REASONING_TAG.matcher(text);
        int depth = 0;
        int firstBalancedEnd = -1;
        int searchFrom = openStart;
        while (matcher.find(searchFrom)) {
            depth += matcher.group(1).isEmpty() ? 1 : -1;
            searchFrom = matcher.end();
            if (depth < 0) {
                return -1;
            }
            if (depth == 0 && firstBalancedEnd < 0) {
                firstBalancedEnd = matcher.end();
            }
        }
        return firstBalancedEnd;
    }
}
