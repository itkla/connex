package ooo.klae.connex.backend.ai.provider;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Selects the Chat Completions parameter dialect required by OpenAI reasoning-model families.
 */
public final class OpenAiChatParameters {
    private static final Pattern REASONING_MODEL = Pattern.compile(
            "^(?:gpt-5(?:[.\\-].*)?|o\\d+(?:[.\\-].*)?)$");

    private OpenAiChatParameters() {
    }

    public static boolean usesReasoningDialect(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return false;
        }
        String normalized = modelId.trim().toLowerCase(Locale.ROOT);
        int namespaceSeparator = normalized.lastIndexOf('/');
        String modelName = namespaceSeparator < 0
                ? normalized
                : normalized.substring(namespaceSeparator + 1);
        return REASONING_MODEL.matcher(modelName).matches();
    }
}
