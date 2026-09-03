package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One duplicate-family review item backed by exact canonical identity evidence.
 *
 * @param itemType {@code pair} or {@code oversized_group}
 * @param recordType {@code person} or {@code company}
 * @param confidence exact-match confidence
 * @param evidence display-safe identity kind; canonical values are never returned
 * @param members the two records for a pair; empty for an oversized group
 * @param groupSize number of records sharing the evidence
 * @param membersTruncated whether members are deferred to the collision-member endpoint
 * @param detectedAt first detection time for this exact evidence item
 * @param state {@code open} or {@code dismissed}
 * @param evidenceFingerprint opaque fingerprint a mutation must echo
 * @param dismissedAt latest dismissal time
 * @param dismissedByUserId latest dismissing actor identifier
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DuplicateReviewItemDto(
        String itemType,
        String recordType,
        DuplicateMatchStrength confidence,
        DuplicateReviewEvidenceDto evidence,
        List<DuplicateReviewMemberDto> members,
        int groupSize,
        boolean membersTruncated,
        LocalDateTime detectedAt,
        String state,
        String evidenceFingerprint,
        LocalDateTime dismissedAt,
        Integer dismissedByUserId) {

    public DuplicateReviewItemDto {
        members = List.copyOf(Objects.requireNonNull(members, "members"));
    }
}
