package ooo.klae.connex.backend.dto;

import java.util.List;

/**
 * Visible record that matched candidate creation values.
 *
 * @param recordId visible record id
 * @param recordType person or company
 * @param name display name
 * @param companyName person's visible company name
 * @param title person's title
 * @param website company's website
 * @param industry company's industry
 * @param ownedByActiveWorkspace whether the active workspace may update the record
 * @param strength strongest evidence tier
 * @param matches exact evidence sorted deterministically
 */
public record DuplicateCandidateDto(
        int recordId,
        String recordType,
        String name,
        String companyName,
        String title,
        String website,
        String industry,
        boolean ownedByActiveWorkspace,
        DuplicateMatchStrength strength,
        List<DuplicateMatchEvidenceDto> matches) {

    public DuplicateCandidateDto {
        matches = List.copyOf(matches);
    }
}
