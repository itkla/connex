package ooo.klae.connex.backend.services;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.EmailChangeToken;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.RevokedInvitationDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.DuplicateResourceException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.EmailChangeTokenMapper;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.notifications.NotificationStateVersionService;
import ooo.klae.connex.backend.util.OneTimeTokenDigest;

/**
 * Drives the verified account email-change flow. Changing an account email is a
 * two-step, ownership-proving operation: the authenticated owner requests the
 * change with their current password (step-up), and the change applies only when
 * the recipient redeems a single-use token delivered to the <em>new</em> address.
 * This keeps email a trustworthy identity anchor (email-bound invites rely on it).
 * Only the SHA-256 hash of a token is persisted; the raw token is delivered by
 * {@link EmailChangeEmailService} and never stored or returned.
 */
@Service
@RequiredArgsConstructor
public class EmailChangeService {

    private final UserMapper userMapper;
    private final WorkspaceMapper workspaceMapper;
    private final NotificationMapper notificationMapper;
    private final NotificationStateVersionService notificationStateVersionService;
    private final EmailChangeTokenMapper emailChangeTokenMapper;
    private final EmailChangeEmailService emailChangeEmailService;
    private final PasswordEncoder passwordEncoder;
    private final AuthService authService;
    private final AuditService auditService;

    @Value("${connex.email-change.token-expiry-minutes:30}")
    private int tokenExpiryMinutes;

    @Value("${connex.email-change.request-window-seconds:900}")
    private int requestWindowSeconds;

    @Value("${connex.email-change.max-requests:5}")
    private int maxRequests;

