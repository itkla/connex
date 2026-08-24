package ooo.klae.connex.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import ooo.klae.connex.backend.beans.AiWatch;

/**
 * One watch as its owner inspects it.
 *
 * <p>Every field of the trigger is present so the client can restate the exact condition rather than
 * a summary of it: which record, which threshold, how often it may fire, when it was last evaluated,
 * and what it last fired on. Inclusion is pinned to ALWAYS because "never fired" and "fired at an
 * unknown time" are different facts and the application-wide {@code non_null} inclusion would erase
 * the distinction.
 *
 * @param id durable watch identifier
 * @param watchType durable watch type key
 * @param subjectKind record kind the watch is about
 * @param subjectId record the watch is about
 * @param subjectLabel record name at read time, or null when it is no longer readable
 * @param thresholdBand declared warmth band, or null when the type declares none
 * @param thresholdDays declared day count, or null when the type declares none
 * @param thresholdLevel declared risk level, or null when the type declares none
 * @param status {@code active} or {@code paused}
 * @param cooldownDays minimum days between two firings of the same condition
 * @param expiresOn ISO-8601 local date the watch stops evaluating, or null when it does not expire
 * @param lastEvaluatedAt when the condition was last checked, or null
 * @param lastFiredAt when the watch last fired, or null
 * @param lastFiredState the deterministic state token that last fired, or null
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AiWatchDto(
        int id,
        String watchType,
        String subjectKind,
        int subjectId,
        String subjectLabel,
        String thresholdBand,
        Integer thresholdDays,
        String thresholdLevel,
        String status,
        int cooldownDays,
        String expiresOn,
        String lastEvaluatedAt,
        String lastFiredAt,
        String lastFiredState) {

    /** Projects one durable watch with the subject label the reader is authorized to see. */
    public static AiWatchDto from(AiWatch watch, String subjectLabel) {
        return new AiWatchDto(
                watch.getId(),
                watch.getWatchType(),
                watch.getSubjectKind(),
                watch.getSubjectId(),
                subjectLabel,
                watch.getThresholdBand(),
                watch.getThresholdDays(),
                watch.getThresholdLevel(),
                watch.getStatus(),
                watch.getCooldownDays(),
                watch.getExpiresOn(),
                watch.getLastEvaluatedAt(),
                watch.getLastFiredAt(),
                watch.getLastFiredState());
    }
}
