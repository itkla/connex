package ooo.klae.connex.backend.services;

import java.util.List;

import org.springframework.stereotype.Service;

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
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.notifications.NotificationStateVersionService;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.util.OneTimeTokenDigest;

/**
 * Shareable invite links: an owner/admin creates, lists, and revokes them (gated by
 * {@code MEMBER_MANAGE}); any authenticated user holding a valid token can redeem one to join the
 * workspace with the link's role. Email-bound, single-use invites live in {@code InviteService};
 * this is the unbound, multi-use channel.
 */
@Service
@RequiredArgsConstructor
public class InviteLinkService {

    private static final int DEFAULT_EXPIRES_IN_DAYS = 14;

    private final InviteLinkMapper inviteLinkMapper;
    private final WorkspaceMapper workspaceMapper;
    private final UserOffboardingService userOffboardingService;
    private final UserMapper userMapper;
    private final AuditService auditService;
    private final WorkspaceService workspaceService;
    private final AllowedDomainService allowedDomainService;
    private final OrgAllowedDomainService orgAllowedDomainService;
    private final RegistrationVerificationService registrationVerificationService;
    private final SessionSecurityService sessionSecurityService;
    private final NotificationStateVersionService notificationStateVersionService;
    private final FreshMembershipTransaction freshMembershipTransaction;

    /** Creates a shareable link. Defaults: member role, 14-day expiry, unlimited uses. */
    public InviteLinkDto createLink(int workspaceId, User actor, String roleRaw,
            Integer expiresInDays, Integer maxUses) {
        workspaceService.requirePermission(workspaceId, actor.getId(), Permission.MEMBER_MANAGE);
        sessionSecurityService.requireRecentAuthentication(actor.getId());
        String role = normalizeRole(roleRaw);
        int days = (expiresInDays == null || expiresInDays <= 0) ? DEFAULT_EXPIRES_IN_DAYS : expiresInDays;
        String token = OneTimeTokenDigest.generate();
        String tokenHash = OneTimeTokenDigest.sha256(token);
        inviteLinkMapper.insertHashed(workspaceId, tokenHash, role, days, maxUses, actor.getId());
        auditService.record("workspace.invite_link.create", "workspace", workspaceId, null,
                "Created an invite link granting " + role, null);
        InviteLinkDto dto = toDto(inviteLinkMapper.findByTokenHash(tokenHash), actor.getDisplayName());
        dto.setToken(token);
        return dto;
    }

    /** Lists the workspace's active (non-revoked) links. */
    public List<InviteLinkDto> listLinks(int workspaceId, int actorId) {
        workspaceService.requirePermission(workspaceId, actorId, Permission.MEMBER_MANAGE);
        return inviteLinkMapper.findActiveByWorkspace(workspaceId);
    }

