package ooo.klae.connex.backend.dto;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Bounded, authorization-safe explanation of one computed relationship score.
 *
 * @param subjectType scored record type
 * @param subjectId scored person or company id
 * @param temperature score produced from the same evidence snapshot
 * @param asOf instant against which decay and source eligibility were evaluated
 * @param attributionRule rule used to attach sources to the subject
 * @param contributors highest decayed contributors, bounded by the server
 * @param totals totals across every eligible source, including omitted contributors
 * @param coverage confidence and exclusion disclosure
 */
public record RelationshipEvidenceDto(
    SubjectType subjectType,
    int subjectId,
    RelationshipTemperatureDto temperature,
    Instant asOf,
    AttributionRule attributionRule,
    List<Contributor> contributors,
    Totals totals,
    Coverage coverage
) {
    /** Creates an immutable evidence response. */
    public RelationshipEvidenceDto {
        contributors = List.copyOf(contributors);
    }

    /** Record types supported by the evidence endpoint. */
    public enum SubjectType {
        PERSON,
        COMPANY;

        /** Returns the lowercase API value. */
        @JsonValue
        public String value() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Source-attribution rules used by live scoring and historical map replay.
     *
     * <p>Live company evidence uses current contact-company and deal-company links. Replay uses the
     * contact's employer at touch time while retaining the deal's current company link.
     */
    public enum AttributionRule {
        DIRECT_PERSON_TOUCHES,
        PRESENT_DAY_PERSON_COMPANY_OR_DEAL_COMPANY,
        TOUCH_TIME_EMPLOYER_OR_PRESENT_DAY_DEAL_COMPANY;

        /** Returns the lowercase API value. */
        @JsonValue
        public String value() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Eligible record types that can contribute to warmth. */
    public enum SourceType {
        ACTIVITY,
        NOTE,
        TASK;

        /** Returns the lowercase API value. */
        @JsonValue
        public String value() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Scope of the private-note exclusion count. */
    public enum PrivateNoteCountScope {
        CURRENT_CALLER_ONLY;

        /** Returns the lowercase API value. */
        @JsonValue
        public String value() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * One source record's non-content metadata and contribution.
     *
     * @param sourceType source record type
     * @param sourceId workspace-scoped source id
     * @param interactionType normalized activity type, {@code workspace-note}, or {@code task}
     * @param occurredAt source timestamp
     * @param baseWeight model weight before decay
     * @param decayedContribution contribution at {@code asOf}
     */
    public record Contributor(
        SourceType sourceType,
        int sourceId,
        String interactionType,
        Instant occurredAt,
        double baseWeight,
        double decayedContribution
    ) {}

    /**
     * Totals across all eligible sources, including records omitted from the bounded list.
     *
     * @param contributorCount total eligible sources
     * @param returnedCount sources returned in {@code contributors}
     * @param omittedCount eligible sources omitted by the bound
     * @param totalDecayedContribution raw model weight across all eligible sources
     * @param returnedDecayedContribution raw model weight represented by returned sources
     * @param omittedDecayedContribution raw model weight represented by omitted sources
     * @param sourceCounts eligible source counts by record type
     */
    public record Totals(
        int contributorCount,
        int returnedCount,
        int omittedCount,
        double totalDecayedContribution,
        double returnedDecayedContribution,
        double omittedDecayedContribution,
        SourceCounts sourceCounts
    ) {}

    /**
     * Eligible source counts by record type.
     *
     * @param activities activity count
     * @param notes workspace-visible note count
     * @param tasks task count
     */
    public record SourceCounts(int activities, int notes, int tasks) {}

    /**
     * Confidence and exclusion disclosure without revealing another user's private-note activity.
     *
     * <p>{@code callerPrivateNotesExcluded} counts only private notes authored by the current
     * caller, whose existence is already visible to that caller. It never counts or signals private
     * notes authored by anyone else.
     *
     * @param limitedEvidence whether the total source count is below the confidence threshold
     * @param minimumContributorsForConfidence server threshold for a non-limited judgement
     * @param callerPrivateNotesExcluded current caller's private notes excluded from scoring
     * @param privateNoteCountScope scope governing the exclusion count
     */
    public record Coverage(
        boolean limitedEvidence,
        int minimumContributorsForConfidence,
        int callerPrivateNotesExcluded,
        PrivateNoteCountScope privateNoteCountScope
    ) {}
}
