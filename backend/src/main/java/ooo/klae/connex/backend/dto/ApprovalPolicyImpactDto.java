package ooo.klae.connex.backend.dto;

import java.util.List;

/** Preview of how a proposed approval-policy edit affects pending frozen requests. */
public record ApprovalPolicyImpactDto(
    String changeClass,
    int pendingApprovalCount,
    String effect,
    List<ApprovalImpactItemDto> affected
) {
}