    /** Revokes a link so it can no longer be redeemed. */
    public void revokeLink(int workspaceId, int linkId, int actorId) {
        workspaceService.requirePermission(workspaceId, actorId, Permission.MEMBER_MANAGE);
        sessionSecurityService.requireRecentAuthentication(actorId);
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
     * Validates a shareable raw bearer and returns its digest for a single-use browser flow grant.
     * The underlying owner-issued bearer is an explicit exception to single-use source tokens: it
     * may establish grants in multiple browser sessions because the product feature is a shareable
     * invite. Each browser grant is still short-lived and single-use. Source use is bounded by the
     * link's expiry, owner revocation, optional maximum-use count, domain policy, verified-email
     * policy, and the exchange admission throttle; final redemption atomically claims one use.
     */
    public String exchangeToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw invalidLink();
        }
        String tokenHash = OneTimeTokenDigest.sha256(rawToken);
        InviteLinkPreviewDto preview = inviteLinkMapper.findPreviewByTokenHash(tokenHash);
        if (preview == null || !preview.isValid()) {
            throw invalidLink();
        }
        return tokenHash;
    }

    /** Looks up a shareable link through its purpose-bound server-session source digest. */
    public InviteLinkPreviewDto previewLinkByHash(String tokenHash) {
        InviteLinkPreviewDto preview = inviteLinkMapper.findPreviewByTokenHash(tokenHash);
        if (preview == null || !preview.isValid()) {
            throw invalidLink();
        }
        return preview;
    }

    /**
     * Redeems a link for the authenticated user, joining them with the link's role.
     * Idempotent for an existing member; re-joining after removal is treated as a
     * fresh redemption, so a prior redemption record never bypasses revocation,
     * expiry, use-exhaustion, or the domain allow-list.
     */
    public WorkspaceMembershipDto redeemLink(String token, User user) {
        WorkspaceInviteLink target = inviteLinkMapper.findByToken(token);
        if (target == null) {
            throw new ResourceNotFoundException("Invite link not found");
        }
        return freshMembershipTransaction.execute(
            target.getWorkspaceId(),
            () -> redeemLinkInWorkspace(token, user));
    }

    /** Redeems a shareable link without restoring its raw bearer to an API path or body. */
    public WorkspaceMembershipDto redeemLinkByHash(String tokenHash, User user) {
        WorkspaceInviteLink target = inviteLinkMapper.findByTokenHash(tokenHash);
        if (target == null) {
            throw invalidLink();
        }
        return freshMembershipTransaction.execute(
            target.getWorkspaceId(),
            () -> redeemLinkByHashInWorkspace(tokenHash, user));
    }

    private WorkspaceMembershipDto redeemLinkByHashInWorkspace(String tokenHash, User user) {
        WorkspaceInviteLink link = inviteLinkMapper.findByTokenHash(tokenHash);
        if (link == null) {
            throw invalidLink();
        }
        return redeemResolvedLink(link, user);
    }

    private WorkspaceMembershipDto redeemLinkInWorkspace(String token, User user) {
        WorkspaceInviteLink link = inviteLinkMapper.findByToken(token);
        if (link == null) {
            throw new ResourceNotFoundException("Invite link not found");
        }
        return redeemResolvedLink(link, user);
    }

    private WorkspaceMembershipDto redeemResolvedLink(WorkspaceInviteLink link, User user) {
        int workspaceId = link.getWorkspaceId();

        // Already an active member: idempotent no-op, regardless of prior redemption.
        if (workspaceMapper.isMember(workspaceId, user.getId())) {
            return membership(user.getId(), workspaceId);
        }

        // Joining (or re-joining after removal): the link must currently be valid and the domain
        // allowed by both the org ceiling (#316) and the per-workspace list. A past redemption
        // record is history, not a standing grant.
        int orgId = workspaceService.getOrgId(workspaceId);
        if (!orgAllowedDomainService.isJoinAllowed(orgId, user.getEmail())
                || !allowedDomainService.isJoinAllowed(workspaceId, user.getEmail())) {
            throw new ForbiddenException("Your email domain isn't permitted to join this workspace");
        }

        // A domain allowlist is only trustworthy if the joiner's email is verified — otherwise
        // an unverified account could register with an allowed-domain address it doesn't own and
        // slip past the domain gate. Enforced only when registration verification is enabled.
        if (registrationVerificationService.isEnabled()
                && (orgAllowedDomainService.hasRestrictions(orgId) || allowedDomainService.hasRestrictions(workspaceId))) {
            User fresh = userMapper.getUserById(user.getId());
            if (fresh == null || !fresh.isEmailVerified()) {
                throw new ForbiddenException("Verify your email address before joining this workspace");
            }
        }

        // Atomically claim a use, rejecting revoked / expired / exhausted links.
        if (inviteLinkMapper.incrementUsedCount(link.getId()) == 0) {
            throw new BadRequestException("This invite link is no longer available");
        }
        if (!inviteLinkMapper.hasRedeemed(link.getId(), user.getId())) {
            inviteLinkMapper.recordRedemption(link.getId(), user.getId());
        }
        userOffboardingService.prepareFreshMembership(workspaceId, user.getId());
        workspaceMapper.addMember(workspaceId, user.getId(), link.getRole());
        notificationStateVersionService.markChanged(user.getId());
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

    private static BadRequestException invalidLink() {
        return new BadRequestException("This invite link is invalid or has expired");
    }
}
