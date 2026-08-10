package ooo.klae.connex.backend.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Bounded canonical Relationship Radar response. */
public record RadarResponseDto(
        List<Signal> items,
        List<FamilyStatus> families,
        Map<String, Integer> counts,
        Instant asOf,
        boolean partialFailure) {

    /** One currently visible ranked signal. */
    public record Signal(
            long id,
            String family,
            Subject subject,
            String priority,
            String state,
            Instant snoozeUntil,
            Integer taskId,
            String version,
            Instant evidenceAsOf,
            boolean stale,
            List<Evidence> evidence,
            Rank rank) {
    }

    /** Current visible source subject. */
    public record Subject(String type, int id, String label) {
    }

    /** Structured deterministic evidence. */
    public record Evidence(
            String type,
            Map<String, Object> parameters,
            List<Reference> references) {
    }

    /** One source record reference, resolved by type and id. */
    public record Reference(String type, int id) {
    }

    /** Human-explainable deterministic ranking inputs. */
    public record Rank(int position, String rule, List<RankFactor> factors) {
    }

    /** One ordered ranking input. */
    public record RankFactor(String key, String direction, Object value) {
    }

    /** Detector availability without collapsing failures into an empty result. */
    public record FamilyStatus(
            String family,
            String status,
            Instant lastAttemptAt,
            Instant lastSuccessAt,
            Instant evidenceAsOf,
            String errorCode) {
    }
}
