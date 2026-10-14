package ooo.klae.connex.backend.delivery;

import java.util.regex.Pattern;

/** Removes unsubscribe bearers from provider diagnostics before persistence, logging, or truncation. */
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
        String scrubbed = bearer == null || bearer.isEmpty()
                ? message : message.replace(bearer, REDACTED);
        return HEX_BEARER.matcher(scrubbed).replaceAll(REDACTED);
    }
}
