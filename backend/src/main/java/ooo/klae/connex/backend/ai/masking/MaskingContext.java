package ooo.klae.connex.backend.ai.masking;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    private final Map<String, String> tokenToOriginalValue = new LinkedHashMap<>();
    private final Map<String, EntityKind> tokenToKind = new LinkedHashMap<>();
    private final Map<String, String> originalValueToToken = new LinkedHashMap<>();
    private final Map<String, IdentifierEntry> rawIdentifierEntries = new LinkedHashMap<>();
    private final Set<String> unsafeIdentifierValues = new LinkedHashSet<>();
    private final Set<String> identifierDictionary = new LinkedHashSet<>();
    private final Set<String> trustedStaticTexts = new LinkedHashSet<>();
    private final EnumMap<EntityKind, Integer> tokenCounts = new EnumMap<>(EntityKind.class);
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
        String token = originalValueToToken.get(rawValue);
        if (token == null) {
            token = nextToken(kind);
            originalValueToToken.put(rawValue, token);
            tokenToOriginalValue.put(token, rawValue);
            tokenToKind.put(token, kind);
        }
        if (!rawIdentifierEntries.containsKey(rawValue)) {
            CanonicalText.StoredIdentifier stored = CanonicalText.storedIdentifier(rawValue);
            String canonicalValue = stored.literal();
            if (isCanonicalDictionaryEligible(canonicalValue)
                    || rawValue.length() >= OutboundLeakScan.MIN_IDENTIFIER_LENGTH) {
                identifierDictionary.add(rawValue);
                rawIdentifierEntries.put(rawValue,
                        new IdentifierEntry(rawValue, canonicalValue,
                                stored.label(), token,
                                Objects.requireNonNull(tokenToKind.get(token)), !stored.converged()));
                if (!stored.converged()) {
                    unsafeIdentifierValues.add(canonicalValue);
                    log.warn("AI stored identifier omitted: non-converging label projection; kind={}", kind);
                }
            }
        }
        IdentifierEntry entry = rawIdentifierEntries.get(rawValue);
        return entry != null && entry.unsafe() ? MaskingEngine.REDACTED : token;
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
        }
    }

    /**
     * Whether the registered server-authored text contains this identifier under the leak scan's
     * own normalization.
     *
     * @param rawValue seeded identifier raw value
     * @return true when the server's own prompt text contains the value
     */
    public boolean isTrustedTextCollision(String rawValue) {
        if (trustedStaticTexts.isEmpty()) {
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

    @Override
    public String toString() {
        return "MaskingContext{redacted}";
    }

    String originalValueForToken(String token) {
        return tokenToOriginalValue.get(token);
    }

    List<IdentifierEntry> identifierEntries() {
        return List.copyOf(rawIdentifierEntries.values());
    }

    private String nextToken(EntityKind kind) {
        int next = tokenCounts.merge(kind, 1, Integer::sum);
        return "{{" + kind.tokenPrefix() + next + "}}";
    }

    record IdentifierEntry(
            String rawValue, String canonicalValue, String labelValue, String token, EntityKind kind, boolean unsafe) {
        boolean replacementEligible() {
            return isCanonicalDictionaryEligible(canonicalValue);
        }

        boolean labelEligible() {
            return isCanonicalDictionaryEligible(labelValue);
        }
    }
}
