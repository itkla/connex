package ooo.klae.connex.backend.dto;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import ooo.klae.connex.backend.exceptions.BadRequestException;

/**
 * The products browser's Billing, Availability, and Effective dates facets, so a server read such
 * as the CSV export narrows the catalog exactly as the browser's client-side facets do: values
 * selected within one facet match any of them, and every facet with a selection must match.
 *
 * <p>The browser filters in the client and matches facet keys exactly, so this accepts exactly the
 * keys the browser produces and nothing else: no trimming or case folding. A hand-edited key the
 * browser would match against no product is rejected rather than read leniently, so an export can
 * never hold rows the view does not list.
 *
 * <p>Effective dates has two buckets: {@link #BOUNDED} for products with a start or an end date,
 * and {@link #NO_DATE_LIMIT} for products with neither, which is the browser's shared empty-facet
 * key.
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
    public static final String NO_DATE_LIMIT = "__empty__";

    private static final Set<String> BILLING_FREQUENCIES = Set.of("one_time", "recurring");
    private static final Set<String> AVAILABILITY = Set.of("true", "false");
    private static final Set<String> EFFECTIVE_DATES = Set.of(BOUNDED, NO_DATE_LIMIT);
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
     * Resolves the request-level facet keys, rejecting any key the browser's facets do not produce.
     *
     * @param billingFrequency billing frequency keys ({@code one_time}, {@code recurring})
     * @param active availability keys ({@code true}, {@code false})
     * @param effectiveDates effective-date keys ({@code bounded}, {@code __empty__})
     * @return canonical filter
     */
    public static ProductFacetFilter fromRequest(
            List<String> billingFrequency, List<String> active, List<String> effectiveDates) {
        Set<String> billing = canonical(billingFrequency, BILLING_FREQUENCIES,
            "billingFrequency must contain only: one_time, recurring");
        Set<String> dates = canonical(effectiveDates, EFFECTIVE_DATES,
            "effectiveDates must contain only: bounded, __empty__");
        Set<Boolean> availability = new LinkedHashSet<>();
        for (String state : canonical(active, AVAILABILITY, "active must contain only: true, false")) {
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
            if (value == null || !allowed.contains(value)) {
                throw new BadRequestException(message);
            }
            out.add(value);
        }
        return out;
    }
}
