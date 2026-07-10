package ooo.klae.connex.backend.ai.masking;

import java.util.Locale;

/**
 * AI masking classification for structured custom-field values. Unknown, null, and blank tokens
 * fail closed to {@link #SPECIAL_CARE} so a malformed classification can never silently downgrade
 * provider exposure.
 */
public enum DataClassification {
    STANDARD,
    SENSITIVE,
    SPECIAL_CARE;

    /**
     * Parses the persisted custom-field classification token.
     * @param token one of {@code standard}, {@code sensitive}, or {@code special_care}
     * @return the parsed classification, or {@link #SPECIAL_CARE} when the token is absent or unknown
     */
    public static DataClassification fromToken(String token) {
        if (token == null || token.isBlank()) {
            return SPECIAL_CARE;
        }
        return switch (token.trim().toLowerCase(Locale.ROOT)) {
            case "standard" -> STANDARD;
            case "sensitive" -> SENSITIVE;
            case "special_care" -> SPECIAL_CARE;
            default -> SPECIAL_CARE;
        };
    }
}
