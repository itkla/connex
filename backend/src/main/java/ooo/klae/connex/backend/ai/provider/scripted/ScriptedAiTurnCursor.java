package ooo.klae.connex.backend.ai.provider.scripted;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import ooo.klae.connex.backend.ai.provider.AiCompletionRequest;
import ooo.klae.connex.backend.ai.provider.AiMessage;
import ooo.klae.connex.backend.ai.provider.AiNativeToolRequest;
import ooo.klae.connex.backend.ai.provider.AiProviderException;
import ooo.klae.connex.backend.ai.provider.AiToolExchange;

/**
 * Where in a scripted trajectory one provider request sits, derived purely from that request.
 *
 * <p>This derivation is the reason the scripted provider needs no per-turn registry, no header, no
 * session map and no endpoint: the request already says which script it belongs to, how many tool
 * calls the loop has completed, whether it is a schema repair, and whether it is the closing step.
 * A provider that had to be told those things out of band would be a control channel, and a
 * control channel is exactly the shape a production bypass takes.
 */
public record ScriptedAiTurnCursor(
        String selector,
        int completedToolCalls,
        boolean repairAttempt,
        boolean closing,
        boolean nativeProtocol) {

    /** Delimiter the prompt assembler opens every untrusted CRM-data envelope with. */
    private static final String ENVELOPE_MARKER = "CRM_DATA_BEGIN";

    /**
     * The envelope type the prompt assembler stamps on a completed tool call's result.
     *
     * <p>Counted rather than inferred, because an evicted result still emits exactly one envelope,
     * so the count stays monotonic under budget pressure.
     *
     * <p>Matched only at the envelope's <em>top level</em>, and only outside a JSON string.
     * Retrieved tenant content is free text that reaches the prompt as ordinary object fields —
     * an activity's {@code type} is unvalidated and lands in the tool-result data map verbatim —
     * so a record whose activity type is literally {@code tool_result} renders this exact marker
     * inside the envelope's {@code data}. A raw substring search would then over-count that turn
     * and replay a step nobody authored, or refuse the turn outright.
     */
    private static final String TOOL_RESULT_MARKER = "\"type\":\"tool_result\"";

    /** Delimiter the prompt assembler wraps a JSON-protocol schema-repair request in. */
    private static final String REPAIR_MARKER = "MODEL_OUTPUT_BEGIN";

    /** Opening of the loop's server-authored closing directive. */
    private static final String CLOSING_MARKER = "You have no investigation steps left.";

    public ScriptedAiTurnCursor {
        Objects.requireNonNull(selector, "selector");
        if (completedToolCalls < 0) {
            throw new IllegalArgumentException("Scripted AI cursor tool-call count is invalid");
        }
    }

    /**
     * Derives the cursor for one provider request.
     * @param request the completion request crossing the provider seam
     * @param knownSelectors selectors declared by the loaded scripts
     * @return the derived cursor
     * @throws AiProviderException when the request carries no selector, or more than one
     */
    public static ScriptedAiTurnCursor of(
            AiCompletionRequest request,
            Set<String> knownSelectors) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(knownSelectors, "knownSelectors");
        String corpus = corpus(request);
        List<String> matched = new ArrayList<>();
        for (String selector : knownSelectors) {
            if (corpus.contains(selector)) {
                matched.add(selector);
            }
        }
        if (matched.size() != 1) {
            throw new AiProviderException(matched.isEmpty()
                    ? "Scripted AI request matched no script selector"
                    : "Scripted AI request matched more than one script selector");
        }
        AiNativeToolRequest nativeTools = request.nativeTools();
        boolean nativeProtocol = nativeTools != null;
        return new ScriptedAiTurnCursor(
                matched.getFirst(),
                nativeProtocol
                        ? nativeTools.exchanges().size()
                        : countToolResults(request.messages()),
                nativeProtocol
                        ? nativeTools.repairMessage() != null
                        : corpus.contains(REPAIR_MARKER),
                nativeProtocol ? nativeTools.finalOnly() : corpus.contains(CLOSING_MARKER),
                nativeProtocol);
    }

    private static String corpus(AiCompletionRequest request) {
        StringBuilder corpus = new StringBuilder();
        if (request.systemPrompt() != null) {
            corpus.append(request.systemPrompt()).append('\n');
        }
        for (AiMessage message : request.messages()) {
            corpus.append(message.content()).append('\n');
        }
        AiNativeToolRequest nativeTools = request.nativeTools();
        if (nativeTools != null) {
            for (AiToolExchange exchange : nativeTools.exchanges()) {
                corpus.append(exchange.call().arguments()).append('\n')
                        .append(exchange.maskedResult()).append('\n');
            }
            if (nativeTools.repairMessage() != null) {
                corpus.append(nativeTools.repairMessage()).append('\n');
            }
        }
        return corpus.toString();
    }

    private static int countToolResults(List<AiMessage> messages) {
        int count = 0;
        for (AiMessage message : messages) {
            String content = message.content();
            if (content == null) {
                continue;
            }
            int index = content.indexOf(ENVELOPE_MARKER);
            while (index >= 0) {
                if (isToolResultEnvelope(content, index + ENVELOPE_MARKER.length())) {
                    count++;
                }
                index = content.indexOf(ENVELOPE_MARKER, index + ENVELOPE_MARKER.length());
            }
        }
        return count;
    }

    /**
     * Whether the envelope opening at {@code from} declares the tool-result type at its top level.
     *
     * <p>Walks the envelope object tracking nesting depth and JSON string state, so the type marker
     * counts only when it is a key of the envelope itself. Tenant content cannot forge it: inside a
     * JSON string every quote is escaped, and an escaped quote is not a key.
     *
     * @param content one prompt message's text
     * @param from index just past the envelope delimiter
     * @return whether this envelope is a tool result
     */
    private static boolean isToolResultEnvelope(String content, int from) {
        int start = from;
        while (start < content.length() && Character.isWhitespace(content.charAt(start))) {
            start++;
        }
        if (start >= content.length() || content.charAt(start) != '{') {
            return false;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = start; index < content.length(); index++) {
            char current = content.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }
            if (depth == 1 && content.startsWith(TOOL_RESULT_MARKER, index)) {
                return true;
            }
            switch (current) {
                case '{', '[' -> depth++;
                case '}', ']' -> {
                    depth--;
                    if (depth == 0) {
                        return false;
                    }
                }
                case '"' -> inString = true;
                default -> {
                }
            }
        }
        return false;
    }
}
