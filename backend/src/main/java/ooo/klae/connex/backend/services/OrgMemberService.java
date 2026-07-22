package ooo.klae.connex.backend.services;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.OrgMemberDto;
import ooo.klae.connex.backend.dto.OrgMembershipDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.OrgMemberMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;

/**
 * Organization membership and org-level authorization (#316). Org members hold
 * org-wide authority — {@code admin} administers org-scoped configuration (SSO
 * today), {@code owner} additionally manages the org's members. This is distinct
 * from workspace membership: owning or administering a workspace grants no org
 * authority, which is what stops a workspace admin from reaching org SSO config.
 */
@Service
@RequiredArgsConstructor
public class OrgMemberService {

    /** Organization roles in ascending privilege order. */
    public enum OrgRole {
        ADMIN, OWNER;

        static OrgRole of(String value) {
            return value == null ? null : OrgRole.valueOf(value.trim().toUpperCase());
        }
    }

    private final OrgMemberMapper orgMemberMapper;
    private final OrganizationMapper organizationMapper;
    private final UserMapper userMapper;
    private final AuditService auditService;
    private final SessionSecurityService sessionSecurityService;

    /** Records a user as the founding owner of a freshly created organization. */
    public void addFoundingOwner(int orgId, int userId) {
        orgMemberMapper.addMember(orgId, userId, OrgRole.OWNER.name().toLowerCase());
    }

    /**
     * Refuses when the user is the only owner of any organization — deleting the account would
     * leave that org ownerless (org_member is {@code ON DELETE CASCADE}, bypassing the last-owner
     * guard on the org-member API). Each owned org's owner rows are read under a lock so concurrent
     * co-owner deletions serialize; must run in a transaction. They must transfer ownership first.
     */
    public void assertNotSoleOwnerOfAnyOrg(int userId) {
        for (int orgId : orgMemberMapper.orgIdsOwnedBy(userId)) {
            if (orgMemberMapper.lockOwnerIds(orgId).size() <= 1) {
                throw new BadRequestException("Transfer organization ownership before deleting your account");
            }
        }
    }

    /** Requires the user to hold org {@code admin} or {@code owner} in the organization. */
    public void requireOrgAdmin(int orgId, int userId) {
        if (OrgRole.of(orgMemberMapper.getRole(orgId, userId)) == null) {
            throw new ForbiddenException("Requires an organization administrator role");
        }
    }

    /** Requires and locks a current organization administrator membership inside a transaction. */
    public void requireOrgAdminForUpdate(int orgId, int userId) {
        if (OrgRole.of(orgMemberMapper.getRoleForUpdate(orgId, userId)) == null) {
            throw new ForbiddenException("Requires an organization administrator role");
        }
    }

    /** Requires the user to hold org {@code owner} in the organization. */
    public void requireOrgOwner(int orgId, int userId) {
        if (OrgRole.of(orgMemberMapper.getRole(orgId, userId)) != OrgRole.OWNER) {
            throw new ForbiddenException("Requires the organization owner role");
        }
    }

    /** The organizations the user administers, with their org role. */
    public List<OrgMembershipDto> membershipsForUser(int userId) {
        return orgMemberMapper.getMembershipsForUser(userId);
    }

    /** The user's org role in the organization, or null when they hold none. */
    public String orgRoleOf(int orgId, int userId) {
        return orgMemberMapper.getRole(orgId, userId);
    }

    /** The members of an organization. Requires the actor to be an org administrator. */
    public List<OrgMemberDto> listMembers(int orgId, int actorId) {
        requireOrgAdmin(orgId, actorId);
        return orgMemberMapper.getMembers(orgId);
    }

    /**
     * Adds an org administrator by email, or changes their role if already a member. Owner-gated;
     * the email must belong to an existing Connex account. Transactional so the delegated
     * {@link #setMember} — reached by an internal call that bypasses the transactional proxy — still
     * runs under this boundary, keeping its last-owner row lock and audit write atomic.
     */
    @Transactional
    public void setMemberByEmail(int orgId, int actorId, String emailRaw, String roleRaw) {
        requireOrgOwner(orgId, actorId);
        sessionSecurityService.requireRecentAuthentication(actorId);
        String email = emailRaw == null ? "" : emailRaw.trim().toLowerCase();
        User target = userMapper.getUserByEmail(email);
        if (target == null) {
            throw new BadRequestException("No Connex account uses that email address");
        }
        setMember(orgId, actorId, target.getId(), roleRaw);
    }

