package ooo.klae.connex.backend.ai.masking;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ooo.klae.connex.backend.ai.AiPrivacyMode;

/**
 * Ephemeral per-request token map for AI masking. The map is never persisted or serialized, and
 * {@link #toString()} is redacted so accidental logs do not reveal raw CRM identifiers. Connex
 * still holds the request-local map and can re-identify provider output, so this boundary reduces
 * Leg-2 provider exposure but is not anonymization and does not change Connex's handler status.
 */
public final class MaskingContext {
    private static final Logger log = LoggerFactory.getLogger(MaskingContext.class);
    private static final Pattern DISPATCHED_TOKEN = Pattern.compile("\\{\\{\\s*([A-Z][1-9][0-9]*)\\s*}}");
    private static final Pattern MEMBER_PLACEHOLDER = Pattern.compile("\\{\\{\\s*([Mm][1-9][0-9]*)\\s*}}");
    private static final Pattern SOURCE_GRAPHEME = Pattern.compile("\\X");
    private static final Pattern PLACEHOLDER_PREFIX = Pattern.compile("\\{\\{?\\s*+[A-Za-z]?[0-9]*+\\s*+}?");
    private final Map<String, String> tokenToOriginalValue = new LinkedHashMap<>();
    private final Map<String, EntityKind> tokenToKind = new LinkedHashMap<>();
    private final Map<String, String> originalValueToToken = new LinkedHashMap<>();
    private final Map<String, IdentifierEntry> rawIdentifierEntries = new LinkedHashMap<>();
    private final Set<String> unsafeIdentifierValues = new LinkedHashSet<>();
    private final Set<String> identifierDictionary = new LinkedHashSet<>();
    private final Set<String> trustedStaticTexts = new LinkedHashSet<>();
    private final Set<String> trustedStaticFields = new LinkedHashSet<>();
    private final EnumMap<EntityKind, Integer> tokenCounts = new EnumMap<>(EntityKind.class);
    private final Set<String> exposedTokens = new LinkedHashSet<>();
    private final Set<String> reservedTokens = new LinkedHashSet<>();
    private final Set<String> memberIdentifierValues = new LinkedHashSet<>();
    private Set<String> replayMemberValues = Set.of();
    private final AiPrivacyMode privacyMode;

    /** Creates a request-local masked context. */
    public MaskingContext() {
        this(AiPrivacyMode.MASKED);
    }

    /** Creates a request-local context for the resolved provider disclosure posture. */
    public MaskingContext(AiPrivacyMode privacyMode) {
        this.privacyMode = Objects.requireNonNull(privacyMode, "privacyMode");
    }

    /** Returns the immutable disclosure posture captured before prompt assembly. */
    public AiPrivacyMode privacyMode() {
        return privacyMode;
    }

    /**
     * Returns a stable placeholder for an identifier within this request, assigning by first
     * appearance within each entity kind. Exact original values determine token identity and
     * demasking; canonical and source-mapped label variants supply free-text matching coverage
     * without merging distinct display identities.
     * @param kind identifier namespace
     * @param rawValue original CRM display value
     * @return request-local placeholder, or a redaction marker for an unsafe stored identifier
     */
    public String tokenFor(EntityKind kind, String rawValue) {
        Objects.requireNonNull(kind, "kind");
        if (rawValue == null || rawValue.isBlank()) {
            throw new IllegalArgumentException("Cannot tokenize a blank identifier");
        }
        if (kind == EntityKind.MEMBER) {
            memberIdentifierValues.add(rawValue);
        }
        registerIdentifier(kind, rawValue, originalValueToToken.get(rawValue));
        IdentifierEntry entry = rawIdentifierEntries.get(rawValue);
        if (entry != null && (entry.placeholderShaped() || entry.irreversibleMemberSymbol())) {
            return MaskingEngine.REDACTED;
        }
        String token = originalValueToToken.get(rawValue);
        if (token == null) {
            token = nextToken(kind);
            originalValueToToken.put(rawValue, token);
            tokenToOriginalValue.put(token, rawValue);
            tokenToKind.put(token, kind);
        }
        registerIdentifier(kind, rawValue, token);
        return (entry != null && entry.unsafe()) || originalValueForToken(token) == null
                ? MaskingEngine.REDACTED : token;
    }

