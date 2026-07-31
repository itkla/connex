package ooo.klae.connex.backend.dto;

/**
 * Records associated with one confirmed business-card import.
 *
 * @param contact created or reused contact
 * @param attachment retained original card image
 * @param company linked company, when selected or created
 * @param disposition whether the contact was created or reused
 */
public record BusinessCardImportResponse(
        PersonDto contact,
        AttachmentDto attachment,
        CompanyDto company,
        BusinessCardImportDisposition disposition) {
}
