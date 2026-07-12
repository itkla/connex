package ooo.klae.connex.backend.dto;

/** The alphabetically first visible contact for one deal in a bounded deal set. */
public record DealPrimaryContactDto(
    int dealId,
    int personId,
    String name,
    String imageUrl
) {}