    /** Registers a member label for replacement and scanning without issuing a token. */
    public void registerMemberCandidate(String rawValue) {
        if (rawValue != null && !rawValue.isBlank()) {
            memberIdentifierValues.add(rawValue);
            if (privacyMode != AiPrivacyMode.UNMASKED || !CanonicalText.storedIdentifier(rawValue).converged()) {
                registerIdentifier(EntityKind.MEMBER, rawValue, originalValueToToken.get(rawValue));
            }
        }
    }

    private void registerIdentifier(EntityKind kind, String rawValue, String token) {
        IdentifierEntry existing = rawIdentifierEntries.get(rawValue);
        if (existing != null) {
            boolean memberSymbol = existing.irreversibleMemberSymbol()
                    || kind == EntityKind.MEMBER && isShortNonAsciiSymbol(rawValue, existing.canonicalValue());
            if (existing.token() == null && token != null || memberSymbol != existing.irreversibleMemberSymbol()) {
                rawIdentifierEntries.put(rawValue, new IdentifierEntry(rawValue,
                        existing.canonicalValue(), existing.labelValue(), token == null ? existing.token() : token,
                        token == null ? existing.kind() : Objects.requireNonNull(tokenToKind.get(token)),
                        existing.unsafe() || memberSymbol, existing.placeholderShaped(), memberSymbol));
            }
            return;
        }
        CanonicalText.StoredIdentifier stored = CanonicalText.storedIdentifier(rawValue);
        var placeholders = DISPATCHED_TOKEN.matcher(java.text.Normalizer.normalize(
                rawValue, java.text.Normalizer.Form.NFKC).toUpperCase(java.util.Locale.ROOT));
        boolean placeholderShaped = false;
        while (placeholders.find()) {
            placeholderShaped = true;
            reservedTokens.add("{{" + placeholders.group(1) + "}}");
        }
        String canonicalValue = stored.literal();
        boolean memberSymbol = kind == EntityKind.MEMBER && isShortNonAsciiSymbol(rawValue, canonicalValue);
        if (isCanonicalDictionaryEligible(canonicalValue)
                || rawValue.length() >= OutboundLeakScan.MIN_IDENTIFIER_LENGTH || memberSymbol) {
            identifierDictionary.add(rawValue);
            rawIdentifierEntries.put(rawValue, new IdentifierEntry(rawValue, canonicalValue,
                    stored.label(), token, token == null ? kind : tokenToKind.get(token),
                    !stored.converged() || placeholderShaped || memberSymbol, placeholderShaped, memberSymbol));
            if (!stored.converged()) {
                unsafeIdentifierValues.add(canonicalValue);
                log.warn("AI stored identifier omitted: non-converging label projection; kind={}", kind);
            }
        }
    }

    /** Short non-ASCII symbols carry member identity but cannot supply reversible placeholders. */
    static boolean isShortNonAsciiSymbol(String rawValue, String canonicalValue) {
        return !canonicalValue.isEmpty() && canonicalValue.length() < OutboundLeakScan.MIN_IDENTIFIER_LENGTH
                && canonicalValue.codePoints().noneMatch(Character::isLetterOrDigit)
                && rawValue.codePoints().anyMatch(value -> value > 127
                        && !Character.isWhitespace(value) && !Character.isSpaceChar(value));
    }

    /**
     * Records member tokens in the final envelope at the actual provider dispatch boundary.
     * Earlier dispatched tokens remain reversible throughout this request.
     */
    public void exposeDispatchedPayload(String serializedPayload) {
        var matcher = DISPATCHED_TOKEN.matcher(Objects.requireNonNull(serializedPayload, "serializedPayload"));
        while (matcher.find()) {
            String token = "{{" + matcher.group(1) + "}}";
            if (originalValueForToken(token) != null) {
                exposedTokens.add(token);
            }
        }
    }