    /**
     * Adds a user to the organization or changes their org role. Requires the actor
     * to be an org owner; the target must be a real user; and the organization must
     * always keep at least one owner (a sole owner cannot be demoted to admin). The
     * organization, sorted actor/target users, and owner rows are locked in that
     * order so role changes serialize with organization mutations, audit
     * attribution, and account deletion.
     */
    @Transactional
    public void setMember(int orgId, int actorId, int targetUserId, String roleRaw) {
        requireOrgOwner(orgId, actorId);
        sessionSecurityService.requireRecentAuthentication(actorId);
        OrgRole role = parseRole(roleRaw);
        if (organizationMapper.lockById(orgId) == null) {
            throw new ForbiddenException("Requires the organization owner role");
        }
        lockMembershipUserRoots(actorId, targetUserId);
        User target = userMapper.getUserById(targetUserId);
        if (target == null) {
            throw new ResourceNotFoundException("User not found: " + targetUserId);
        }
        List<Integer> ownerIds = lockCurrentOwnerIds(orgId, actorId);
        if (role != OrgRole.OWNER && isSoleOwner(ownerIds, targetUserId)) {
            throw new BadRequestException("An organization must keep at least one owner");
        }
        orgMemberMapper.addMember(orgId, targetUserId, role.name().toLowerCase());
        auditService.record("org.member.set", "organization", orgId, target.getDisplayName(),
                "Set " + target.getDisplayName() + " to org " + role.name().toLowerCase(), null);
    }

    /**
     * Removes a user's org membership. Requires the actor to be an org owner; the
     * organization must always keep at least one owner.
     */
    @Transactional
    public void removeMember(int orgId, int actorId, int targetUserId) {
        requireOrgOwner(orgId, actorId);
        sessionSecurityService.requireRecentAuthentication(actorId);
        if (organizationMapper.lockById(orgId) == null) {
            throw new ForbiddenException("Requires the organization owner role");
        }
        lockMembershipUserRoot(actorId, actorId);
        List<Integer> ownerIds = lockCurrentOwnerIds(orgId, actorId);
        if (isSoleOwner(ownerIds, targetUserId)) {
            throw new BadRequestException("An organization must keep at least one owner");
        }
        if (orgMemberMapper.removeMember(orgId, targetUserId) == 0) {
            throw new ResourceNotFoundException("User is not an organization member");
        }
        auditService.record("org.member.remove", "organization", orgId, null,
                "Removed org member " + targetUserId, null);
    }

    /**
     * Locks the organization's owner rows and verifies the actor's current owner
     * authority. The lock makes authorization, the last-owner check, and the
     * subsequent mutation one serialized step inside the surrounding transaction.
     */
    private List<Integer> lockCurrentOwnerIds(int orgId, int actorId) {
        List<Integer> ownerIds = orgMemberMapper.lockOwnerIds(orgId);
        if (!ownerIds.contains(actorId)) {
            throw new ForbiddenException("Requires the organization owner role");
        }
        return ownerIds;
    }

    private void lockMembershipUserRoots(int actorId, int targetUserId) {
        int firstUserId = Math.min(actorId, targetUserId);
        int secondUserId = Math.max(actorId, targetUserId);
        lockMembershipUserRoot(firstUserId, actorId);
        if (secondUserId != firstUserId) {
            lockMembershipUserRoot(secondUserId, actorId);
        }
    }

    private void lockMembershipUserRoot(int userId, int actorId) {
        if (userMapper.lockById(userId) != null) {
            return;
        }
        if (userId == actorId) {
            throw new ForbiddenException("Requires the organization owner role");
        }
        throw new ResourceNotFoundException("User not found: " + userId);
    }

    private boolean isSoleOwner(List<Integer> ownerIds, int userId) {
        return ownerIds.size() <= 1 && ownerIds.contains(userId);
    }

    private OrgRole parseRole(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BadRequestException("Org role is required");
        }
        try {
            return OrgRole.of(raw);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown org role: " + raw);
        }
    }
}
