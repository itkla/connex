package ooo.klae.connex.backend.ai.masking;

import java.text.Normalizer;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Runtime final gate for serialized AI provider payloads. It scans the exact outbound string for
 * request-local raw identifiers and throws without echoing the matched value, turning masking into
 * a per-request invariant immediately before send.
 */
public final class OutboundLeakScan {
    /**
     * Shortest normalized identifier the scan flags, shared with the masking engine's residual
     * replacement pass so the replacer always covers at least what this scan can refuse.
     */
    static final int MIN_IDENTIFIER_LENGTH = 4;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private OutboundLeakScan() {
    }

    /**
     * Requires that the serialized provider payload contains none of the request's raw identifiers.
     * @param serializedOutboundPayload exact JSON or text body about to be sent
     * @param ctx request-local masking context
     * @param objectMapper provider payload JSON decoder
     * @throws MaskingLeakException when a raw identifier is present
     */
    public static void assertNoLeak(String serializedOutboundPayload, MaskingContext ctx, ObjectMapper objectMapper) {
        Objects.requireNonNull(serializedOutboundPayload, "serializedOutboundPayload");
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(objectMapper, "objectMapper");
        String rawPayload = normalizeForScan(serializedOutboundPayload);
        String decodedPayload = decodedJsonText(serializedOutboundPayload, objectMapper);
        Set<String> leakedTokens = new LinkedHashSet<>();
        Set<EntityKind> leakedKinds = EnumSet.noneOf(EntityKind.class);
        for (MaskingContext.IdentifierEntry entry : ctx.identifierEntriesByLongestRawValue()) {
            String normalizedIdentifier = normalizeForScan(entry.rawValue());
            if (normalizedIdentifier.length() >= MIN_IDENTIFIER_LENGTH
                    && (rawPayload.contains(normalizedIdentifier) || decodedPayload.contains(normalizedIdentifier))) {
                leakedTokens.add(entry.token());
                leakedKinds.add(entry.kind());
            }
        }
        if (!leakedTokens.isEmpty()) {
            throw new MaskingLeakException(leakedKinds, leakedTokens.size());
        }
    }

    private static String decodedJsonText(String serializedOutboundPayload, ObjectMapper objectMapper) {
        try {
            JsonNode root = objectMapper.readTree(serializedOutboundPayload);
            if (root == null) {
                return "";
            }
            StringBuilder decoded = new StringBuilder(serializedOutboundPayload.length());
            appendTextValues(root, decoded);
            return normalizeForScan(decoded.toString());
        } catch (Exception exception) {
            return "";
        }
    }

    private static void appendTextValues(JsonNode node, StringBuilder decoded) {
        if (node.isString()) {
            decoded.append('\u0000').append(node.asString()).append('\u0000');
            return;
        }
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                decoded.append('\u0000').append(entry.getKey()).append('\u0000');
                appendTextValues(entry.getValue(), decoded);
            });
            return;
        }
        for (JsonNode child : node) {
            appendTextValues(child, decoded);
        }
    }

    private static String normalizeForScan(String value) {
        String normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return WHITESPACE.matcher(normalized).replaceAll(" ");
    }
}
