package ooo.klae.connex.backend.ai.assistant;

import java.util.List;
import java.util.Map;

/** Ephemeral untrusted attachment data and provider usage added to one assistant turn. */
public record AiChatAttachmentContext(
        List<Map<String, Object>> data,
        int inputTokens,
        int outputTokens) {

    public AiChatAttachmentContext {
        data = List.copyOf(data);
    }

    /** @return an empty attachment context */
    public static AiChatAttachmentContext empty() {
        return new AiChatAttachmentContext(List.of(), 0, 0);
    }
}
