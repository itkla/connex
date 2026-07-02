package ooo.klae.connex.backend.services;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.WorkspaceInvite;
import ooo.klae.connex.backend.dto.InviteDto;
import ooo.klae.connex.backend.dto.InvitePreviewDto;
import ooo.klae.connex.backend.dto.InviteResultDto;
import ooo.klae.connex.backend.dto.MemberDto;
import ooo.klae.connex.backend.dto.WorkspaceMembershipDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.InviteMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.Permission;

/**
 * The two workspace onboarding flows: email-token invites (create / list /
 * revoke / preview / accept) and admin-adds-an-existing-user. Mutations that
 * touch a workspace's roster require the actor to be an admin of it.
 */
@Service
@RequiredArgsConstructor
public class InviteService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private final InviteMapper inviteMapper;
    private final WorkspaceMapper workspaceMapper;
    private final UserMapper userMapper;
    private final WorkspaceService workspaceService;
    private final AuditService auditService;
    private final InviteEmailService inviteEmailService;

    /**
     * Invites someone to a workspace by email. An address that already belongs to
     * a Connex user is added as a pending member and notified in-app (they accept
     * from Settings); any other address gets an emailed token invite. Either way,
     * an earlier pending invite for the same email is superseded.
     */
    @Transactional
    public InviteResultDto createInvite(int workspaceId, User actor, String emailRaw, String roleRaw) {
        workspaceService.requirePermission(workspaceId, actor.getId(), Permission.MEMBER_MANAGE);
        String email = normalizeEmail(emailRaw);
        String role = normalizeRole(roleRaw);

        User existing = userMapper.getUserByEmail(email);
        if (existing != null) {
            if (workspaceMapper.getMember(workspaceId, existing.getId()) != null) {
                throw new BadRequestException("That person is already a member of or invited to this workspace");
            }
            inviteMapper.revokePendingForEmail(workspaceId, email);
            MemberDto member = workspaceService.addPendingMember(workspaceId, actor, existing, role);
            return new InviteResultDto(null, member);
        }

        inviteMapper.revokePendingForEmail(workspaceId, email);

        WorkspaceInvite invite = new WorkspaceInvite();
        invite.setWorkspaceId(workspaceId);
        invite.setEmail(email);
        invite.setRole(role);
        invite.setToken(generateToken());
        invite.setInvitedById(actor.getId());
        inviteMapper.insert(invite);

        auditService.record("workspace.invite", "workspace", workspaceId, email,
                "Invited " + email + " as " + role, null);

        inviteEmailService.sendInvite(workspaceId, workspaceNameFor(actor, workspaceId), email,
                actor.getDisplayName(), role, invite.getToken());

        WorkspaceInvite saved = inviteMapper.findByToken(invite.getToken());
        InviteDto dto = new InviteDto();
        dto.setId(saved.getId());
        dto.setEmail(saved.getEmail());
        dto.setRole(saved.getRole());
        dto.setStatus(saved.getStatus());
        dto.setToken(saved.getToken());
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

    /** Redeems an invite for the authenticated user whose email it targets. */
    public WorkspaceMembershipDto acceptInvite(String token, User user) {
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

        int workspaceId = invite.getWorkspaceId();
        if (!workspaceMapper.isMember(workspaceId, user.getId())) {
            workspaceMapper.addMember(workspaceId, user.getId(), invite.getRole());
        }
        inviteMapper.markAccepted(invite.getId(), user.getId());
        auditService.record("workspace.invite.accept", "workspace", workspaceId, user.getDisplayName(),
                user.getDisplayName() + " joined via invite", null);

        return membership(user.getId(), workspaceId);
    }

    /** Invites an existing Connex user to the workspace by email; they join after accepting. */
    public MemberDto addExistingMember(int workspaceId, int actorId, String emailRaw, String roleRaw) {
        workspaceService.requirePermission(workspaceId, actorId, Permission.MEMBER_MANAGE);
        String email = normalizeEmail(emailRaw);
        String role = normalizeRole(roleRaw);

        User user = userMapper.getUserByEmail(email);
        if (user == null) {
            throw new BadRequestException("No Connex account uses that email; send an invite instead");
        }
        if (workspaceMapper.getMember(workspaceId, user.getId()) != null) {
            throw new BadRequestException("That person is already a member of or invited to this workspace");
        }
        User actor = userMapper.getUserById(actorId);
        return workspaceService.addPendingMember(workspaceId, actor, user, role);
    }

    private String workspaceNameFor(User actor, int workspaceId) {
        return workspaceMapper.getWorkspacesForUser(actor.getId()).stream()
            .filter(w -> w.getId() == workspaceId)
            .map(ooo.klae.connex.backend.beans.Workspace::getName)
            .findFirst()
            .orElse(null);
    }

    private WorkspaceMembershipDto membership(int userId, int workspaceId) {
        return workspaceMapper.getMembershipsForUser(userId).stream()
            .filter(m -> m.getId() == workspaceId)
            .findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("Membership not found"));
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

    private static String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
