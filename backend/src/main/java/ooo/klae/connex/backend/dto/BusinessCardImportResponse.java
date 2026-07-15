package ooo.klae.connex.backend.dto;

/**
 * Records created by one confirmed business-card import.
 *
 * @param contact created contact
 * @param attachment retained original card image
 * @param company linked company, when selected or created
 */
public record BusinessCardImportResponse(
        PersonDto contact,
        AttachmentDto attachment,
        CompanyDto company) {
}
