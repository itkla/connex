package ooo.klae.connex.backend.ai.assistant;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites model-authored record links from per-turn handles to durable record references.
 *
 * <p>The model links records as {@code [label](record:rN)} because it only ever holds masked
 * per-turn handles; raw record ids never enter a prompt. On the way into the durable transcript
 * the handle is resolved through the turn's own resource registry into the {@code kind:id} href
 * scheme the markdown renderer already resolves to record chips. A handle the registry does not
 * know degrades to the bare label rather than shipping a dead or forgeable reference.
 */
final class AiChatRecordLinkRewriter {

    private static final Pattern RECORD_LINK =
            Pattern.compile("\\[([^\\]\\n]*)\\]\\(record:(r[1-9][0-9]*)\\)");

    private AiChatRecordLinkRewriter() {
    }

    private static final Pattern DURABLE_LINK = Pattern.compile(
            "\\[([^\\]\\n]*)\\]\\((?:person|company|deal|record):[^)\\n]*\\)");

    /**
     * Resolves every {@code record:rN} link target against the handles the answer actually cited.
     *
     * <p>Resolution is restricted to cited handles rather than the whole registry: text can gain
     * a link after the citation check ran — a demasked identifier value may itself contain link
     * syntax — and resolving an uncited handle there would mint a live record reference the
     * answer's evidence never declared. An uncited or unknown handle degrades to its bare label.
     *
     * @param text demasked terminal markdown
     * @param resources per-turn handle registry snapshot
     * @param citedHandles handles the final answer declared as citations
     * @return markdown whose record links carry durable {@code kind:id} targets
     */
    static String rewrite(
            String text,
            Map<String, AiChatResourceRegistry.ResourceRef> resources,
            java.util.Set<String> citedHandles) {
        Matcher matcher = RECORD_LINK.matcher(text);
        StringBuilder rewritten = new StringBuilder(text.length());
        int end = 0;
        while (matcher.find()) {
            rewritten.append(text, end, matcher.start());
            AiChatResourceRegistry.ResourceRef ref =
                    citedHandles.contains(matcher.group(2))
                            ? resources.get(matcher.group(2))
                            : null;
            if (ref == null) {
                rewritten.append(matcher.group(1));
            } else {
                rewritten.append('[').append(matcher.group(1)).append("](")
                        .append(ref.kind()).append(':').append(ref.id()).append(')');
            }
            end = matcher.end();
        }
        rewritten.append(text, end, text.length());
        return rewritten.toString();
    }

    /**
     * Strips durable and handle-form record links down to their labels.
     *
     * <p>Applied to transcript text before it re-enters a prompt: a persisted answer carries
     * {@code kind:id} targets for the renderer, and replaying those to the provider would put raw
     * record ids into a prompt and teach the model the durable link syntax. The label survives —
     * it is a display name the conversational masking pass handles like any other text.
     *
     * @param text transcript content about to be replayed as history
     * @return the same text with record link targets removed
     */
    static String stripDurableLinks(String text) {
        return DURABLE_LINK.matcher(text).replaceAll("$1");
    }
}