    boolean isMemberIdentifier(String rawValue) {
        return memberIdentifierValues.contains(rawValue);
    }

    boolean isExposedMemberToken(String token) {
        return exposedTokens.contains(token) && memberIdentifierValues.contains(originalValueForToken(token));
    }

    /**
     * Detects unexposed member placeholders, including tokens reconstructed by link cleanup.
     * The identity source map preserves doubled braces that ordinary prose canonicalization cancels.
     */
    public boolean containsUnexposedMemberToken(String text) {
        return !refusedMemberPlaceholders(text).isEmpty();
    }

    /** Source spans whose spelling or exposure cannot be demasked by the canonical token parser. */
    List<PlaceholderSpan> refusedMemberPlaceholders(String text) {
        Objects.requireNonNull(text, "text");
        var labels = placeholderProjection(text);
        var matcher = MEMBER_PLACEHOLDER.matcher(labels.value());
        List<PlaceholderSpan> refused = new ArrayList<>();
        while (matcher.find()) {
            int start = labels.starts()[matcher.start()];
            int end = labels.ends()[matcher.end() - 1];
            var literal = DISPATCHED_TOKEN.matcher(text.substring(start, end));
            if (!literal.matches() || !literal.group(1).equals(matcher.group(1).toUpperCase(java.util.Locale.ROOT))
                    || demaskableValueForToken("{{" + matcher.group(1).toUpperCase(java.util.Locale.ROOT) + "}}") == null) {
                refused.add(new PlaceholderSpan(start, end));
            }
        }
        return List.copyOf(refused);
    }

    /**
     * Withholds every incomplete spelling recognized by member validation, preserving its original
     * offset through the same NFKC and label projection. A flush cannot publish a refused prefix.
     */
    public static int withheldPlaceholderStart(String text, int safeLength) {
        var labels = placeholderProjection(text.substring(0, safeLength));
        int start = labels.value().lastIndexOf('{');
        if (start < 0) {
            return safeLength;
        }
        if (start > 0 && labels.value().charAt(start - 1) == '{') {
            start--;
        }
        return PLACEHOLDER_PREFIX.matcher(labels.value().substring(start)).matches()
                ? labels.starts()[start] : safeLength;
    }

    private static CanonicalText.Projection placeholderProjection(String text) {
        StringBuilder normalized = new StringBuilder();
        List<Integer> starts = new ArrayList<>();
        List<Integer> ends = new ArrayList<>();
        var units = SOURCE_GRAPHEME.matcher(text);
        while (units.find()) {
            String unit = java.text.Normalizer.normalize(units.group(), java.text.Normalizer.Form.NFKC);
            normalized.append(unit);
            for (int index = 0; index < unit.length(); index++) {
                starts.add(units.start());
                ends.add(units.end());
            }
        }
        return ConversationText.projectLabels(new CanonicalText.Projection(normalized.toString(),
                starts.stream().mapToInt(Integer::intValue).toArray(),
                ends.stream().mapToInt(Integer::intValue).toArray()));
    }

    record PlaceholderSpan(int start, int end) {
    }

    /** Captures known raw member mentions using the same script-aware boundary gate as user text. */
    public List<String> memberCandidatesIn(String text) {
        return memberCandidatesIn(text, List.of());
    }

    /**
     * Adds precise demask provenance only at the originating textual field. Raw candidates still
     * require mention boundaries; token-derived values must remain contained after typed normalization.
     * Input projections are built once and reused across the complete candidate set.
     */
    public List<String> memberCandidatesIn(String text, List<String> demaskedValues) {
        Objects.requireNonNull(text, "text");
        Set<String> recorded = Set.copyOf(demaskedValues);
        if (memberIdentifierValues.isEmpty() || text.isBlank()) {
            return List.of();
        }
        var input = MaskingEngine.mentionScanText(text);
        var candidates = privacyMode == AiPrivacyMode.UNMASKED ? memberIdentifierValues.stream()
                : rawIdentifierEntries.values().stream().map(IdentifierEntry::rawValue)
                        .filter(this::isMemberIdentifier);
        return candidates.filter(value -> MaskingEngine.containsMemberMention(input, value)
                        || recorded.contains(value) && MaskingEngine.containsRecordedMemberValue(input, value))
                .toList();
    }

