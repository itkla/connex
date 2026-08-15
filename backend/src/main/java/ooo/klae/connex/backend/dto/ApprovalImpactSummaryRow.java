package ooo.klae.connex.backend.dto;

/** Bounded tenant-plane projection used to hydrate one approval-policy impact item. */
public record ApprovalImpactSummaryRow(
    int dealId,
    String dealName,
    int documentId,
    String documentTitle,
    int version,
    Integer requestedBy,
    String requestedAt
) {
}
