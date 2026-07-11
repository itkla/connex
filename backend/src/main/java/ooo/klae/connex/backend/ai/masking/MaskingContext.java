package ooo.klae.connex.backend.ai.masking;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Ephemeral per-request token map for AI masking. The map is never persisted or serialized, and
 * {@link #toString()} is redacted so accidental logs do not reveal raw CRM identifiers. Connex
 * still holds the request-local map and can re-identify provider output, so this boundary reduces
 * Leg-2 provider exposure but is not anonymization and does not change Connex's handler status.
 */
public final class MaskingContext {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final Map<String, String> tokenToOriginalValue = new LinkedHashMap<>();
    private final Map<String, String> canonicalValueToToken = new LinkedHashMap<>();
    private final Map<String, String> rawIdentifierToToken = new LinkedHashMap<>();
    private final Set<String> identifierDictionary = new LinkedHashSet<>();
    private final EnumMap<EntityKind, Integer> tokenCounts = new EnumMap<>(EntityKind.class);

    /**
     * Returns a stable placeholder for an identifier within this request, assigning by first
     * appearance within each entity kind.
     * @param kind identifier namespace
     * @param rawValue original CRM display value
     * @return request-local placeholder such as {@code {{P1}}}
     */
    public String tokenFor(EntityKind kind, String rawValue) {
        Objects.requireNonNull(kind, "kind");
        String normalizedRawValue = normalizeRawValue(rawValue);
        String canonicalKey = canonicalKey(normalizedRawValue);
        String token = canonicalValueToToken.get(canonicalKey);
        if (token == null) {
            token = nextToken(kind);
            canonicalValueToToken.put(canonicalKey, token);
            tokenToOriginalValue.put(token, normalizedRawValue);
        }
        identifierDictionary.add(normalizedRawValue);
        rawIdentifierToToken.putIfAbsent(normalizedRawValue, token);
        return token;
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

    List<IdentifierEntry> identifierEntriesByLongestRawValue() {
        List<IdentifierEntry> entries = new ArrayList<>();
        for (Map.Entry<String, String> entry : rawIdentifierToToken.entrySet()) {
            entries.add(new IdentifierEntry(entry.getKey(), entry.getValue()));
        }
        entries.sort(Comparator.comparingInt((IdentifierEntry entry) -> entry.rawValue().length()).reversed());
        return entries;
    }

    private static String normalizeRawValue(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            throw new IllegalArgumentException("Cannot tokenize a blank identifier");
        }
        return WHITESPACE.matcher(rawValue.trim()).replaceAll(" ");
    }

    private static String canonicalKey(String rawValue) {
        String canonicalValue = Normalizer.normalize(rawValue, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return WHITESPACE.matcher(canonicalValue).replaceAll(" ");
    }

    private String nextToken(EntityKind kind) {
        int next = tokenCounts.merge(kind, 1, Integer::sum);
        return "{{" + kind.tokenPrefix() + next + "}}";
    }

    record IdentifierEntry(String rawValue, String token) {
    }
}
