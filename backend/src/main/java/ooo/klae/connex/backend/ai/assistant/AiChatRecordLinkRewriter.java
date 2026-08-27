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

    /**
     * Resolves every {@code record:rN} link target against the turn's resource registry.
     *
     * @param text demasked terminal markdown
     * @param resources per-turn handle registry snapshot
     * @return markdown whose record links carry durable {@code kind:id} targets
     */
    static String rewrite(
            String text, Map<String, AiChatResourceRegistry.ResourceRef> resources) {
        Matcher matcher = RECORD_LINK.matcher(text);
        StringBuilder rewritten = new StringBuilder(text.length());
        int end = 0;
        while (matcher.find()) {
            rewritten.append(text, end, matcher.start());
            AiChatResourceRegistry.ResourceRef ref = resources.get(matcher.group(2));
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
}
