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

    /**
     * The JSON-protocol tool-result envelope the prompt assembler emits per completed tool call.
     *
     * <p>Counted rather than inferred, because an evicted result still emits exactly one envelope,
     * so the count stays monotonic under budget pressure. The marker cannot be forged from inside
     * an envelope: retrieved tenant content is serialized as JSON strings, where the quotes are
     * escaped.
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
            int index = content.indexOf(TOOL_RESULT_MARKER);
            while (index >= 0) {
                count++;
                index = content.indexOf(TOOL_RESULT_MARKER, index + TOOL_RESULT_MARKER.length());
            }
        }
        return count;
    }
}
