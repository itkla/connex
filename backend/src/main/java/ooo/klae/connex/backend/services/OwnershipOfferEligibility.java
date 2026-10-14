package ooo.klae.connex.backend.services;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.OwnershipOffer;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceMember;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mappers.OrgMemberMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.Permission;

/** Live control-plane eligibility for cards, locked acceptance rechecks and invalidation hooks. */
@Component
@RequiredArgsConstructor
public class OwnershipOfferEligibility {
    private final WorkspaceMapper workspaceMapper;
    private final OrganizationMapper organizationMapper;
    private final OrgMemberMapper orgMemberMapper;
    private final RoleMapper roleMapper;
    private final UserMapper userMapper;
    private final RegistrationVerificationService registrationVerificationService;

    /** Evaluates current scope, parties, authority and the original membership generation. */
    public boolean isValid(OwnershipOffer offer) {
        if (organizationMapper.getActiveById(offer.getOrgId()) == null
                || userMapper.isAccountDeletionReserved(offer.getOfferorUserId())
                || userMapper.isAccountDeletionReserved(offer.getOffereeUserId())) {
            return false;
        }
        if (offer.getWorkspaceId() == null) {
            if (!"owner".equals(orgMemberMapper.getRole(offer.getOrgId(), offer.getOfferorUserId()))) {
                return false;
            }
            if (orgMemberMapper.getRole(offer.getOrgId(), offer.getOffereeUserId()) != null) {
                return true;
            }
            User target = userMapper.getUserById(offer.getOffereeUserId());
            return target != null && orgMemberMapper.isActiveWorkspaceTenant(offer.getOrgId(), target.getId())
                && !registrationVerificationService.requiresMailboxProof(target);
        }
        Workspace workspace = workspaceMapper.getActiveById(offer.getWorkspaceId());
        WorkspaceMember target = workspaceMapper.getAuthorizationMembership(offer.getWorkspaceId(), offer.getOffereeUserId());
        return workspace != null && workspace.getOrgId() == offer.getOrgId()
            && target != null && "active".equals(target.getStatus())
            && offer.getMembershipId().equals(workspaceMapper.getMembershipGenerationId(
                offer.getWorkspaceId(), offer.getOffereeUserId()))
            && canOfferWorkspaceOwnership(offer.getWorkspaceId(), offer.getOfferorUserId());
    }

    /** Checks raw ownership and the full grant ceiling without relying on a current session. */
    public boolean canOfferWorkspaceOwnership(int workspaceId, int userId) {
        WorkspaceMember actor = workspaceMapper.getAuthorizationMembership(workspaceId, userId);
        if (actor == null || !"active".equals(actor.getStatus()) || !"owner".equals(actor.getRole())) {
            return false;
        }
        if (actor.getRoleId() == null) {
            return true;
        }
        Set<String> permissions = Set.copyOf(roleMapper.findPermissions(workspaceId, actor.getRoleId()));
        return permissions.containsAll(Arrays.stream(Permission.values()).filter(Permission::isGrantable)
            .map(Enum::name).collect(Collectors.toSet()));
    }
}
