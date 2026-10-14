package ooo.klae.connex.backend.delivery;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ooo.klae.connex.backend.util.OneTimeTokenDigest;

/** Removes unsubscribe bearers while preserving provider identifiers used for webhook correlation. */
public final class CampaignDeliveryDiagnostics {
    private static final Pattern HEX_BEARER = Pattern.compile("[a-fA-F0-9]{64,}");
    private static final String REDACTED = "[redacted]";
    private static final int IDENTIFIER_LIMIT = 255;

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
        String text = bearer == null || bearer.isEmpty() ? message : message.replace(bearer, REDACTED);
        return HEX_BEARER.matcher(text).replaceAll(REDACTED);
    }

    /**
     * Replaces recorded bearer windows with their digests, preserving unrelated identifier bytes.
     * Every 64-hex window is inspected in the original input, including overlapping windows within
     * longer runs. The leftmost non-overlapping matches are replaced; generated text is never rescanned.
     * Digest membership is cached within this call only, and short identifiers perform no probes.
     * Identifiers exceeding the database column limit are rejected before any probes.
     *
     * @param identifier provider-returned identifier, possibly null
     * @param recordedDigest tests membership in the caller's resolved delivery or workspace scope
     * @return the deterministic non-secret correlation form, preserving null
     * @throws IllegalArgumentException if the identifier cannot fit its database column
     */
    public static String correlationForm(String identifier, Predicate<String> recordedDigest) {
        if (identifier == null) {
            return null;
        }
        if (!fitsIdentifierColumn(identifier)) {
            throw new IllegalArgumentException("Provider identifier exceeds storage limit");
        }
        Matcher runs = HEX_BEARER.matcher(identifier);
        char[] result = null;
        int replacedUntil = 0;
        Map<String, Boolean> matches = new HashMap<>();
        while (runs.find()) {
            for (int start = runs.start(); start + 64 <= runs.end(); start++) {
                String digest = OneTimeTokenDigest.sha256(
                        identifier.substring(start, start + 64).toLowerCase(Locale.ROOT));
                if (matches.computeIfAbsent(digest, recordedDigest::test) && start >= replacedUntil) {
                    if (result == null) {
                        result = identifier.toCharArray();
                    }
                    digest.getChars(0, 64, result, start);
                    replacedUntil = start + 64;
                }
            }
        }
        return result == null ? identifier : new String(result);
    }

    /**
     * Tests the shared provider identifier column's capacity without digest probes.
     *
     * @param identifier provider-returned identifier, possibly null
     * @return whether the identifier is null or at most 255 Unicode code points
     */
    public static boolean fitsIdentifierColumn(String identifier) {
        return identifier == null || identifier.codePointCount(0, identifier.length()) <= IDENTIFIER_LIMIT;
    }
}
