package ooo.klae.connex.backend.ai.provider;

import java.util.Map;
import java.util.Objects;

/**
 * Decrypted credential material for an organization-scoped BYOP provider.
 * @param values provider-specific credential values
 */
public record AiCredentials(Map<String, String> values) {

    public AiCredentials {
        values = Map.copyOf(Objects.requireNonNull(values, "values"));
    }

    /**
     * Creates an immutable provider credential bundle.
     * @param values provider-specific credential values
     * @return immutable credential bundle
     */
    public static AiCredentials of(Map<String, String> values) {
        return new AiCredentials(values);
    }

    /**
     * Returns an optional credential value.
     * @param key provider credential key
     * @return credential value, or null when absent
     */
    public String get(String key) {
        if (key == null) {
            return null;
        }
        return values.get(key);
    }

    /**
     * Returns a required non-blank credential value.
     * @param key provider credential key
     * @return credential value
     * @throws AiProviderException when the value is absent or blank
     */
    public String require(String key) {
        if (key == null || key.isBlank()) {
            throw new AiProviderException("AI provider credential key is required");
        }
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new AiProviderException("AI provider credential " + key + " is required");
        }
        return value;
    }

    @Override
    public String toString() {
        return "AiCredentials[values=<redacted>]";
    }
}
