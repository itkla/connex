package ooo.klae.connex.backend.services;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.OwnershipOffer;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.OwnershipOfferMapper;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;

/** Erasure retains its existing isolation and locks only the deleting account, never counterparts. */
@Component
@RequiredArgsConstructor
public class OwnershipOfferAccountCleanupTransaction {
    private final UserMapper userMapper;
    private final OwnershipOfferMapper mapper;
    private final NotificationMapper notificationMapper;
    private final WorkspaceService workspaceService;
    private final OrgMemberService orgMemberService;
    private final OwnershipOfferWriter writer;
    private final OwnershipOfferNotificationStateVersionService stateVersionService;

    /** Removes party references and nullable attribution after guarded tenant erasure succeeds. */
    @Transactional
    public void erase(int userId, List<OwnershipOffer> discovered) {
        if (userMapper.lockById(userId) == null) {
            throw new ResourceNotFoundException("User not found");
        }
        TreeSet<Integer> workspaceIds = new TreeSet<>();
        TreeSet<Integer> orgIds = new TreeSet<>();
        for (OwnershipOffer offer : discovered) {
            if (offer.getWorkspaceId() != null) {
                workspaceIds.add(offer.getWorkspaceId());
            }
            if (offer.getDeliveryWorkspaceId() != null) {
                workspaceIds.add(offer.getDeliveryWorkspaceId());
            }
            orgIds.add(offer.getOrgId());
        }
        List<Integer> owned = workspaceService.discoverOwnedWorkspaceIds(userId);
        workspaceService.lockAccountWorkspaceRoots(owned, new ArrayList<>(workspaceIds));
        workspaceService.assertNotSoleOwnerOfWorkspaces(userId, owned);
        orgMemberService.assertNotSoleOwnerOfAnyOrg(userId, new ArrayList<>(orgIds));
        List<OwnershipOffer> locked = new ArrayList<>();
        for (OwnershipOffer candidate : discovered.stream().sorted(Comparator.comparingLong(OwnershipOffer::getId)).toList()) {
            OwnershipOffer offer = mapper.lockById(candidate.getId());
            if (offer != null) {
                locked.add(offer);
            }
        }
        List<OwnershipOffer> invalidated = new ArrayList<>();
        for (OwnershipOffer offer : locked) {
            mapper.clearClosureActor(offer.getId(), userId);
            if (offer.getOfferorUserId() == userId || offer.getOffereeUserId() == userId) {
                if ("OPEN".equals(offer.getState())
                        && mapper.close(offer.getId(), "INVALIDATED", null, "account_erasure") == 1) {
                    writer.resolveNoticeLocked(offer);
                    invalidated.add(offer);
                }
                if (offer.getDeliveryWorkspaceId() != null) {
                    writer.withNoticeScope(offer, () -> {
                        if (notificationMapper.deleteOwnershipOffer(offer.getDeliveryWorkspaceId(),
                                offer.getOffereeUserId(), Long.toString(offer.getId())) > 0) {
                            stateVersionService.markChanged(offer.getOffereeUserId());
                        }
                    });
                }
                mapper.deletePartyReference(offer.getId(), userId);
            }
        }
        invalidated.sort(Comparator.comparingInt((OwnershipOffer offer) -> offer.getWorkspaceId() == null ? 1 : 0)
            .thenComparingInt(OwnershipOffer::getScopeId).thenComparingLong(OwnershipOffer::getId));
        for (OwnershipOffer offer : invalidated) {
            writer.audit(offer, "invalidated");
        }
    }
}
