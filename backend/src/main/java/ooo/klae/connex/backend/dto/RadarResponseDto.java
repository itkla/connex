package ooo.klae.connex.backend.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

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

    /**
     * One source record reference, resolved by type and id.
     *
     * <p>{@code label} is resolved at read time from the referenced record, exactly like
     * {@link Subject#label()}: it is never persisted into stored evidence, so a renamed or deleted
     * record can never be described by a stale name. Evidence deserialized from storage and evidence
     * built by a detector both use the two-argument form, and the omitted label keeps the persisted
     * JSON byte-identical to what earlier binaries wrote.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Reference(String type, int id, String label) {

        /**
         * Creates an unresolved reference, the only form a detector may persist.
         *
         * @param type referenced record type
         * @param id referenced record id
         */
        public Reference(String type, int id) {
            this(type, id, null);
        }

        /**
         * Returns a copy carrying the referenced record's current label.
         *
         * @param resolved current label of the referenced record
         * @return labelled reference
         */
        public Reference withLabel(String resolved) {
            return new Reference(type, id, resolved);
        }
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
