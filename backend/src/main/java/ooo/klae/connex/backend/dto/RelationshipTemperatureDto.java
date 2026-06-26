package ooo.klae.connex.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

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
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RelationshipTemperatureDto {
    /** Id of the scored entity (person or company). */
    private int id;
    /** Warmth on a 0–100 scale. */
    private int score;
    /** Coarse band derived from {@link #score}: {@code "hot" | "warm" | "cool" | "cold"}. */
    private String band;
    /** Direction of travel: {@code "rising" | "steady" | "cooling"}. */
    private String trend;
    /** Most recent touch as a UTC {@code yyyy-MM-dd HH:mm:ss} string, or {@code null} if never touched. */
    private String lastTouchAt;
    /** Whole days since the most recent touch, or {@code null} if never touched. */
    private Integer daysSinceTouch;
    /** Number of touches inside the recent window, surfaced for context/tooltips. */
    private int touchCount;
}
