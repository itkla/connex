package ooo.klae.connex.backend.dto;

import java.util.List;

import ooo.klae.connex.backend.beans.User;

/**
 * Advisory management candidates and one authoritative step snapshot. Counts, decision authors and
 * effective approver ids are projected from the same hydrated step for client quorum checks.
 */
public record ApprovalStepCandidatesDto(
    List<Candidate> candidates,
    int requiredCount,
    int approvedCount,
    int remainingRequiredCount,
    List<Integer> decidedByUserIds,
    List<Integer> effectiveApproverIds
) {
    /** Identity fields needed to select and disambiguate an eligible workspace member. */
    public record Candidate(int id, String username, String displayName, String email) {
        /** Projects an account onto the candidate identity allowlist. */
        public static Candidate from(User user) {
            return new Candidate(user.getId(), user.getUsername(), user.getDisplayName(), user.getEmail());
        }
    }
}
