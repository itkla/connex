package ooo.klae.connex.backend.ai.provider.openai;

/**
 * Separates inline thought summaries from the answer that follows them.
 *
 * <p>The Gemini OpenAI-compatibility layer returns thought summaries inside the ordinary
 * {@code content} field, wrapped in {@code <thought>…</thought>} and marked with a
 * machine-readable flag beside the message. The split has to happen at this boundary because the
 * two halves belong to channels with opposite retention: everything left in the text reaches the
 * answer — and, beside a tool call, the durable narration — while reasoning is deliberately
 * ephemeral. Content that opens a thought and never closes it therefore yields an empty text
 * rather than passing the thought through: an unfinished thought block failing the step as
 * malformed is recoverable, thought text persisted into the wrong channel is not.
 */
final class OpenAiThoughtTags {
    static final String OPEN = "<thought>";
    static final String CLOSE = "</thought>";

    private OpenAiThoughtTags() {
    }

    /** One content field's thought summary and the answer text that remains without it. */
    record Split(String reasoning, String text) {
    }

    /**
     * Splits one complete content field on its thought tags.
     *
     * @param content provider content, never {@code null}
     * @return the thought summary (empty when the content opens with no thought) and the text
     */
    static Split split(String content) {
        if (!content.startsWith(OPEN)) {
            return new Split("", content);
        }
        int close = content.indexOf(CLOSE);
        if (close < 0) {
            return new Split(content.substring(OPEN.length()), "");
        }
        return new Split(
                content.substring(OPEN.length(), close),
                content.substring(close + CLOSE.length()));
    }
}
