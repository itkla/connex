package ooo.klae.connex.backend.dto;

import java.util.List;

/**
 * One held exact-identity review row.
 */
public record ProviderCaptureReviewDto(
    long id,
    long version,
    long interactionId,
    long interactionVersion,
    String provider,
    String stream,
    String interactionType,
    String subject,
    String occurredAt,
    String participantRole,
    String displayName,
    String email,
    String matchState,
    String heldReason,
    List<Candidate> candidates,
    List<String> allowedActions
) {
    /** Defensively copies review options. */
    public ProviderCaptureReviewDto {
        candidates = List.copyOf(candidates);
        allowedActions = List.copyOf(allowedActions);
    }

    /** Exact current identity candidate. */
    public record Candidate(int personId, String name) {
    }
}
