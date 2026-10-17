package ooo.klae.connex.backend.dto;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import ooo.klae.connex.backend.exceptions.BadRequestException;

/**
 * The products browser's Billing, Availability, and Effective dates facets, so a server read such
 * as the CSV export narrows the catalog exactly as the browser's client-side facets do: values
 * selected within one facet match any of them, and every facet with a selection must match.
 *
 * <p>Effective dates has two buckets: {@link #BOUNDED} for products with a start or an end date,
 * and {@link #UNBOUNDED} for products with neither, which the browser labels "No date limit".
 *
 * @param billingFrequencies selected billing frequencies, empty when the facet is unrestricted
 * @param availability selected availability states, empty when the facet is unrestricted
 * @param effectiveDates selected effective-date buckets, empty when the facet is unrestricted
 */
public record ProductFacetFilter(
    Set<String> billingFrequencies,
    Set<Boolean> availability,
    Set<String> effectiveDates
) {
    /** Effective-dates bucket for products with a start date, an end date, or both. */
    public static final String BOUNDED = "bounded";

    /** Effective-dates bucket for products with neither a start nor an end date. */
    public static final String UNBOUNDED = "none";

    private static final Set<String> BILLING_FREQUENCIES = Set.of("one_time", "recurring");
    private static final Set<String> EFFECTIVE_DATES = Set.of(BOUNDED, UNBOUNDED);
    private static final ProductFacetFilter NONE = new ProductFacetFilter(Set.of(), Set.of(), Set.of());

    /**
     * Creates a validated immutable filter.
     *
     * @param billingFrequencies selected billing frequencies
     * @param availability selected availability states
     * @param effectiveDates selected effective-date buckets
     */
    public ProductFacetFilter {
        billingFrequencies = Set.copyOf(Objects.requireNonNull(billingFrequencies, "billingFrequencies"));
        availability = Set.copyOf(Objects.requireNonNull(availability, "availability"));
        effectiveDates = Set.copyOf(Objects.requireNonNull(effectiveDates, "effectiveDates"));
        if (!BILLING_FREQUENCIES.containsAll(billingFrequencies)) {
            throw new IllegalArgumentException("Unknown billing frequency");
        }
        if (!EFFECTIVE_DATES.containsAll(effectiveDates)) {
            throw new IllegalArgumentException("Unknown effective-dates bucket");
        }
    }

    /** Returns the filter that narrows nothing, for the catalog list and an unfaceted export. */
    public static ProductFacetFilter none() {
        return NONE;
    }

    /**
     * Resolves the request-level facet syntax, rejecting any value the browser could not have sent.
     *
     * @param billingFrequency raw billing frequencies ({@code one_time}, {@code recurring})
     * @param active raw availability states ({@code true}, {@code false})
     * @param effectiveDates raw effective-date buckets ({@code bounded}, {@code none})
     * @return canonical filter
     */
    public static ProductFacetFilter fromRequest(
            List<String> billingFrequency, List<String> active, List<String> effectiveDates) {
        Set<String> billing = canonical(billingFrequency, BILLING_FREQUENCIES,
            "billingFrequency must contain only: one_time, recurring");
        Set<String> dates = canonical(effectiveDates, EFFECTIVE_DATES,
            "effectiveDates must contain only: bounded, none");
        Set<Boolean> availability = new LinkedHashSet<>();
        for (String state : canonical(active, Set.of("true", "false"), "active must contain only: true, false")) {
            availability.add(Boolean.parseBoolean(state));
        }
        return new ProductFacetFilter(billing, availability, dates);
    }

    private static Set<String> canonical(List<String> values, Set<String> allowed, String message) {
        Set<String> out = new LinkedHashSet<>();
        if (values == null) {
            return out;
        }
        for (String value : values) {
            String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            if (!allowed.contains(normalized)) {
                throw new BadRequestException(message);
            }
            out.add(normalized);
        }
        return out;
    }
}
