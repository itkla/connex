package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Control-plane consent for a workspace or organization ownership grant. */
@Data
@NoArgsConstructor
public class OwnershipOffer {
    private long id;
    private int orgId;
    private Integer workspaceId;
    private String scopeKind;
    private int scopeId;
    private int offerorUserId;
    private int offereeUserId;
    private Long membershipId;
    private String state;
    private String createdAt;
    private String expiresAt;
    private String closedAt;
    private Integer closedByUserId;
    private String closureReason;
    private String deliveryState;
    private Integer deliveryWorkspaceId;
    private Long deliveryMembershipId;
}