    /**
     * Issues a verification token for the current user's requested new email and
     * emails the link to that new address. Requires the caller's current password
     * (step-up) and rejects an address already in use.
     *
     * <p>Preliminary validation is non-locking. Token replacement then locks the account before
     * invalidating tokens, matching confirmation's account-before-token order. Validation repeats
     * under that root at READ COMMITTED after invalidation clears the mapper read cache; a refusal
     * rolls back the invalidation. Delivery is dispatched off-thread and best-effort by
     * {@code MailService.sendInstance}; confirmation rechecks address uniqueness before applying it.
     * @param newEmailRaw the requested new email address
     * @param currentPassword the caller's current password, verified before issuing
     * @param requestIp the requesting client IP, recorded for abuse audit
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void requestChange(String newEmailRaw, String currentPassword, String requestIp) {
        User user = authService.getCurrentUser();
        String newEmail = normalizeEmail(newEmailRaw);
        validateRequest(user, newEmail, currentPassword);

        user = lockUser(user.getId());
        emailChangeTokenMapper.invalidateForUser(user.getId());
        validateRequest(user, newEmail, currentPassword);

        String rawToken = OneTimeTokenDigest.generate();
        emailChangeTokenMapper.insert(
            user.getId(), newEmail, OneTimeTokenDigest.sha256(rawToken), requestIp, tokenExpiryMinutes);
        emailChangeEmailService.sendVerificationEmail(user, newEmail, rawToken);

        auditService.record("user.email_change_requested", "user", user.getId(), user.getDisplayName(),
                "Requested a verified email change", null);
    }

    private void validateRequest(User user, String newEmail, String currentPassword) {
        if (currentPassword == null || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new ForbiddenException("Your current password is incorrect");
        }
        if (newEmail.equalsIgnoreCase(normalizeEmail(user.getEmail()))) {
            throw new BadRequestException("That is already your email address");
        }
        if (userMapper.getUserByEmail(newEmail) != null) {
            throw new DuplicateResourceException("That email address is already in use");
        }
        if (emailChangeTokenMapper.countRecentByUser(user.getId(), requestWindowSeconds) >= maxRequests) {
            throw new BadRequestException("Too many email-change requests; please try again later");
        }
    }

    /**
     * Reports whether a raw token is currently redeemable (unconsumed and unexpired).
     * @param rawToken the unhashed token from the verification link
     * @return true when the token can still be used to apply the email change
     */
    public boolean validateToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return false;
        }
        return emailChangeTokenMapper.existsRedeemableByHash(OneTimeTokenDigest.sha256(rawToken));
    }

    /**
     * Claims the raw token for one browser and server-session lineage, locking its account before
     * the token write so programmatic confirmation cannot invert replacement's lock order.
     * READ COMMITTED keeps same-browser retry checks current after waiting for the account root.
     * @param rawToken raw fragment bearer
     * @param exchangeOwnerHash one-way owner of the browser and server-session exchange
     * @return persisted source-token digest
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public String exchangeToken(String rawToken, String exchangeOwnerHash) {
        String tokenHash = rawToken == null || rawToken.isBlank()
            ? null
            : OneTimeTokenDigest.sha256(rawToken);
        if (tokenHash == null || exchangeOwnerHash == null || exchangeOwnerHash.isBlank()) {
            throw invalidLink();
        }
        EmailChangeToken token = emailChangeTokenMapper.findRedeemableByHash(tokenHash);
        if (token == null) {
            throw invalidLink();
        }
        lockUser(token.getUserId());
        int claimed = emailChangeTokenMapper.claimExchange(tokenHash, exchangeOwnerHash);
        if (claimed != 1
                && !emailChangeTokenMapper.isExchangeOwnedBy(tokenHash, exchangeOwnerHash)) {
            throw invalidLink();
        }
        return tokenHash;
    }

    /** @return whether an exchanged source digest is still redeemable */
    public boolean validateExchangedTokenHash(String tokenHash) {
        return tokenHash != null
            && emailChangeTokenMapper.existsExchangedRedeemableByHash(tokenHash);
    }

    /**
     * Applies the pending email change bound to a redeemable token, then consumes
     * the token and invalidates the user's other outstanding email-change tokens.
     * Re-checks uniqueness at confirm time in case the address was claimed since.
     *
     * <p>The programmatic, non-browser entry point: it self-claims the exchange rather than
     * carrying a browser-bound flow grant, so the HTTP surface uses
     * {@link #confirmChangeByHash(String)} instead.
     * @param rawToken the unhashed token from the verification link
     * @return the pending invitations the change revoked, empty when there were none
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<RevokedInvitationDto> confirmChange(String rawToken) {
        String tokenHash = rawToken == null ? null : OneTimeTokenDigest.sha256(rawToken);
        return confirmChangeByHash(exchangeToken(rawToken, programmaticExchangeOwner(tokenHash)));
    }

    /**
     * Applies a verified email change and revokes the pending grants addressed to the previous
     * email. READ COMMITTED makes pending-grant discovery current after waiting for the account
     * lock, so a grant committed while this flow queued is revoked rather than missed.
     * @param tokenHash the purpose-bound browser-flow source digest
     * @return the pending invitations the change revoked, empty when there were none
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<RevokedInvitationDto> confirmChangeByHash(String tokenHash) {
        EmailChangeToken token = tokenHash == null ? null
                : emailChangeTokenMapper.findExchangedRedeemableByHash(tokenHash);
        if (token == null) {
            throw invalidLink();
        }

        User user = lockUser(token.getUserId());

        User existing = userMapper.getUserByEmail(token.getNewEmail());
        if (existing != null && existing.getId() != user.getId()) {
            throw new DuplicateResourceException("That email address is already in use");
        }

        if (emailChangeTokenMapper.markConsumed(tokenHash) == 0) {
            throw invalidLink();
        }
        userMapper.updateEmail(user.getId(), token.getNewEmail());
        List<RevokedInvitationDto> revoked = revokePendingMemberships(user.getId());
        userMapper.markEmailVerified(user.getId());
        emailChangeTokenMapper.invalidateForUser(user.getId());

        auditService.record("user.email_change_completed", "user", user.getId(), user.getDisplayName(),
                "Completed a verified email change", null);
        return revoked;
    }

    private User lockUser(int userId) {
        if (userMapper.lockById(userId) == null) {
            throw invalidLink();
        }
        User user = userMapper.getUserByIdForShare(userId);
        if (user == null) {
            throw invalidLink();
        }
        return user;
    }

    /**
     * Drops every pending grant addressed to the account's previous email. A pending row is an
     * offer to one mailbox, so moving the account off that mailbox must not carry the offer along.
     *
     * <p>Runs under the already-held account root: workspace roots are locked in ascending id, then
     * the recipient's globally ordered membership set, matching the member-removal contract in
     * {@code docs/backend/LOCKING.md} so notification cleanup cannot invert membership lock order.
     * Each revoked grant gets the same recipient-scoped notification cleanup and scoped audit event
     * as a declined invitation, so no orphaned "invited you" row or unattributed deletion is left.
     * The pending row is deleted first and the cleanup runs only when that delete claimed the row,
     * so a grant a concurrent decline already removed leaves no partial deletion behind and the
     * notification state version is bumped exactly when this sweep deleted something.
     * @param userId the account whose address just changed
     * @return the revoked grants in ascending workspace id
     */
    private List<RevokedInvitationDto> revokePendingMemberships(int userId) {
        List<RevokedInvitationDto> grants = workspaceMapper.findPendingGrants(userId);
        if (grants.isEmpty()) {
            return List.of();
        }
        for (RevokedInvitationDto grant : grants) {
            workspaceMapper.lockWorkspace(grant.getWorkspaceId());
        }
        notificationMapper.lockRecipientMemberships(userId);
        List<RevokedInvitationDto> revoked = new ArrayList<>();
        for (RevokedInvitationDto grant : grants) {
            int workspaceId = grant.getWorkspaceId();
            if (workspaceMapper.removePendingMember(workspaceId, userId) == 0) {
                continue;
            }
            notificationMapper.deleteHistoricalNotificationBaselinesForRecipient(workspaceId, userId);
            notificationMapper.deleteAllForRecipient(workspaceId, userId);
            revoked.add(grant);
            auditService.recordScoped(
                "workspace.member.decline", "workspace", workspaceId, workspaceId, grant.getOrgId(),
                grant.getWorkspaceName(),
                "Pending invitation revoked by a verified email change", null);
        }
        if (!revoked.isEmpty()) {
            notificationStateVersionService.markChanged(userId);
        }
        return List.copyOf(revoked);
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private static BadRequestException invalidLink() {
        return new BadRequestException("This verification link is invalid or has expired");
    }

    private static String programmaticExchangeOwner(String tokenHash) {
        return tokenHash == null ? "" : OneTimeTokenDigest.sha256("email-change:" + tokenHash);
    }
}