    String demaskableValueForToken(String token) {
        return tokenToKind.get(token) == EntityKind.MEMBER && !exposedTokens.contains(token)
                ? null : originalValueForToken(token);
    }

    /** Applies containment only while masking and scanning the one message owning this provenance. */
    public <T> T withReplayMemberValues(List<String> values, Supplier<T> work) {
        Set<String> previous = replayMemberValues;
        replayMemberValues = Set.copyOf(values);
        try {
            return work.get();
        } finally {
            replayMemberValues = previous;
        }
    }

    boolean requiresMemberContainment(String rawValue) {
        return replayMemberValues.contains(rawValue);
    }

    /** Recognizes an unsafe structured value without projecting its exhausted label again. */
    boolean isUnsafeIdentifierValue(String value) {
        return !unsafeIdentifierValues.isEmpty()
                && unsafeIdentifierValues.contains(MaskingEngine.normalizeIdentifierValue(value));
    }

    /**
     * Whether a raw value may drive free-text replacement and mention matching.
     *
     * <p>A display name made only of punctuation or separators — a record literally named
     * {@code "."} or {@code "-"} — carries no tenant identity, yet it has no word boundary, so the
     * replacer would substitute every occurrence in uncontrolled text and expand a long note by
     * roughly the redaction marker's length each time. Refusing dictionary membership removes that
     * amplification, and the structured field the value came from still tokenizes.
     *
     * <p>Eligibility uses the stored replacement canonicalization, so control-only names cannot
     * become empty matchers. Short symbol-only canonical values are also excluded; short names
     * containing a letter or digit, including single-character CJK surnames, remain protected.
     * MEMBER candidates additionally retain short non-ASCII symbols for irreversible redaction;
     * short ASCII punctuation-only labels retain the generic exclusion.
     * Raw values long enough for {@link OutboundLeakScan} remain in its dictionary independently
     * of replacement eligibility, preserving fail-closed scanning when separator folding removes
     * their entire value or reduces it below the scanner's floor.
     *
     * @param rawValue candidate identifier value
     * @return true when the value carries identity worth replacing without unanchored amplification
     */
    static boolean isDictionaryEligible(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return false;
        }
        return isCanonicalDictionaryEligible(MaskingEngine.normalizeIdentifierValue(rawValue));
    }

    static boolean isCanonicalDictionaryEligible(String canonicalValue) {
        return !canonicalValue.isEmpty()
                && (canonicalValue.length() >= OutboundLeakScan.MIN_IDENTIFIER_LENGTH
                        || canonicalValue.codePoints().anyMatch(Character::isLetterOrDigit));
    }

    /**
     * Registers server-authored prompt text this request will send verbatim.
     *
     * <p>Server text is never identifier-masked, so a tenant record named a common word the
     * envelope itself uses — a company literally named "what" against a directive containing
     * "what" — would otherwise make the outbound leak scan refuse every request that seeds it.
     * The scan consults this corpus to skip identifiers whose raw value the server provably emits
     * on its own; tenant occurrences of the same value are still tokenized by the replacer.
     *
     * @param text server-authored prompt segment about to enter the outbound payload
     */
    public void addTrustedStaticText(String text) {
        if (text != null && !text.isBlank()) {
            trustedStaticTexts.add(OutboundLeakScan.normalizeForScan(text));
            trustedStaticFields.add(text);
        }
    }

    /**
     * Whether the registered server-authored text contains this identifier under the leak scan's
     * own normalization.
     *
     * @param rawValue seeded identifier raw value
     * @return true for a non-member identifier when the server text contains the value
     */
    public boolean isTrustedTextCollision(String rawValue) {
        if (trustedStaticTexts.isEmpty()) {
            return false;
        }
        if (isMemberIdentifier(rawValue)) {
            return false;
        }
        String candidate = OutboundLeakScan.normalizeForScan(rawValue);
        String labelCandidate = CanonicalText.storedIdentifier(rawValue).label();
        for (String segment : trustedStaticTexts) {
            if (candidate.length() >= OutboundLeakScan.MIN_IDENTIFIER_LENGTH && segment.contains(candidate)
                    || labelCandidate.length() >= OutboundLeakScan.MIN_IDENTIFIER_LENGTH && segment.contains(labelCandidate)) {
                return true;
            }
        }
        return false;
    }

    /** Recognizes one complete registered server-authored field, never a label-wide exemption. */
    boolean isTrustedStaticField(String value) {
        return trustedStaticFields.contains(value);
    }

    /**
     * Whether this exact value is a seeded identifier under the leak scan's normalization.
     *
     * <p>Exists so structural preservation rules — a valid ISO temporal is normally passed through
     * unchanged — can refuse to preserve a value that is actually a tenant record's display name,
     * which must tokenize like any other identifier occurrence.
     *
     * @param value candidate string about to be preserved verbatim
     * @return true when a seeded identifier has the same normalized value
     */
    public boolean isSeededIdentifierValue(String value) {
        String candidate = OutboundLeakScan.normalizeForScan(value);
        if (candidate.length() < OutboundLeakScan.MIN_IDENTIFIER_LENGTH) {
            return false;
        }
        for (IdentifierEntry entry : rawIdentifierEntries.values()) {
            if (entry.canonicalValue().equals(candidate) || entry.labelValue().equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    /** Detects identifier reuse anywhere in a value, including short structured identifiers. */
    public boolean containsSeededIdentifierValue(String value) {
        String candidate = OutboundLeakScan.normalizeForScan(value);
        Set<String> originals = new LinkedHashSet<>(originalValueToToken.keySet());
        originals.addAll(rawIdentifierEntries.keySet());
        for (String original : originals) {
            CanonicalText.StoredIdentifier stored = CanonicalText.storedIdentifier(original);
            if (!stored.literal().isEmpty() && candidate.contains(stored.literal())
                    || !stored.label().isEmpty() && candidate.contains(stored.label())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Raw identifiers collected for request-local leak scanning.
     * @return ordered immutable identifier dictionary
     */
    public Set<String> identifierDictionary() {
        return Collections.unmodifiableSet(identifierDictionary);
    }

    /**
     * Token-to-original-value bindings for cache fingerprinting, ordered by token. Fingerprinting the
     * bindings (not merely the raw-value set) makes a cache key change when two requests share the
     * same masked text but bind the same tokens to different identifiers (an identity swap).
     * @return ordered immutable token-to-original-value entries
     */
    public List<Map.Entry<String, String>> tokenBindings() {
        return tokenToOriginalValue.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> Map.entry(entry.getKey(), entry.getValue()))
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Marks the identifier bindings issued so far, so {@link #restore} can later withdraw every
     * binding issued after this point.
     *
     * @return a checkpoint only this context accepts
     */
    public Checkpoint checkpoint() {
        return new Checkpoint(
                this,
                tokenToOriginalValue.size(),
                rawIdentifierEntries.size(),
                unsafeIdentifierValues.size(), new LinkedHashMap<>(rawIdentifierEntries),
                new LinkedHashSet<>(memberIdentifierValues), new LinkedHashSet<>(reservedTokens));
    }

    /**
     * Withdraws every identifier binding issued after a checkpoint, so the context binds exactly
     * the placeholders it bound when the checkpoint was taken.
     *
     * <p>A batched assistant step abandoned part-way leaves the replay with none of its results,
     * so the placeholders its executed calls seeded stand for values the model was never shown.
     * Left bound, a closing answer could name one — placeholders are short and sequential — and it
     * would demask cleanly into a real value instead of counting as an invented placeholder.
     * Bindings are only ever added, in order, so the ones issued after a checkpoint are exactly
     * the entries past its position in each ordered collection.
     *
     * <p>A restore is sound only while no outbound request has carried a withdrawn binding, because
     * the provider could otherwise answer with a placeholder it was legitimately shown; the
     * assistant loop restores before it assembles its next request. The per-kind placeholder
     * counters are deliberately not rewound: a withdrawn placeholder may still sit in the abandoned
     * calls' committed durable rows and can still be guessed, so it must stay unbound for as long as
     * this context lives rather than be issued again for another value.
     *
     * @param checkpoint a checkpoint this context issued
     * @throws IllegalArgumentException when the checkpoint belongs to another context, or names
     *     more bindings than this context still holds because an earlier restore withdrew them
     */
    public void restore(Checkpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        if (checkpoint.context != this
                || checkpoint.tokens > tokenToOriginalValue.size()
                || checkpoint.identifiers > rawIdentifierEntries.size()
                || checkpoint.unsafeIdentifiers > unsafeIdentifierValues.size()) {
            throw new IllegalArgumentException(
                    "Masking checkpoint does not belong to this context");
        }
        for (String token : withdrawn(tokenToOriginalValue.keySet(), checkpoint.tokens)) {
            originalValueToToken.remove(tokenToOriginalValue.remove(token));
            tokenToKind.remove(token);
            exposedTokens.remove(token);
        }
        for (String rawValue : withdrawn(rawIdentifierEntries.keySet(), checkpoint.identifiers)) {
            rawIdentifierEntries.remove(rawValue);
            identifierDictionary.remove(rawValue);
        }
        rawIdentifierEntries.clear();
        rawIdentifierEntries.putAll(checkpoint.entries);
        memberIdentifierValues.clear();
        memberIdentifierValues.addAll(checkpoint.memberValues);
        reservedTokens.clear();
        reservedTokens.addAll(checkpoint.reservedTokens);
        unsafeIdentifierValues.removeAll(
                withdrawn(unsafeIdentifierValues, checkpoint.unsafeIdentifiers));
    }

    @Override
    public String toString() {
        return "MaskingContext{redacted}";
    }

    /** Refuses literal identifier spellings even when their colliding binding predates registration. */
    String originalValueForToken(String token) {
        String original = tokenToOriginalValue.get(token);
        IdentifierEntry entry = rawIdentifierEntries.get(original);
        return reservedTokens.contains(token) || entry != null && entry.irreversibleMemberSymbol() ? null : original;
    }

    List<IdentifierEntry> identifierEntries() {
        return List.copyOf(rawIdentifierEntries.values());
    }

    private static List<String> withdrawn(Set<String> ordered, int kept) {
        return ordered.stream().skip(kept).toList();
    }

    private String nextToken(EntityKind kind) {
        String token;
        do {
            int next = tokenCounts.merge(kind, 1, Integer::sum);
            token = "{{" + kind.tokenPrefix() + next + "}}";
        } while (reservedTokens.contains(token));
        return token;
    }

    /** A position in one context's binding order, which only that context can restore to. */
    public static final class Checkpoint {
        private final MaskingContext context;
        private final int tokens;
        private final int identifiers;
        private final int unsafeIdentifiers;
        private final Map<String, IdentifierEntry> entries;
        private final Set<String> memberValues;
        private final Set<String> reservedTokens;

        private Checkpoint(
                MaskingContext context, int tokens, int identifiers, int unsafeIdentifiers,
                Map<String, IdentifierEntry> entries, Set<String> memberValues, Set<String> reservedTokens) {
            this.context = context;
            this.tokens = tokens;
            this.identifiers = identifiers;
            this.unsafeIdentifiers = unsafeIdentifiers;
            this.entries = entries;
            this.memberValues = memberValues;
            this.reservedTokens = reservedTokens;
        }
    }

    record IdentifierEntry(
            String rawValue, String canonicalValue, String labelValue, String token, EntityKind kind,
            boolean unsafe, boolean placeholderShaped, boolean irreversibleMemberSymbol) {
        boolean replacementEligible() {
            return isCanonicalDictionaryEligible(canonicalValue) || irreversibleMemberSymbol;
        }

        boolean labelEligible() {
            return isCanonicalDictionaryEligible(labelValue) || irreversibleMemberSymbol && !labelValue.isEmpty();
        }
    }
}
