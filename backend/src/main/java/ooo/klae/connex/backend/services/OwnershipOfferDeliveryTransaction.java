package ooo.klae.connex.backend.services;

import java.util.Objects;
import java.util.TreeSet;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.OwnershipOffer;
import ooo.klae.connex.backend.beans.WorkspaceMember;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.OwnershipOfferMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.notifications.NotificationDelivery;

/** Routed notice persistence retains every account, membership, scope and offer lock through commit. */
@Component
@RequiredArgsConstructor
public class OwnershipOfferDeliveryTransaction {
    private final OwnershipOfferMapper offers;
    private final UserMapper userMapper;
    private final WorkspaceMapper workspaceMapper;
    private final OrganizationMapper organizationMapper;
    private final OwnershipOfferWriter writer;
    private final NotificationDelivery delivery;

    /** A delayed worker cannot write after a closure or a party's deletion reservation wins. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deliver(OwnershipOffer discovered) {
        OwnershipOfferWriter.requireReadCommitted();
        Integer workspaceId = discovered.getDeliveryWorkspaceId();
        if (workspaceId == null) {
            return;
        }
        for (int userId : new TreeSet<>(List.of(discovered.getOfferorUserId(), discovered.getOffereeUserId()))) {
            if (userMapper.lockByIdForShare(userId) == null) {
                return;
            }
            writer.requireUnreserved(userId);
        }
        Integer orgId = workspaceMapper.lockWorkspaceOrgIdForShare(workspaceId);
        if (!Objects.equals(orgId, discovered.getOrgId())) {
            return;
        }
        WorkspaceMember member = workspaceMapper.lockAuthorizationMembership(workspaceId, discovered.getOffereeUserId());
        Long generation = workspaceMapper.getMembershipGenerationId(workspaceId, discovered.getOffereeUserId());
        if (member == null || !"active".equals(member.getStatus())
                || !Objects.equals(generation, discovered.getDeliveryMembershipId())
                || workspaceMapper.getActiveById(workspaceId) == null
                || organizationMapper.lockActiveByIdForShare(discovered.getOrgId()) == null) {
            return;
        }
        OwnershipOffer locked = offers.lockByIdForShare(discovered.getId());
        if (locked == null || !"OPEN".equals(locked.getState()) || offers.isExpired(locked.getId())
                || !"PENDING".equals(locked.getDeliveryState())
                || !Objects.equals(locked.getDeliveryWorkspaceId(), workspaceId)
                || !Objects.equals(locked.getDeliveryMembershipId(), generation)
                || (locked.getWorkspaceId() != null && !Objects.equals(locked.getMembershipId(), generation))) {
            return;
        }
        if (offers.markDelivered(locked.getId(), workspaceId, generation) != 1) {
            return;
        }
        Notification notice = new Notification();
        notice.setWorkspaceId(workspaceId);
        notice.setRecipientId(locked.getOffereeUserId());
        notice.setActorId(locked.getOfferorUserId());
        notice.setType(OwnershipOfferWriter.NOTICE_TYPE);
        notice.setCategory("system");
        notice.setSeverity("info");
        notice.setTemplateVersion(1);
        notice.setTitle("Ownership offered");
        notice.setBody("Review and accept or decline your ownership offer.");
        notice.setActionUrl("/settings/personal/workspaces");
        notice.setData("{\"offerId\":\"" + locked.getId() + "\",\"scopeKind\":\"" + locked.getScopeKind() + "\"}");
        notice.setDedupeKey("ownership.offer:" + locked.getId());
        notice.setTriggeredAt(locked.getCreatedAt());
        delivery.deliver(notice);
    }
}
