package ooo.klae.connex.backend.services;

import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.OrgMemberDto;
import ooo.klae.connex.backend.dto.OrgMembershipDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.OrgMemberMapper;
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
    private final UserMapper userMapper;
    private final AuditService auditService;

    /** Records a user as the founding owner of a freshly created organization. */
    public void addFoundingOwner(int orgId, int userId) {
        orgMemberMapper.addMember(orgId, userId, OrgRole.OWNER.name().toLowerCase());
    }

    /** Requires the user to hold org {@code admin} or {@code owner} in the organization. */
    public void requireOrgAdmin(int orgId, int userId) {
        if (OrgRole.of(orgMemberMapper.getRole(orgId, userId)) == null) {
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

    /** The members of an organization. Requires the actor to be an org administrator. */
    public List<OrgMemberDto> listMembers(int orgId, int actorId) {
        requireOrgAdmin(orgId, actorId);
        return orgMemberMapper.getMembers(orgId);
    }

    /**
     * Adds a user to the organization or changes their org role. Requires the actor
     * to be an org owner; the target must be a real user; and the organization must
     * always keep at least one owner (a sole owner cannot be demoted to admin).
     */
    public void setMember(int orgId, int actorId, int targetUserId, String roleRaw) {
        requireOrgOwner(orgId, actorId);
        OrgRole role = parseRole(roleRaw);
        User target = userMapper.getUserById(targetUserId);
        if (target == null) {
            throw new ResourceNotFoundException("User not found: " + targetUserId);
        }
        if (role != OrgRole.OWNER && isSoleOwner(orgId, targetUserId)) {
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
    public void removeMember(int orgId, int actorId, int targetUserId) {
        requireOrgOwner(orgId, actorId);
        if (isSoleOwner(orgId, targetUserId)) {
            throw new BadRequestException("An organization must keep at least one owner");
        }
        if (orgMemberMapper.removeMember(orgId, targetUserId) == 0) {
            throw new ResourceNotFoundException("User is not an organization member");
        }
        auditService.record("org.member.remove", "organization", orgId, null,
                "Removed org member " + targetUserId, null);
    }

    private boolean isSoleOwner(int orgId, int userId) {
        return OrgRole.of(orgMemberMapper.getRole(orgId, userId)) == OrgRole.OWNER
                && orgMemberMapper.countOwners(orgId) <= 1;
    }

    private OrgRole parseRole(String raw) {
        try {
            OrgRole role = OrgRole.of(raw);
            if (role == null) {
                throw new BadRequestException("Org role is required");
            }
            return role;
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown org role: " + raw);
        }
    }
}
