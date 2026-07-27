package ooo.klae.connex.backend.dto;

import java.util.List;

/**
 * Ranked visible duplicate candidates for one proposed record.
 *
 * @param recordType person or company
 * @param candidates ranked candidates
 * @param truncated whether additional visible candidates were omitted
 */
public record DuplicatePreflightResponse(
        String recordType,
        List<DuplicateCandidateDto> candidates,
        boolean truncated) {

    public DuplicatePreflightResponse {
        candidates = List.copyOf(candidates);
    }
}
