package ooo.klae.connex.backend.dto;

/** Exact approval-work ordering key used for raw keyset iteration. */
public record ApprovalInboxCursor(
    int urgencyRank,
    String dueDateKey,
    String freshnessAt,
    int approvalId,
    int stepId
) {
}
