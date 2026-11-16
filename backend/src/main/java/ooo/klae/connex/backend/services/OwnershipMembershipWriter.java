package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.OwnershipOffer;
import ooo.klae.connex.backend.mappers.OrgMemberMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

/** The promotion writer reached only after consent has been conditionally claimed under roots. */
@Component
@RequiredArgsConstructor
public class OwnershipMembershipWriter {
    private final WorkspaceMapper workspaceMapper;
    private final OrgMemberMapper orgMemberMapper;

    /** Grants accepted ownership without transferring credential coverage or founder attribution. */
    public void grantAccepted(OwnershipOffer offer) {
        if (!"ACCEPTED".equals(offer.getState())) {
            throw new IllegalStateException("Ownership requires accepted consent");
        }
        if (offer.getWorkspaceId() == null) {
            orgMemberMapper.addMemberClearingFounder(offer.getOrgId(), offer.getOffereeUserId(), "owner");
        } else {
            workspaceMapper.updateMemberRole(offer.getWorkspaceId(), offer.getOffereeUserId(), "owner");
        }
    }
}
