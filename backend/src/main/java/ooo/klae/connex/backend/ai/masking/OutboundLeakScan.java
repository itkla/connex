package ooo.klae.connex.backend.ai.masking;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Runtime final gate for serialized AI provider payloads. It scans the exact outbound string for
 * request-local raw identifiers and throws without echoing the matched value, turning masking into
 * a per-request invariant immediately before send.
 */
public final class OutboundLeakScan {
    private static final int MIN_IDENTIFIER_LENGTH = 4;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private OutboundLeakScan() {
    }

    /**
     * Requires that the serialized provider payload contains none of the request's raw identifiers.
     * @param serializedOutboundPayload exact JSON or text body about to be sent
     * @param ctx request-local masking context
     * @throws MaskingLeakException when a raw identifier is present
     */
    public static void assertNoLeak(String serializedOutboundPayload, MaskingContext ctx) {
        Objects.requireNonNull(serializedOutboundPayload, "serializedOutboundPayload");
        Objects.requireNonNull(ctx, "ctx");
        String payload = normalizeForScan(serializedOutboundPayload);
        for (String identifier : ctx.identifierDictionary()) {
            String normalizedIdentifier = normalizeForScan(identifier);
            if (normalizedIdentifier.length() >= MIN_IDENTIFIER_LENGTH && payload.contains(normalizedIdentifier)) {
                throw new MaskingLeakException("Outbound AI payload contains an unmasked identifier");
            }
        }
    }

    private static String normalizeForScan(String value) {
        String normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return WHITESPACE.matcher(normalized).replaceAll(" ");
    }
}
