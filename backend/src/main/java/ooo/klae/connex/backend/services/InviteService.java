package ooo.klae.connex.backend.services;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.WorkspaceInvite;
import ooo.klae.connex.backend.beans.WorkspaceMember;
import ooo.klae.connex.backend.dto.InviteDto;
import ooo.klae.connex.backend.dto.InvitePreviewDto;
import ooo.klae.connex.backend.dto.InviteResultDto;
import ooo.klae.connex.backend.dto.MemberDto;
import ooo.klae.connex.backend.dto.WorkspaceMembershipDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.InviteMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.notifications.NotificationStateVersionService;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.util.OneTimeTokenDigest;

/**
 * The two workspace onboarding flows: email-token invites (create / list /
 * revoke / preview / accept) and admin-adds-an-existing-user. Mutations that
 * touch a workspace's roster require the actor to be an admin of it.
 */
@Service
@RequiredArgsConstructor
public class InviteService {

    private final InviteMapper inviteMapper;
    private final WorkspaceMapper workspaceMapper;
    private final UserOffboardingService userOffboardingService;
    private final UserMapper userMapper;
    private final WorkspaceService workspaceService;
    private final OrgAllowedDomainService orgAllowedDomainService;
    private final AuditService auditService;
    private final InviteEmailService inviteEmailService;
    private final SessionSecurityService sessionSecurityService;
    private final NotificationStateVersionService notificationStateVersionService;
    private final FreshMembershipTransaction freshMembershipTransaction;

    /**
     * Invites someone to a workspace by email. An address that already belongs to
     * a Connex user is added as a pending member and notified in-app (they accept
     * from Settings); any other address gets an emailed token invite. Either way,
     * an earlier pending invite for the same email is superseded.
     */
    public InviteResultDto createInvite(int workspaceId, User actor, String emailRaw, String roleRaw) {
        workspaceService.requirePermission(workspaceId, actor.getId(), Permission.MEMBER_MANAGE);
        sessionSecurityService.requireRecentAuthentication(actor.getId());
        return freshMembershipTransaction.execute(
            workspaceId,
            () -> createInviteInWorkspace(workspaceId, actor, emailRaw, roleRaw));
    }

    private InviteResultDto createInviteInWorkspace(
            int workspaceId, User actor, String emailRaw, String roleRaw) {
        workspaceService.requirePermission(workspaceId, actor.getId(), Permission.MEMBER_MANAGE);
        sessionSecurityService.requireRecentAuthentication(actor.getId());
        String email = normalizeEmail(emailRaw);
        String role = normalizeRole(roleRaw);
        requireOrgDomainAllowed(workspaceId, email);

        User existing = userMapper.getUserByEmail(email);
        workspaceService.lockInviteGrantAuthorization(
            workspaceId,
            actor.getId(),
            existing == null ? null : existing.getId(),
            role);
        if (existing != null) {
            User lockedExisting = userMapper.getUserById(existing.getId());
            if (lockedExisting == null || !email.equalsIgnoreCase(lockedExisting.getEmail())) {
                throw new ConflictException("Invite recipient changed; refresh and retry");
            }
            inviteMapper.revokePendingForEmail(workspaceId, email);
            MemberDto member = workspaceService.addPendingMember(
                workspaceId, actor, lockedExisting, role);
            return new InviteResultDto(null, member);
        }

        inviteMapper.revokePendingForEmail(workspaceId, email);

        WorkspaceInvite invite = new WorkspaceInvite();
        invite.setWorkspaceId(workspaceId);
        invite.setEmail(email);
        invite.setRole(role);
        invite.setToken(OneTimeTokenDigest.generate());
        invite.setTokenHash(OneTimeTokenDigest.sha256(invite.getToken()));
        invite.setInvitedById(actor.getId());
        inviteMapper.insertHashed(invite);

        auditService.record("workspace.invite", "workspace", workspaceId, email,
                "Invited " + email + " as " + role, null);

        inviteEmailService.sendInvite(workspaceId, workspaceNameFor(actor, workspaceId), email,
                actor.getDisplayName(), role, invite.getToken());

        WorkspaceInvite saved = inviteMapper.findByTokenHash(invite.getTokenHash());
        InviteDto dto = new InviteDto();
        dto.setId(saved.getId());
        dto.setEmail(saved.getEmail());
        dto.setRole(saved.getRole());
        dto.setStatus(saved.getStatus());
        dto.setToken(invite.getToken());
        dto.setExpiresAt(saved.getExpiresAt());
        dto.setCreatedAt(saved.getCreatedAt());
        dto.setInvitedByLabel(actor.getDisplayName());
        return new InviteResultDto(dto, null);
    }

    /** Lists the workspace's still-pending invites. */
    public List<InviteDto> listInvites(int workspaceId, int actorId) {
        workspaceService.requirePermission(workspaceId, actorId, Permission.MEMBER_MANAGE);
        return inviteMapper.findPendingByWorkspace(workspaceId);
    }

