package ooo.klae.connex.backend.util;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import ooo.klae.connex.backend.exceptions.BadRequestException;

/**
 * Shared normalization for multi-value list filter parameters, so every endpoint offering the same
 * filter validates and expands identical inputs and therefore returns identical result sets.
 * Rejects out-of-range or over-long values, de-duplicates, and expands the {@code closed} status
 * shorthand into its {@code won}/{@code lost} outcomes to match the mapper's status projection.
 *
 * <p>The deal-specific vocabularies and {@link #normalizeStatuses} back the deal list, metrics, and
 * CSV export; the generic {@link #normalizeValues} and {@link #validateOptionalValue} are reused by
 * any bounded list filter, including the generated-document index and the campaign recipient list.
 */
public final class DealFilterNormalizer {

    /** Maximum number of values accepted for any single multi-value filter. */
    public static final int MAX_FILTER_VALUES = 100;
    /** Statuses accepted from the client; {@code closed} expands to {@code won} + {@code lost}. */
    public static final Set<String> DEAL_STATUSES = Set.of("open", "closed", "won", "lost");
    /** Risk levels accepted from the client. */
    public static final Set<String> DEAL_RISKS = Set.of("high", "medium", "low", "none");

    private DealFilterNormalizer() {
    }

    /**
     * Validates and de-duplicates a positive-integer id filter, or {@code null} when unset.
     */
    public static List<Integer> normalizeIds(List<Integer> values, String parameter) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        if (values.size() > MAX_FILTER_VALUES) {
            throw new BadRequestException(parameter + " accepts at most " + MAX_FILTER_VALUES + " values");
        }
        LinkedHashSet<Integer> normalized = new LinkedHashSet<>();
        for (Integer value : values) {
            if (value == null || value < 1) {
                throw new BadRequestException(parameter + " values must be positive integers");
            }
            normalized.add(value);
        }
        return List.copyOf(normalized);
    }

    /**
     * Validates statuses and expands {@code closed} into {@code won} + {@code lost}, or {@code null}
     * when unset.
     */
    public static List<String> normalizeStatuses(List<String> values) {
        List<String> normalized = normalizeValues(values, DEAL_STATUSES, "status");
        if (normalized == null) {
            return null;
        }
        LinkedHashSet<String> expanded = new LinkedHashSet<>();
        for (String value : normalized) {
            if ("closed".equals(value)) {
                expanded.add("won");
                expanded.add("lost");
            } else {
                expanded.add(value);
            }
        }
        return List.copyOf(expanded);
    }

    /**
     * Validates each value against {@code allowed} and de-duplicates, or {@code null} when unset.
     */
    public static List<String> normalizeValues(List<String> values, Set<String> allowed, String parameter) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        if (values.size() > MAX_FILTER_VALUES) {
            throw new BadRequestException(parameter + " accepts at most " + MAX_FILTER_VALUES + " values");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new BadRequestException(parameter + " values must not be blank");
            }
            normalized.add(validateOptionalValue(value, allowed, parameter));
        }
        return List.copyOf(normalized);
    }

    /**
     * Returns {@code value} when it is blank ({@code null}) or a member of {@code allowed}, else fails.
     */
    public static String validateOptionalValue(String value, Set<String> allowed, String parameter) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (!allowed.contains(value)) {
            throw new BadRequestException(parameter + " must be one of: " + String.join(", ", allowed));
        }
        return value;
    }
}
