package ooo.klae.connex.backend.services;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.WorkspaceInviteLink;
import ooo.klae.connex.backend.dto.InviteLinkDto;
import ooo.klae.connex.backend.dto.InviteLinkPreviewDto;
import ooo.klae.connex.backend.dto.WorkspaceMembershipDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.InviteLinkMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.Permission;

/**
 * Shareable invite links: an owner/admin creates, lists, and revokes them (gated by
 * {@code MEMBER_MANAGE}); any authenticated user holding a valid token can redeem one to join the
 * workspace with the link's role. Email-bound, single-use invites live in {@code InviteService};
 * this is the unbound, multi-use channel.
 */
@Service
@RequiredArgsConstructor
public class InviteLinkService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;
    private static final int DEFAULT_EXPIRES_IN_DAYS = 14;

    private final InviteLinkMapper inviteLinkMapper;
    private final WorkspaceMapper workspaceMapper;
    private final AuditService auditService;
    private final WorkspaceService workspaceService;
    private final AllowedDomainService allowedDomainService;

    /** Creates a shareable link. Defaults: member role, 14-day expiry, unlimited uses. */
    public InviteLinkDto createLink(int workspaceId, User actor, String roleRaw,
            Integer expiresInDays, Integer maxUses) {
        workspaceService.requirePermission(workspaceId, actor.getId(), Permission.MEMBER_MANAGE);
        String role = normalizeRole(roleRaw);
        int days = (expiresInDays == null || expiresInDays <= 0) ? DEFAULT_EXPIRES_IN_DAYS : expiresInDays;
        String token = generateToken();
        inviteLinkMapper.insert(workspaceId, token, role, days, maxUses, actor.getId());
        auditService.record("workspace.invite_link.create", "workspace", workspaceId, null,
                "Created an invite link granting " + role, null);
        return toDto(inviteLinkMapper.findByToken(token), actor.getDisplayName());
    }

    /** Lists the workspace's active (non-revoked) links. */
    public List<InviteLinkDto> listLinks(int workspaceId, int actorId) {
        workspaceService.requirePermission(workspaceId, actorId, Permission.MEMBER_MANAGE);
        return inviteLinkMapper.findActiveByWorkspace(workspaceId);
    }

    /** Revokes a link so it can no longer be redeemed. */
    public void revokeLink(int workspaceId, int linkId, int actorId) {
        workspaceService.requirePermission(workspaceId, actorId, Permission.MEMBER_MANAGE);
        if (inviteLinkMapper.markRevoked(linkId, workspaceId) == 0) {
            throw new ResourceNotFoundException("Invite link not found");
        }
        auditService.record("workspace.invite_link.revoke", "workspace", workspaceId, null,
                "Revoked invite link " + linkId, null);
    }

    /** Looks up a link by token for the accept screen. No role gate; the token is the secret. */
    public InviteLinkPreviewDto previewLink(String token) {
        InviteLinkPreviewDto preview = inviteLinkMapper.findPreviewByToken(token);
        if (preview == null) {
            throw new ResourceNotFoundException("Invite link not found");
        }
        return preview;
    }

    /**
     * Redeems a link for the authenticated user, joining them with the link's role.
     * Idempotent for an existing member; re-joining after removal is treated as a
     * fresh redemption, so a prior redemption record never bypasses revocation,
     * expiry, use-exhaustion, or the domain allow-list.
     */
    @Transactional
    public WorkspaceMembershipDto redeemLink(String token, User user) {
        WorkspaceInviteLink link = inviteLinkMapper.findByToken(token);
        if (link == null) {
            throw new ResourceNotFoundException("Invite link not found");
        }
        int workspaceId = link.getWorkspaceId();

        // Already an active member: idempotent no-op, regardless of prior redemption.
        if (workspaceMapper.isMember(workspaceId, user.getId())) {
            return membership(user.getId(), workspaceId);
        }

        // Joining (or re-joining after removal): the link must currently be valid and the
        // domain allowed. A past redemption record is history, not a standing grant.
        if (!allowedDomainService.isJoinAllowed(workspaceId, user.getEmail())) {
            throw new ForbiddenException("Your email domain isn't permitted to join this workspace");
        }

        // Atomically claim a use, rejecting revoked / expired / exhausted links.
        if (inviteLinkMapper.incrementUsedCount(link.getId()) == 0) {
            throw new BadRequestException("This invite link is no longer available");
        }
        if (!inviteLinkMapper.hasRedeemed(link.getId(), user.getId())) {
            inviteLinkMapper.recordRedemption(link.getId(), user.getId());
        }
        workspaceMapper.addMember(workspaceId, user.getId(), link.getRole());
        auditService.record("workspace.invite_link.accept", "workspace", workspaceId, user.getDisplayName(),
                user.getDisplayName() + " joined via an invite link", null);
        return membership(user.getId(), workspaceId);
    }

    private WorkspaceMembershipDto membership(int userId, int workspaceId) {
        return workspaceMapper.getMembershipsForUser(userId).stream()
            .filter(m -> m.getId() == workspaceId)
            .findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("Membership not found"));
    }

    private InviteLinkDto toDto(WorkspaceInviteLink link, String createdByLabel) {
        InviteLinkDto dto = new InviteLinkDto();
        dto.setId(link.getId());
        dto.setToken(link.getToken());
        dto.setRole(link.getRole());
        dto.setExpiresAt(link.getExpiresAt());
        dto.setMaxUses(link.getMaxUses());
        dto.setUsedCount(link.getUsedCount());
        dto.setRevoked(link.isRevoked());
        dto.setCreatedByLabel(createdByLabel);
        dto.setCreatedAt(link.getCreatedAt());
        return dto;
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
