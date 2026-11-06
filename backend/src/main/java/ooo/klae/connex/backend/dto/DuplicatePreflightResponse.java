package ooo.klae.connex.backend.dto;

import java.util.List;

/**
 * Ranked visible duplicate candidates for one proposed record.
 *
 * @param recordType person, company, or deal
 * @param candidates ranked candidates
 * @param truncated whether additional visible candidates were omitted
 * @param reviewToken opaque token binding this result to the proposed values and candidates
 * @param allowSeparateCreation whether incomplete weak person-name evidence permits explicitly reviewed creation
 */
public record DuplicatePreflightResponse(
        String recordType,
        List<DuplicateCandidateDto> candidates,
        boolean truncated,
        String reviewToken,
        boolean allowSeparateCreation) {

    /** Creates a response whose incomplete candidate set cannot authorize separate creation. */
    public DuplicatePreflightResponse(
            String recordType,
            List<DuplicateCandidateDto> candidates,
            boolean truncated,
            String reviewToken) {
        this(recordType, candidates, truncated, reviewToken, false);
    }

    public DuplicatePreflightResponse {
        candidates = List.copyOf(candidates);
    }
}
