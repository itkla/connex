package ooo.klae.connex.backend.delivery;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ooo.klae.connex.backend.util.OneTimeTokenDigest;

/** Removes unsubscribe bearers while preserving provider identifiers used for webhook correlation. */
public final class CampaignDeliveryDiagnostics {
    private static final Pattern HEX_BEARER = Pattern.compile("[a-fA-F0-9]{64,}");
    private static final String REDACTED = "[redacted]";
    private static final int IDENTIFIER_LIMIT = 255;
    private static final int CORRELATION_PASSES = 4;

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
     * Every 64-hex window is inspected, including overlapping windows within longer runs. Each pass
     * replaces the leftmost non-overlapping matches, then rescans the output. After at most four
     * replacement passes, a final scan must find no recorded bearer; otherwise the identifier is
     * unusable and null is returned. Callers must not persist or look up the original identifier.
     * Each pass collects all candidate digests before resolving uncached membership in one batch.
     * Positive and negative membership is cached within this call only. At most five batch reads
     * are needed, including the final residual check; short identifiers perform no reads.
     * Identifiers exceeding the database column limit are rejected before any probes.
     *
     * @param identifier provider-returned identifier, possibly null
     * @param recordedDigests resolves the recorded subset in the caller's workspace in one batch
     * @return the deterministic non-secret correlation form, or null for null or unusable input
     * @throws IllegalArgumentException if the identifier cannot fit its database column
     */
    public static String correlationForm(String identifier, Function<Set<String>, Set<String>> recordedDigests) {
        if (identifier == null) {
            return null;
        }
        if (!fitsIdentifierColumn(identifier)) {
            throw new IllegalArgumentException("Provider identifier exceeds storage limit");
        }
        Map<String, Boolean> matches = new HashMap<>();
        String current = identifier;
        for (int pass = 0; pass <= CORRELATION_PASSES; pass++) {
            Matcher runs = HEX_BEARER.matcher(current);
            List<DigestWindow> windows = new ArrayList<>();
            Set<String> unresolved = new LinkedHashSet<>();
            while (runs.find()) {
                for (int start = runs.start(); start + 64 <= runs.end(); start++) {
                    String digest = OneTimeTokenDigest.sha256(
                            current.substring(start, start + 64).toLowerCase(Locale.ROOT));
                    windows.add(new DigestWindow(start, digest));
                    if (!matches.containsKey(digest)) {
                        unresolved.add(digest);
                    }
                }
            }
            if (!unresolved.isEmpty()) {
                Set<String> recorded = recordedDigests.apply(Set.copyOf(unresolved));
                for (String digest : unresolved) {
                    matches.put(digest, recorded.contains(digest));
                }
            }
            char[] result = null;
            int replacedUntil = 0;
            for (DigestWindow window : windows) {
                if (Boolean.TRUE.equals(matches.get(window.digest())) && window.start() >= replacedUntil) {
                    if (pass == CORRELATION_PASSES) {
                        return null;
                    }
                    if (result == null) {
                        result = current.toCharArray();
                    }
                    window.digest().getChars(0, 64, result, window.start());
                    replacedUntil = window.start() + 64;
                }
            }
            if (result == null) {
                return current;
            }
            current = new String(result);
        }
        return null;
    }

    private record DigestWindow(int start, String digest) {
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
