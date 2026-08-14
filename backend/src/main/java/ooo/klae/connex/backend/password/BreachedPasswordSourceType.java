package ooo.klae.connex.backend.password;

import java.util.Locale;

/**
 * Supported sources for breached-password screening.
 */
public enum BreachedPasswordSourceType {
    REMOTE,
    OFFLINE;

    static BreachedPasswordSourceType parse(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        try {
            return BreachedPasswordSourceType.valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Breached-password source must be REMOTE or OFFLINE");
        }
    }
}
