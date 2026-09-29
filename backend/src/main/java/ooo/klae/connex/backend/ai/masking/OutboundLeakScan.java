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
 *
 * <p>The same reasoning applies positionally, and more sharply. A server-authored envelope holds
 * tenant text only in its values; its property names and its message-role discriminator are emitted
 * by this codebase from literals no matter what the tenant's records are called. Scanning them as
 * tenant text refused every request seeding a record named after an envelope key — {@code Content},
 * {@code Role}, {@code System}, {@code Messages} — or after a role, {@code User} or
 * {@code Assistant}. Those positions are therefore skipped when the payload is the server's own
 * envelope, and scanned as ordinary text when the payload is provider-authored, where no position
 * is trustworthy. Nested JSON that arrives as a string value — a masked tool result — is one scalar
 * to this parser, so identifiers among its own keys are still covered.
 */
public final class OutboundLeakScan {
    /**
     * Shortest normalized identifier the scan flags, shared with the masking engine's residual
     * replacement pass so the replacer always covers at least what this scan can refuse.
     */
    static final int MIN_IDENTIFIER_LENGTH = 4;

    private static final String ROLE_PROPERTY = "role";

    private OutboundLeakScan() {
    }

    /**
     * Requires that a payload this server serialized contains none of the request's raw
     * identifiers in any position that can carry tenant text.
     *
     * <p>The name states a precondition the caller must honour: the payload's structure — its
     * property names and its message-role discriminator — must be written by this codebase, not by
     * a provider or a tenant, because those positions are skipped. Provider-authored text goes to
     * {@link #assertNoLeakStrict} instead, which trusts no position.
     *
     * @param serializedOutboundPayload exact JSON or text body this server built and is about to send
     * @param ctx request-local masking context
     * @param objectMapper provider payload JSON decoder
     * @throws MaskingLeakException when a raw identifier is present
     */
    public static void assertNoLeakInServerEnvelope(
            String serializedOutboundPayload, MaskingContext ctx, ObjectMapper objectMapper) {
        assertNoLeak(serializedOutboundPayload, ctx, objectMapper, false);
    }

    /**
     * Strict variant that ignores trusted-text collisions.
     *
     * <p>Provider-authored output — reasoning being normalized for return — is not the server's
     * own prompt: a raw identifier there is a leak regardless of which words the request envelope
     * happens to contain, so neither the trusted-text exemption nor the structural-position
     * exemption applies. Property names and role values the provider chose are scanned as the
     * tenant text they may well be.
     *
     * @param serializedOutboundPayload provider-authored text under validation
     * @param ctx request-local masking context
     * @param objectMapper payload JSON decoder
     * @throws MaskingLeakException when a raw identifier is present
     */
    public static void assertNoLeakStrict(
            String serializedOutboundPayload, MaskingContext ctx, ObjectMapper objectMapper) {
        assertNoLeak(serializedOutboundPayload, ctx, objectMapper, true);
    }

    private static void assertNoLeak(
            String serializedOutboundPayload,
            MaskingContext ctx,
            ObjectMapper objectMapper,
            boolean providerAuthored) {
        Objects.requireNonNull(serializedOutboundPayload, "serializedOutboundPayload");
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(objectMapper, "objectMapper");
        boolean honorTrustedText = !providerAuthored;
        List<ScannedField> fields = decodedJsonFields(serializedOutboundPayload, objectMapper);
        List<String> unprotectedFields = fields.stream()
                .filter(field -> providerAuthored || field.tenantBearing())
                .flatMap(field -> MaskingEngine.unprotectedTextSegments(field.text(), ctx).stream())
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
    private static List<ScannedField> decodedJsonFields(String payload, ObjectMapper objectMapper) {
        List<ScannedField> fields = new ArrayList<>();
        try (JsonParser parser = objectMapper.createParser(payload)) {
            for (JsonToken token = parser.nextToken(); token != null; token = parser.nextToken()) {
                if (token == JsonToken.PROPERTY_NAME) {
                    fields.add(new ScannedField(parser.getString(), false));
                } else if (token.isScalarValue() && token != JsonToken.VALUE_NULL) {
                    String value = parser.getString();
                    fields.add(new ScannedField(value, !isRoleDiscriminator(parser.currentName(), value)));
                }
            }
        } catch (JacksonException exception) {
            fields.add(new ScannedField(payload, true));
        }
        return fields;
    }

    private static boolean isRoleDiscriminator(String propertyName, String value) {
        return ROLE_PROPERTY.equals(propertyName) && MaskedMessage.ROLES.contains(value);
    }

    private record ScannedField(String text, boolean tenantBearing) {
    }

    /** Uses the same canonical form as registration, lookup admission and replacement. */
    static String normalizeForScan(String value) {
        return CanonicalText.canonical(value);
    }
}
