package ooo.klae.connex.backend.ai.masking;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * every request that seeds it. MEMBER labels never receive this label-wide exemption: only complete
 * static fields and server-authored system/tool/schema positions are excluded for members.
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
    private static final Pattern MASKING_SENTINEL = Pattern.compile("\\[redacted]|\\[omitted by policy]");

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
        List<ScannedField> fields = decodedJsonFields(serializedOutboundPayload, objectMapper);
        assertNoLeak(fields, ctx, providerAuthored, false);
    }

    /** Checks one already-masked message while its recorded MEMBER containment scope is active. */
    static void assertNoLeakInReplay(String text, MaskingContext ctx) {
        assertNoLeak(List.of(new ScannedField(text, true, false)), ctx, false, false);
    }

    /**
     * Screens one provider-authored native replay field without trusting sentinel, key or role
     * positions. JSON arguments are decoded independently, including keys and escaped scalars.
     * Shared member values retain their non-member protections here; unrelated non-member
     * envelope policy remains owned by the complete server-envelope scan.
     *
     * @param field provider-authored field, serialized as JSON for a scalar or as its arguments object
     * @param ctx request-local masking context
     * @param objectMapper payload JSON decoder
     * @throws MaskingLeakException when a raw member label is present
     */
    public static void assertNoMemberLeakInProviderReplay(
            String field, MaskingContext ctx, ObjectMapper objectMapper) {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(objectMapper, "objectMapper");
        assertNoLeak(List.of(new ScannedField(field, true, false)), ctx, true, true);
        assertNoLeak(decodedJsonFields(field, objectMapper), ctx, true, true);
    }

    /**
     * Checks the complete serialized envelope for existing kinds and its classified text for MEMBER.
     * Static text carries no member identity. Provider text receives neither static nor sentinel
     * exemptions. Exact masker-inserted spans retain server origin even within provider text;
     * tenant text has already passed replacement before server sentinels were inserted.
     */
    public static void assertNoLeakInClassifiedEnvelope(
            String payload, List<OutboundSegment> segments, MaskingContext ctx, ObjectMapper objectMapper) {
        assertNoLeak(decodedJsonFields(payload, objectMapper), ctx, false, false, true, false);
        for (OutboundSegment segment : segments) {
            if (segment.origin() == OutboundSegment.Origin.SERVER_STATIC
                    || segment.origin() == OutboundSegment.Origin.PROVIDER_OPAQUE) {
                continue;
            }
            if (segment.origin() == OutboundSegment.Origin.PROVIDER_AUTHORED) {
                assertNoMemberLeakInProviderReplay(segment.memberScanText(),
                        segment.memberScanContext() == null ? ctx : segment.memberScanContext(), objectMapper);
            } else if (segment.origin() == OutboundSegment.Origin.TENANT_TOOL_RESULT) {
                assertNoLeak(decodedJsonFields(segment.text(), objectMapper, true), ctx, false, true, false, true);
            } else {
                assertNoLeak(List.of(new ScannedField(segment.text(), true, false)), ctx, false, true, false, true);
            }
        }
    }

    private static void assertNoLeak(
            List<ScannedField> fields, MaskingContext ctx, boolean providerAuthored, boolean membersOnly) {
        assertNoLeak(fields, ctx, providerAuthored, membersOnly, false, false);
    }

    private static void assertNoLeak(
            List<ScannedField> fields, MaskingContext ctx, boolean providerAuthored, boolean membersOnly,
            boolean excludeMembers, boolean classified) {
        boolean honorTrustedText = !providerAuthored;
        List<PreparedField> preparedFields = fields.stream()
                .filter(field -> providerAuthored || field.tenantBearing())
                .flatMap(field -> MaskingEngine.unprotectedTextSegments(field.text(), ctx).stream()
                        .map(text -> new PreparedField(MaskingEngine.screenText(text),
                                classified || providerAuthored
                                        || !field.staticPosition() && !ctx.isTrustedStaticField(field.text()))))
                .toList();
        List<MaskingEngine.ScreenText> allFields = preparedFields.stream().map(PreparedField::input).toList();
        List<MaskingEngine.ScreenText> memberFields = preparedFields.stream().filter(PreparedField::memberBearing)
                .flatMap(field -> memberSegments(field.input(), providerAuthored).stream()).toList();
        Set<String> leakedIdentifiers = new LinkedHashSet<>();
        Set<EntityKind> leakedKinds = EnumSet.noneOf(EntityKind.class);
        for (MaskingContext.IdentifierEntry entry : ctx.identifierEntries()) {
            boolean member = !excludeMembers && ctx.isMemberIdentifier(entry.rawValue());
            boolean nonMember = (!membersOnly || member) && ctx.hasNonMemberIdentifier(entry.rawValue());
            if (!member && !nonMember) {
                continue;
            }
            List<MaskingEngine.ScreenText> nonMemberFields = membersOnly ? memberFields : allFields;
            String identifier = entry.canonicalValue();
            boolean canonicalLeak = nonMember && identifier.length() >= MIN_IDENTIFIER_LENGTH
                    && !identifier.isEmpty()
                    && nonMemberFields.stream().anyMatch(field -> field.literal().value().contains(identifier));
            boolean labelLeak = nonMember && entry.labelEligible()
                    && entry.labelValue().length() >= MIN_IDENTIFIER_LENGTH
                    && nonMemberFields.stream().anyMatch(field -> field.labels().value().contains(entry.labelValue()));
            boolean rawOnly = !entry.replacementEligible() && entry.rawValue().length() >= MIN_IDENTIFIER_LENGTH;
            boolean exactRawLeak = nonMember && rawOnly
                    && nonMemberFields.stream().anyMatch(field -> field.original().contains(entry.rawValue()));
            boolean nonMemberLeak = (canonicalLeak || labelLeak || exactRawLeak)
                    && !(honorTrustedText && ctx.isTrustedNonMemberTextCollision(entry.rawValue()));
            boolean memberLeak = member && memberFields.stream().anyMatch(field ->
                    MaskingEngine.containsMemberMatch(field, entry, ctx.requiresMemberContainment(entry.rawValue()))
                            || rawOnly && field.original().contains(entry.rawValue()));
            if (nonMemberLeak || memberLeak) {
                leakedIdentifiers.add(entry.rawValue());
                leakedKinds.add(entry.kind());
            }
        }
        if (!leakedIdentifiers.isEmpty()) {
            throw new MaskingLeakException(leakedKinds, leakedIdentifiers.size());
        }
    }

    /** Only final server-authored marker positions are exempt; source text is masked before insertion. */
    private static List<MaskingEngine.ScreenText> memberSegments(MaskingEngine.ScreenText input, boolean providerAuthored) {
        if (providerAuthored) {
            return List.of(input);
        }
        Matcher marker = MASKING_SENTINEL.matcher(input.original());
        if (!marker.find()) {
            return List.of(input);
        }
        List<MaskingEngine.ScreenText> segments = new ArrayList<>();
        int end = 0;
        do {
            segments.add(MaskingEngine.screenText(input.original().substring(end, marker.start())));
            end = marker.end();
        } while (marker.find());
        segments.add(MaskingEngine.screenText(input.original().substring(end)));
        return List.copyOf(segments);
    }

    /** Visits every key and non-null scalar separately, including earlier values of duplicate keys. */
    private static List<ScannedField> decodedJsonFields(String payload, ObjectMapper objectMapper) {
        return decodedJsonFields(payload, objectMapper, false);
    }

    private static List<ScannedField> decodedJsonFields(
            String payload, ObjectMapper objectMapper, boolean includeRoleValues) {
        List<ScannedField> fields = new ArrayList<>();
        try (JsonParser parser = objectMapper.createParser(payload)) {
            int depth = 0;
            String rootField = null;
            for (JsonToken token = parser.nextToken(); token != null; token = parser.nextToken()) {
                if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
                    depth++;
                } else if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) {
                    depth--;
                } else if (token == JsonToken.PROPERTY_NAME) {
                    if (depth == 1) {
                        rootField = parser.getString();
                    }
                    fields.add(new ScannedField(parser.getString(), false, false));
                } else if (token.isScalarValue() && token != JsonToken.VALUE_NULL) {
                    String value = parser.getString();
                    fields.add(new ScannedField(value, includeRoleValues || !isRoleDiscriminator(parser.currentName(), value),
                            "system".equals(rootField) || "tools".equals(rootField) || "responseSchema".equals(rootField)));
                }
            }
        } catch (JacksonException exception) {
            fields.add(new ScannedField(payload, true, false));
        }
        return fields;
    }

    private static boolean isRoleDiscriminator(String propertyName, String value) {
        return ROLE_PROPERTY.equals(propertyName) && MaskedMessage.ROLES.contains(value);
    }

    private record ScannedField(String text, boolean tenantBearing, boolean staticPosition) {
    }

    private record PreparedField(MaskingEngine.ScreenText input, boolean memberBearing) {
    }

    /** Uses the same canonical form as registration, lookup admission and replacement. */
    static String normalizeForScan(String value) {
        return CanonicalText.canonical(value);
    }
}