    /** Revokes a pending invite. */
    public void revokeInvite(int workspaceId, int inviteId, int actorId) {
        workspaceService.requirePermission(workspaceId, actorId, Permission.MEMBER_MANAGE);
        sessionSecurityService.requireRecentAuthentication(actorId);
        if (inviteMapper.markRevoked(inviteId, workspaceId) == 0) {
            throw new ResourceNotFoundException("Invite not found");
        }
        auditService.record("workspace.invite.revoke", "workspace", workspaceId, null,
                "Revoked invite " + inviteId, null);
    }

    /** Looks up an invite by token for the accept screen. No role gate; the token is the secret. */
    public InvitePreviewDto previewInvite(String token) {
        InvitePreviewDto preview = inviteMapper.findPreviewByToken(token);
        if (preview == null) {
            throw new ResourceNotFoundException("Invite not found");
        }
        return preview;
    }

    /**
     * Claims a raw email-invite bearer for one browser and server-session lineage. A retry from
     * that lineage remains recoverable until invite expiry; every other lineage is refused.
     * @param rawToken token carried only in the fragment-to-body bootstrap request
     * @param exchangeOwnerHash one-way owner of the browser and server-session exchange
     * @return source-token digest stored in the purpose-bound flow session
     */
    @Transactional
    public String exchangeToken(String rawToken, String exchangeOwnerHash) {
        String tokenHash = rawToken == null || rawToken.isBlank()
            ? null
            : OneTimeTokenDigest.sha256(rawToken);
        if (tokenHash == null || exchangeOwnerHash == null || exchangeOwnerHash.isBlank()) {
            throw invalidLink();
        }
        int claimed = inviteMapper.claimExchangeByHash(tokenHash, exchangeOwnerHash);
        if (claimed != 1
                && !inviteMapper.isExchangeOwnedByHash(tokenHash, exchangeOwnerHash)) {
            throw invalidLink();
        }
        return tokenHash;
    }

    /** Looks up an exchanged invite by its server-side source digest. */
    public InvitePreviewDto previewInviteByHash(String tokenHash) {
        InvitePreviewDto preview = inviteMapper.findPreviewByTokenHash(tokenHash);
        if (preview == null || !preview.isValid()) {
            throw invalidLink();
        }
        return preview;
    }

    /** Redeems an invite for the authenticated user whose email it targets. */
    public WorkspaceMembershipDto acceptInvite(String token, User user) {
        WorkspaceInvite target = inviteMapper.findByToken(token);
        if (target == null) {
            throw new ResourceNotFoundException("Invite not found");
        }
        return freshMembershipTransaction.execute(
            target.getWorkspaceId(),
            () -> acceptInviteInWorkspace(token, user));
    }

    /**
     * Redeems an exchanged invite and completes its browser grant in the membership transaction.
     * @param tokenHash digest of the exchanged source token
     * @param user authenticated invite recipient
     * @param flowCompletion grant delete that participates in the membership transaction
     * @return the accepted workspace membership
     */
    public WorkspaceMembershipDto acceptInviteByHash(
            String tokenHash, User user, Runnable flowCompletion) {
        WorkspaceInvite target = inviteMapper.findByTokenHash(tokenHash);
        if (target == null || !inviteMapper.isExchangedRedeemable(tokenHash)) {
            throw invalidLink();
        }
        return freshMembershipTransaction.execute(
            target.getWorkspaceId(),
            () -> acceptInviteByHashInWorkspace(tokenHash, user, flowCompletion));
    }

    private WorkspaceMembershipDto acceptInviteByHashInWorkspace(
            String tokenHash, User user, Runnable flowCompletion) {
        WorkspaceInvite invite = inviteMapper.findByTokenHash(tokenHash);
        if (invite == null || !"pending".equals(invite.getStatus())
                || !inviteMapper.isExchangedRedeemable(tokenHash)) {
            throw invalidLink();
        }
        WorkspaceMembershipDto membership = acceptResolvedInvite(invite, user, tokenHash, true);
        workspaceService.rememberActive(user.getId(), membership.getId());
        flowCompletion.run();
        return membership;
    }

    private WorkspaceMembershipDto acceptInviteInWorkspace(String token, User user) {
        WorkspaceInvite invite = inviteMapper.findByToken(token);
        if (invite == null) {
            throw new ResourceNotFoundException("Invite not found");
        }
        if (!"pending".equals(invite.getStatus())) {
            throw new BadRequestException("This invite is no longer available");
        }
        if (!inviteMapper.isRedeemable(token)) {
            throw new BadRequestException("This invite has expired");
        }
        if (!user.getEmail().equalsIgnoreCase(invite.getEmail())) {
            throw new ForbiddenException("This invite was sent to a different email address");
        }

        return acceptResolvedInvite(invite, user, token, false);
    }

