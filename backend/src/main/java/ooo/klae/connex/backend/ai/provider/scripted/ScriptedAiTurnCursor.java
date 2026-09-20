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
 *
 * <p><b>Protocol control state is read from the assembler's structure, never from its text.</b>
 * The serialized prompt contains every system, member and CRM string in the turn, so searching it
 * for the repair delimiter or the closing sentence would let an ordinary sentence a member typed —
 * or a company name, an activity subject, a note — move the provider into a state the loop never
 * entered. The assembler emits untrusted content only inside a delimiter envelope that occupies a
 * whole message, and its own directives as bare messages, so the segment's shape decides. The
 * script <em>selector</em> is the deliberate exception: it is carried by the requesting member's
 * own words on purpose, and it selects which fixture answers rather than which protocol state the
 * loop is in.
 */
public record ScriptedAiTurnCursor(
        String selector,
        int completedToolCalls,
        boolean repairAttempt,
        boolean closing,
        boolean nativeProtocol) {

    /** Provider role the prompt assembler emits every server-authored directive under. */
    private static final String ROLE_USER = "user";

    /** Delimiter the prompt assembler opens every untrusted CRM-data envelope with. */
    private static final String ENVELOPE_MARKER = "CRM_DATA_BEGIN";

    /** Delimiter the prompt assembler opens every member-authored request envelope with. */
    private static final String USER_REQUEST_MARKER = "USER_REQUEST_BEGIN";

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

    /** Delimiter opening the offending output inside a JSON-protocol schema-repair request. */
    private static final String REPAIR_MARKER = "MODEL_OUTPUT_BEGIN";

    /** Delimiter closing a schema-repair request, which is that message's final text. */
    private static final String REPAIR_END_MARKER = "MODEL_OUTPUT_END";

    /** Opening sentence of the loop's server-authored closing directive. */
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
                        : isRepairAttempt(request.messages()),
                nativeProtocol ? nativeTools.finalOnly() : isClosing(request.messages()),
                nativeProtocol);
    }

    /**
     * Whether the last JSON-protocol message is the assembler's schema-repair request.
     *
     * <p>Derived from the prompt's structure, never from a substring of its text. The assembler
     * appends the repair request last, as a bare server-authored user turn whose final characters
     * are the offending-output end delimiter. Every untrusted string in the same prompt — the
     * member's own words, a tool result, a CRM field — arrives inside its own delimiter envelope
     * and is therefore not a server-authored segment at all, so a member who types the repair
     * marker verbatim changes nothing here.
     *
     * @param messages the prompt's messages in order
     * @return whether this request is a repair attempt
     */
    private static boolean isRepairAttempt(List<AiMessage> messages) {
        if (messages.isEmpty()) {
            return false;
        }
        AiMessage last = messages.getLast();
        if (!isServerAuthoredDirective(last)) {
            return false;
        }
        String content = last.content().strip();
        return content.endsWith(REPAIR_END_MARKER) && content.contains(REPAIR_MARKER);
    }

    /**
     * Whether the JSON-protocol prompt carries the loop's closing directive.
     *
     * <p>Same structural rule as the repair request: the directive is emitted as a whole bare user
     * turn beside the turn's other server-authored contract text, so only a segment that is not an
     * untrusted envelope can be one. A member, an activity subject or any other CRM value quoting
     * the closing sentence travels inside an envelope and cannot reach this state.
     *
     * @param messages the prompt's messages in order
     * @return whether the loop asked for the closing answer
     */
    private static boolean isClosing(List<AiMessage> messages) {
        for (AiMessage message : messages) {
            if (isServerAuthoredDirective(message)
                    && message.content().strip().startsWith(CLOSING_MARKER)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether one message is a server-authored directive rather than untrusted data.
     *
     * <p>The assembler emits exactly two kinds of user turn: a delimiter envelope opening with
     * {@code CRM_DATA_BEGIN} or {@code USER_REQUEST_BEGIN}, which carries retrieved tenant content
     * or a member's own words; and a bare directive it wrote itself. The leading delimiter is the
     * whole discriminator, and it cannot be forged from inside an envelope because the envelope's
     * body is serialized JSON — a member's text is a JSON string value, never the first characters
     * of the message.
     *
     * @param message one prompt message
     * @return whether the message is server-authored directive text
     */
    private static boolean isServerAuthoredDirective(AiMessage message) {
        if (!ROLE_USER.equals(message.role())) {
            return false;
        }
        String content = message.content();
        return content != null
                && !content.startsWith(ENVELOPE_MARKER)
                && !content.startsWith(USER_REQUEST_MARKER);
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

    /**
     * Counts the tool-result envelopes the assembler emitted, by segment rather than by substring.
     *
     * <p>Each envelope is a whole user message that opens with the delimiter, so only a message's
     * leading envelope is a real one. A record field or a member's sentence that spells the
     * delimiter out lives inside another envelope's serialized body and is never counted.
     *
     * @param messages the prompt's messages in order
     * @return completed tool calls the loop has replayed
     */
    private static int countToolResults(List<AiMessage> messages) {
        int count = 0;
        for (AiMessage message : messages) {
            String content = message.content();
            if (!ROLE_USER.equals(message.role())
                    || content == null
                    || !content.startsWith(ENVELOPE_MARKER)) {
                continue;
            }
            if (isToolResultEnvelope(content, ENVELOPE_MARKER.length())) {
                count++;
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
