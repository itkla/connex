package ooo.klae.connex.backend.dto;

import java.util.List;

import ooo.klae.connex.backend.beans.User;

/** Advisory management candidates and the active step's current quorum context. */
public record ApprovalStepCandidatesDto(
    List<Candidate> candidates,
    int requiredCount,
    int approvedCount,
    int remainingRequiredCount
) {
    /** Identity fields needed to select and disambiguate an eligible workspace member. */
    public record Candidate(int id, String username, String displayName, String email) {
        /** Projects an account onto the candidate identity allowlist. */
        public static Candidate from(User user) {
            return new Candidate(user.getId(), user.getUsername(), user.getDisplayName(), user.getEmail());
        }
    }
}
