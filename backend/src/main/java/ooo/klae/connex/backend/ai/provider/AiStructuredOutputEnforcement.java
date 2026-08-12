package ooo.klae.connex.backend.ai.provider;

/** Provider-native enforcement applied to one structured completion request. */
public enum AiStructuredOutputEnforcement {
    PROMPT_ONLY,
    JSON_OBJECT,
    JSON_SCHEMA;

    /** @return the next honest fallback after a provider rejects this enforcement mode */
    public AiStructuredOutputEnforcement degrade() {
        return switch (this) {
            case JSON_SCHEMA -> JSON_OBJECT;
            case JSON_OBJECT, PROMPT_ONLY -> PROMPT_ONLY;
        };
    }
}
