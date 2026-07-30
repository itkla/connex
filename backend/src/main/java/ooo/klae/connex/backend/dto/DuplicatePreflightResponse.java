package ooo.klae.connex.backend.dto;

import java.util.List;

/**
 * Ranked visible duplicate candidates for one proposed record.
 *
 * @param recordType person or company
 * @param candidates ranked candidates
 * @param truncated whether additional visible candidates were omitted
 * @param reviewToken opaque token binding this result to the proposed values and candidates
 */
public record DuplicatePreflightResponse(
        String recordType,
        List<DuplicateCandidateDto> candidates,
        boolean truncated,
        String reviewToken) {

    public DuplicatePreflightResponse {
        candidates = List.copyOf(candidates);
    }
}
