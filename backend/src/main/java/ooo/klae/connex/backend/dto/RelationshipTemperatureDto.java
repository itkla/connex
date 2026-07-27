package ooo.klae.connex.backend.dto;

import java.time.Instant;
import java.util.Objects;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/**
 * A computed relationship-"temperature" score for a single contact or company.
 *
 * <p>Derived on read from the recency and frequency of logged interactions
 * (activities, notes, tasks); it is never persisted. Recent, frequent, higher-intent
 * touches (meeting &gt; call &gt; email &gt; note &gt; task) push the score up; the
 * contribution of every touch decays with a fixed half-life so a relationship
 * naturally cools as it goes quiet.
 *
 * @see ooo.klae.connex.backend.services.ScoringService
 */
@Getter
@EqualsAndHashCode
@ToString
public class RelationshipTemperatureDto {
    /** Id of the scored entity (person or company). */
    private final int id;
    /** Warmth on a 0–100 scale. */
    private final int score;
    /** Coarse band derived from {@link #score}: {@code "hot" | "warm" | "cool" | "cold"}. */
    private final String band;
    /** Direction of travel: {@code "rising" | "steady" | "cooling"}. */
    private final String trend;
    /** Most recent touch as a UTC {@code yyyy-MM-dd HH:mm:ss} string, or {@code null} if never touched. */
    private final String lastTouchAt;
    /** Whole days since the most recent touch, or {@code null} if never touched. */
    private final Integer daysSinceTouch;
    /** Number of touches inside the recent window, surfaced for context/tooltips. */
    private final int touchCount;
    /**
     * Predicted UTC date the relationship decays into the "cold" band if left untouched, or
     * {@code null} when it is already cold or has no activity to decay.
     */
    private final String goesColdAt;
    /** Whole days from now until {@link #goesColdAt}, or {@code null} when not applicable. */
    private final Integer daysUntilCold;
    /** Stable identifier of the formula that produced this score. */
    private final String modelVersion;
    /** Instant against which touch decay and cutoff eligibility were evaluated. */
    private final Instant asOf;

    /**
     * Creates a traceable score produced by a named model at a specific reference instant.
     */
    public RelationshipTemperatureDto(
            int id,
            int score,
            String band,
            String trend,
            String lastTouchAt,
            Integer daysSinceTouch,
            int touchCount,
            String goesColdAt,
            Integer daysUntilCold,
            String modelVersion,
            Instant asOf) {
        this.id = id;
        this.score = score;
        this.band = Objects.requireNonNull(band);
        this.trend = Objects.requireNonNull(trend);
        this.lastTouchAt = lastTouchAt;
        this.daysSinceTouch = daysSinceTouch;
        this.touchCount = touchCount;
        this.goesColdAt = goesColdAt;
        this.daysUntilCold = daysUntilCold;
        this.modelVersion = Objects.requireNonNull(modelVersion);
        this.asOf = Objects.requireNonNull(asOf);
    }
}
