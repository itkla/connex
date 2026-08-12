package ooo.klae.connex.backend.ai.assistant;

import org.springframework.stereotype.Component;

import ooo.klae.connex.backend.ai.provider.AiResponseSchema;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Provider-neutral strict JSON schema for one durable conversation summary. */
@Component
public class AiAssistantSummarySchema {
    private final AiResponseSchema responseSchema;

    public AiAssistantSummarySchema(ObjectMapper objectMapper) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "object");
        ObjectNode summary = root.putObject("properties").putObject("summary");
        summary.put("type", "string");
        summary.put("minLength", 1);
        summary.put("maxLength", AiAssistantSummaryGuard.MAX_SUMMARY_CHARS);
        root.putArray("required").add("summary");
        root.put("additionalProperties", false);
        responseSchema = new AiResponseSchema("ask_connex_summary", root);
    }

    /** @return immutable provider-neutral summary schema */
    public AiResponseSchema responseSchema() {
        return responseSchema;
    }
}