    private WorkspaceMembershipDto acceptResolvedInvite(
            WorkspaceInvite invite, User user, String credential, boolean exchanged) {
        int workspaceId = invite.getWorkspaceId();
        Integer inviterId = invite.getInvitedById();
        if (inviterId == null) {
            throw invalidLink();
        }
        workspaceService.lockPersistedInviteGrantAuthorization(
            workspaceId, inviterId, user.getId(), invite.getRole());
        int orgId = requireOrgDomainAllowed(workspaceId, user.getEmail());
        User lockedUser = userMapper.getUserByIdForShare(user.getId());
        if (lockedUser == null) {
            throw new ResourceNotFoundException("User not found: " + user.getId());
        }
        if (!lockedUser.getEmail().equalsIgnoreCase(invite.getEmail())) {
            throw new ForbiddenException("This invite was sent to a different email address");
        }
        if (workspaceMapper.lockWorkspaceForShare(workspaceId) == null) {
            throw new ResourceNotFoundException("Workspace not found: " + workspaceId);
        }
        int claimed = exchanged
            ? inviteMapper.claimAcceptanceByHash(
                invite.getId(), credential, workspaceId, lockedUser.getId())
            : inviteMapper.claimAcceptance(
                invite.getId(), credential, workspaceId, lockedUser.getId());
        if (claimed != 1) {
            throw new BadRequestException("This invite is no longer available");
        }
        WorkspaceMember membership =
            workspaceMapper.lockAuthorizationMembership(workspaceId, lockedUser.getId());
        if (membership == null) {
            userOffboardingService.prepareFreshMembership(workspaceId, lockedUser.getId());
            workspaceMapper.addMember(workspaceId, lockedUser.getId(), invite.getRole());
            notificationStateVersionService.markChanged(lockedUser.getId());
        } else if (!"active".equals(membership.getStatus())) {
            throw new BadRequestException("That person is already a member of or invited to this workspace");
        }
        auditService.recordScoped(
                "workspace.invite.accept", "workspace", workspaceId, workspaceId, orgId,
                lockedUser.getDisplayName(), lockedUser.getDisplayName() + " joined via invite", null);

        return membership(lockedUser.getId(), workspaceId);
    }

    /** Invites an existing Connex user to the workspace by email; they join after accepting. */
    public MemberDto addExistingMember(int workspaceId, int actorId, String emailRaw, String roleRaw) {
        workspaceService.requirePermission(workspaceId, actorId, Permission.MEMBER_MANAGE);
        sessionSecurityService.requireRecentAuthentication(actorId);
        return freshMembershipTransaction.execute(
            workspaceId,
            () -> addExistingMemberInWorkspace(workspaceId, actorId, emailRaw, roleRaw));
    }

    private MemberDto addExistingMemberInWorkspace(
            int workspaceId, int actorId, String emailRaw, String roleRaw) {
        workspaceService.requirePermission(workspaceId, actorId, Permission.MEMBER_MANAGE);
        sessionSecurityService.requireRecentAuthentication(actorId);
        String email = normalizeEmail(emailRaw);
        String role = normalizeRole(roleRaw);
        requireOrgDomainAllowed(workspaceId, email);

        User user = userMapper.getUserByEmail(email);
        if (user == null) {
            throw new BadRequestException("No Connex account uses that email; send an invite instead");
        }
        workspaceService.lockInviteGrantAuthorization(
            workspaceId, actorId, user.getId(), role);
        User lockedUser = userMapper.getUserById(user.getId());
        if (lockedUser == null || !email.equalsIgnoreCase(lockedUser.getEmail())) {
            throw new ConflictException("Invite recipient changed; refresh and retry");
        }
        User actor = userMapper.getUserById(actorId);
        return workspaceService.addPendingMember(workspaceId, actor, lockedUser, role);
    }

    /**
     * Enforces the organization's email-domain ceiling (#316, Option B) before a workspace roster
     * change. When the workspace's org restricts invite domains, {@code email} must be on the org
     * allowlist; an org with no allowlist is unrestricted, so existing workspaces are unaffected.
     */
    private int requireOrgDomainAllowed(int workspaceId, String email) {
        int orgId = workspaceService.getOrgId(workspaceId);
        if (!orgAllowedDomainService.isJoinAllowed(orgId, email)) {
            throw new ForbiddenException("This organization only allows members from approved email domains");
        }
        return orgId;
    }

    private String workspaceNameFor(User actor, int workspaceId) {
        return workspaceMapper.getWorkspacesForUser(actor.getId()).stream()
            .filter(w -> w.getId() == workspaceId)
            .map(ooo.klae.connex.backend.beans.Workspace::getName)
            .findFirst()
            .orElse(null);
    }

    private WorkspaceMembershipDto membership(int userId, int workspaceId) {
        WorkspaceMembershipDto membership =
            workspaceMapper.getMembershipForUserForShare(workspaceId, userId);
        if (membership == null) {
            throw new ResourceNotFoundException("Membership not found");
        }
        return membership;
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase();
    }

    private static String normalizeRole(String role) {
        String normalized = (role == null || role.isBlank()) ? "member" : role.trim().toLowerCase();
        if (!normalized.equals("member") && !normalized.equals("admin")) {
            throw new BadRequestException("Role must be member or admin");
        }
        return normalized;
    }

    private static BadRequestException invalidLink() {
        return new BadRequestException("This invite is invalid or has expired");
    }
}
