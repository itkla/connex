package ooo.klae.connex.backend.dto;

import java.util.List;

/**
 * Preview of how a proposed approval-policy edit affects pending frozen requests.
 *
 * @param impactFingerprint a digest of the proposed normalized policy, the persisted policy
 *     revision, and the exact pending approvals this preview counted. It authorizes nothing and is
 *     not a credential: the confirmed write recomputes it under the policy lock and refuses when it
 *     no longer matches, so an administrator can only ever confirm the impact they were actually
 *     shown.
 */
public record ApprovalPolicyImpactDto(
    String changeClass,
    int pendingApprovalCount,
    String effect,
    String impactFingerprint,
    List<ApprovalImpactItemDto> affected
) {
}
