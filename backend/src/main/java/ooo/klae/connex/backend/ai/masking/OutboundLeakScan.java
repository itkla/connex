package ooo.klae.connex.backend.ai.masking;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.ObjectMapper;

/**
 * Runtime final gate for serialized AI provider payloads. It scans the exact outbound string for
 * request-local raw identifiers and throws without echoing the matched value, turning masking into
 * a per-request invariant immediately before send. Decoded fields never share a matching domain.
 * Canonical-ineligible raw values retain exact-text coverage without a second normalization.
 *
 * <p>An identifier whose raw value the request's registered server-authored text provably contains
 * is skipped: the server emits that word in every prompt regardless of tenant data, so its presence
 * carries no tenant signal, and refusing it would make a record named a common envelope word poison
 * every request that seeds it. Tenant occurrences of the value are still tokenized by the replacer.
 */
public final class OutboundLeakScan {
    /**
     * Shortest normalized identifier the scan flags, shared with the masking engine's residual
     * replacement pass so the replacer always covers at least what this scan can refuse.
     */
    static final int MIN_IDENTIFIER_LENGTH = 4;

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
        assertNoLeak(serializedOutboundPayload, ctx, objectMapper, true);
    }

    /**
     * Strict variant that ignores trusted-text collisions.
     *
     * <p>Provider-authored output — reasoning being normalized for return — is not the server's
     * own prompt: a raw identifier there is a leak regardless of which words the request envelope
     * happens to contain, so the trusted-text exemption must not apply.
     *
     * @param serializedOutboundPayload provider-authored text under validation
     * @param ctx request-local masking context
     * @param objectMapper payload JSON decoder
     * @throws MaskingLeakException when a raw identifier is present
     */
    public static void assertNoLeakStrict(
            String serializedOutboundPayload, MaskingContext ctx, ObjectMapper objectMapper) {
        assertNoLeak(serializedOutboundPayload, ctx, objectMapper, false);
    }

    private static void assertNoLeak(
            String serializedOutboundPayload,
            MaskingContext ctx,
            ObjectMapper objectMapper,
            boolean honorTrustedText) {
        Objects.requireNonNull(serializedOutboundPayload, "serializedOutboundPayload");
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(objectMapper, "objectMapper");
        List<String> fields = decodedJsonFields(serializedOutboundPayload, objectMapper);
        List<String> unprotectedFields = fields.stream()
                .flatMap(field -> MaskingEngine.unprotectedTextSegments(field, ctx).stream())
                .toList();
        List<String> canonicalFields = unprotectedFields.stream().map(OutboundLeakScan::normalizeForScan).toList();
        List<String> labelFields = unprotectedFields.stream()
                .map(field -> CanonicalText.projectLabels(field).value()).toList();
        Set<String> leakedTokens = new LinkedHashSet<>();
        Set<EntityKind> leakedKinds = EnumSet.noneOf(EntityKind.class);
        for (MaskingContext.IdentifierEntry entry : ctx.identifierEntries()) {
            String identifier = entry.canonicalValue();
            boolean canonicalLeak = identifier.length() >= MIN_IDENTIFIER_LENGTH
                    && canonicalFields.stream().anyMatch(field -> field.contains(identifier));
            boolean labelLeak = entry.labelEligible() && entry.labelValue().length() >= MIN_IDENTIFIER_LENGTH
                    && labelFields.stream().anyMatch(field -> field.contains(entry.labelValue()));
            boolean exactRawLeak = !entry.replacementEligible() && entry.rawValue().length() >= MIN_IDENTIFIER_LENGTH
                    && unprotectedFields.stream().anyMatch(field -> field.contains(entry.rawValue()));
            if ((canonicalLeak || labelLeak || exactRawLeak)
                    && !(honorTrustedText && ctx.isTrustedTextCollision(entry.rawValue()))) {
                leakedTokens.add(entry.token());
                leakedKinds.add(entry.kind());
            }
        }
        if (!leakedTokens.isEmpty()) {
            throw new MaskingLeakException(leakedKinds, leakedTokens.size());
        }
    }

    /** Visits every key and non-null scalar separately, including earlier values of duplicate keys. */
    private static List<String> decodedJsonFields(String payload, ObjectMapper objectMapper) {
        List<String> fields = new ArrayList<>();
        try (JsonParser parser = objectMapper.createParser(payload)) {
            for (JsonToken token = parser.nextToken(); token != null; token = parser.nextToken()) {
                if (token == JsonToken.PROPERTY_NAME || (token.isScalarValue() && token != JsonToken.VALUE_NULL)) {
                    fields.add(parser.getString());
                }
            }
        } catch (JacksonException exception) {
            fields.add(payload);
        }
        return fields;
    }

    /** Uses the same canonical form as registration, lookup admission and replacement. */
    static String normalizeForScan(String value) {
        return CanonicalText.canonical(value);
    }
}
