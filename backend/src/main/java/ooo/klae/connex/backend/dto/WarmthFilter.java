package ooo.klae.connex.backend.dto;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.warmth.RelationshipWarmthModel;

/**
 * Canonical warmth band, no-history, and decay-horizon filter for the contact and company browsers.
 *
 * <p>Band membership is evaluated in SQL against the same decayed raw weight
 * {@link ooo.klae.connex.backend.services.ScoringService} aggregates, using the boundaries published
 * by {@link RelationshipWarmthModel.SqlParameters}, so a browser facet and a smart-segment warmth
 * predicate can never be computed from two different scoring paths. Records with no touch history
 * are reported under {@link #NO_WARMTH_KEY} rather than {@code cold} so the facet buckets partition
 * the visible set and a facet count predicts exactly what the matching filter returns.
 *
 * @param bands requested warmth bands, empty when only {@code noWarmth} was requested
 * @param noWarmth whether records with no touch history are included
 * @param goesColdWithinDays decay horizon in whole days, or null when no horizon was requested
 * @param reference UTC instant the decay is evaluated against
 * @param model immutable SQL parameter set for the active warmth model
 */
public record WarmthFilter(
    Set<String> bands,
    boolean noWarmth,
    Integer goesColdWithinDays,
    LocalDateTime reference,
    RelationshipWarmthModel.SqlParameters model
) {
    /** Facet key for records with no touch history at all. */
    public static final String NO_WARMTH_KEY = "__none__";

    /** Sort key that orders a browser page by decayed relationship warmth. */
    public static final String WARMTH_SORT = "warmth";

    private static final Set<String> BANDS = Set.of("hot", "warm", "cool", "cold");
    private static final int MIN_HORIZON_DAYS = 1;
    private static final int MAX_HORIZON_DAYS = 3650;

    /**
     * Creates a validated immutable filter.
     *
     * @param bands requested warmth bands
     * @param noWarmth whether records with no touch history are included
     * @param goesColdWithinDays decay horizon in whole days
     * @param reference UTC instant the decay is evaluated against
     * @param model immutable SQL parameter set for the active warmth model
     */
    public WarmthFilter {
        bands = Set.copyOf(Objects.requireNonNull(bands, "bands"));
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(model, "model");
        if (bands.stream().anyMatch(band -> !BANDS.contains(band))) {
            throw new IllegalArgumentException("Unknown warmth band");
        }
        if (goesColdWithinDays != null
                && (goesColdWithinDays < MIN_HORIZON_DAYS || goesColdWithinDays > MAX_HORIZON_DAYS)) {
            throw new IllegalArgumentException("The warmth horizon must be between 1 and 3650 days");
        }
    }

    /**
     * Resolves the request-level warmth syntax, returning null when the request needs no warmth
     * computation at all so an unfiltered page never pays for the aggregate join.
     *
     * @param bands raw requested band keys, which may include {@link #NO_WARMTH_KEY}
     * @param noWarmth raw no-history flag
     * @param goesColdWithinDays raw decay horizon
     * @param sort raw requested sort key
     * @param now current instant
     * @return canonical filter, or null when neither a warmth filter nor a warmth sort was requested
     */
    public static WarmthFilter fromRequest(
            List<String> bands,
            boolean noWarmth,
            Integer goesColdWithinDays,
            String sort,
            Instant now) {
        Set<String> requested = new LinkedHashSet<>();
        boolean includeNoWarmth = noWarmth;
        if (bands != null) {
            for (String band : bands) {
                String normalized = band == null ? "" : band.trim().toLowerCase(Locale.ROOT);
                if (NO_WARMTH_KEY.equals(normalized)) {
                    includeNoWarmth = true;
                } else if (BANDS.contains(normalized)) {
                    requested.add(normalized);
                } else {
                    throw new BadRequestException(
                        "warmthBands must contain only: hot, warm, cool, cold, __none__");
                }
            }
        }
        if (goesColdWithinDays != null
                && (goesColdWithinDays < MIN_HORIZON_DAYS || goesColdWithinDays > MAX_HORIZON_DAYS)) {
            throw new BadRequestException("goesColdWithinDays must be between 1 and 3650");
        }
        boolean sorted = WARMTH_SORT.equalsIgnoreCase(sort == null ? "" : sort.trim());
        if (requested.isEmpty() && !includeNoWarmth && goesColdWithinDays == null && !sorted) {
            return null;
        }
        return new WarmthFilter(
            requested,
            includeNoWarmth,
            goesColdWithinDays,
            LocalDateTime.ofInstant(now, ZoneOffset.UTC),
            RelationshipWarmthModel.current().sqlParameters());
    }

    /** Whether any band or no-history bucket narrows the result set. */
    public boolean restrictsBands() {
        return !bands.isEmpty() || noWarmth;
    }

    /**
     * Creates the unrestricted filter that only supplies the model parameters and evaluation
     * instant, for callers that score every visible record rather than narrowing to a band.
     *
     * @param now current instant
     * @return filter that restricts nothing
     */
    public static WarmthFilter forScoring(Instant now) {
        return new WarmthFilter(
            Set.of(),
            false,
            null,
            LocalDateTime.ofInstant(now, ZoneOffset.UTC),
            RelationshipWarmthModel.current().sqlParameters());
    }
}
