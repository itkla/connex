package ooo.klae.connex.backend.delivery;

import java.util.regex.Pattern;

/** Removes unsubscribe bearers while preserving provider identifiers used for webhook correlation. */
public final class CampaignDeliveryDiagnostics {
    private static final Pattern HEX_BEARER = Pattern.compile("[a-fA-F0-9]{64,}");
    private static final String REDACTED = "[redacted]";

    private CampaignDeliveryDiagnostics() {
    }

    /**
     * Preserves diagnostic text while replacing the current bearer and any other 64-hex run.
     *
     * @param message untrusted diagnostic, possibly null
     * @param bearer current attempt's bearer, or null when only defensive scrubbing is available
     * @return scrubbed diagnostic, preserving null
     */
    public static String scrub(String message, String bearer) {
        if (message == null) {
            return null;
        }
        return HEX_BEARER.matcher(scrubIdentifier(message, bearer)).replaceAll(REDACTED);
    }

    /**
     * Removes only the exact attempt bearer, preserving unrelated opaque identifiers including hex ids.
     *
     * @param identifier provider-returned identifier, possibly null
     * @param bearer current attempt's bearer
     * @return identifier with exact bearer occurrences replaced, preserving null
     */
    public static String scrubIdentifier(String identifier, String bearer) {
        return identifier == null || bearer == null || bearer.isEmpty()
                ? identifier : identifier.replace(bearer, REDACTED);
    }
}
