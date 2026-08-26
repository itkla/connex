package ooo.klae.connex.backend.dto;

/** A bounded, content-free summary of one pending approval affected by a policy edit. */
public record ApprovalImpactItemDto(
    int dealId,
    String dealName,
    int documentId,
    String documentTitle,
    int version,
    String requestedByName,
    boolean requestedByFormerMember,
    String requestedAt
) {
}
