package ooo.klae.connex.backend.services;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
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
import ooo.klae.connex.backend.exceptions.PasskeyEnrollmentRequiredException;
import ooo.klae.connex.backend.exceptions.RecentAuthenticationRequiredException;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;
import ooo.klae.connex.backend.mappers.EmailChangeTokenMapper;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.mappers.PasswordResetTokenMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.notifications.NotificationStateVersionService;
import ooo.klae.connex.backend.util.ClientIpResolver.ResolvedClientIp;
import ooo.klae.connex.backend.util.OneTimeTokenDigest;
import ooo.klae.connex.backend.webauthn.WebAuthnService;

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
    private final PasswordResetTokenMapper passwordResetTokenMapper;
    private final EmailChangeEmailService emailChangeEmailService;
    private final AuthService authService;
    private final SessionSecurityService sessionSecurityService;
    private final AuditService auditService;
    private final LoginRateLimiter loginRateLimiter;
    private final PrivilegedAccountService privilegedAccountService;
    private final WebAuthnService webAuthnService;

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
     * The account lock serializes issuance with recovery; the locked credential
     * snapshot must still match the one whose password was confirmed and the persisted
     * servlet-session epoch captured before confirmation, never the transient principal epoch.
     *
     * <p>The proof runs through the shared login/confirmation throttle, so repeated failures here
     * also consume the account's login failure budget: a session holder without the password can
     * therefore lock the owner out of password login for the remainder of the window. That is the
     * accepted trade for closing the unmetered guessing oracle; the owner's emailed reset path is
     * unaffected.
     *
     * <p>Confirmation outcomes are audited here rather than inside the shared confirmation step,
     * which other callers reach while already holding {@code app_user} exclusively; the audit
     * append takes its own shared lock on that row in an independent transaction, so it is emitted
     * before this method acquires the account lock. Throttle audit attempts are limited to one
     * per account per login window per JVM; wrong-password failures remain individually audited.
     *
     * <p>Address and request-count validation is preliminary until the account is locked. It repeats
     * at READ COMMITTED after invalidation clears the mapper read cache; a refusal rolls back the
     * invalidation. Delivery is dispatched off-thread and best-effort by {@code MailService.sendInstance};
     * confirmation rechecks address uniqueness before applying it.
     *
     * <p>A privileged account's address is where its first-passkey enrollment confirmation is
     * delivered, so moving it behind the password alone would let a stolen password redirect that
     * second factor (#1506). A privileged account must therefore hold a passkey and present a fresh
     * WebAuthn step-up, whether or not {@code privileged-mfa.enforced} confines it. The gate is
     * evaluated after the password proof and again under the account lock, after the assigned
     * custom roles are locked, so a promotion committed while this request waited is observed.
     * The refusal audit is an independent append that takes the actor's {@code app_user} row
     * shared, so it is written only on the pre-lock refusal; a refusal that first appears under the
     * lock is not audited, because appending there would wait on this transaction's own exclusive
     * lock until the InnoDB timeout.
     *
     * @param newEmailRaw the requested new email address
     * @param currentPassword the caller's current password, verified before issuing
     * @param requestIp the resolved client address and trusted-proxy provenance, recorded for
     *     abuse audit and used by the shared confirmation throttle
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void requestChange(String newEmailRaw, String currentPassword, ResolvedClientIp requestIp) {
        User user = authService.getCurrentUser();
        Integer sessionEpoch = sessionSecurityService.currentSessionEpoch();
        try {
            authService.requireCurrentPassword(user.getId(), currentPassword, requestIp);
        } catch (TooManyRequestsException exception) {
            if (loginRateLimiter.tryAcquirePasswordConfirmationThrottleAudit(user.getId(), System.currentTimeMillis())) {
                auditService.recordFailure("auth.password_confirmation_throttled", "user", user.getId(), null,
                        "Current-password confirmation throttled", null);
            }
            throw exception;
        } catch (BadCredentialsException exception) {
            auditService.recordFailure("auth.password_confirmation", "user", user.getId(), null,
                    "Current-password confirmation failed", "incorrect_password");
            throw new ForbiddenException("Your current password is incorrect");
        }
        requirePrivilegedStepUpAuditingRefusal(user.getId());
        String newEmail = normalizeEmail(newEmailRaw);
        validateRequest(user, newEmail);

        if (userMapper.lockById(user.getId()) == null) {
            throw new ForbiddenException("Your current password is incorrect");
        }
        User lockedUser = userMapper.getUserByIdForShare(user.getId());
        if (lockedUser == null || lockedUser.getSessionEpoch() == null
                || !Objects.equals(user.getPasswordHash(), lockedUser.getPasswordHash())
                || !Objects.equals(sessionEpoch, lockedUser.getSessionEpoch())) {
            throw new ForbiddenException("Your current password is incorrect");
        }
        userMapper.lockAssignedCustomRoleIds(user.getId());
        requirePrivilegedStepUp(user.getId());
        user = lockedUser;
        emailChangeTokenMapper.invalidateForUser(user.getId());
        validateRequest(user, newEmail);

        String rawToken = OneTimeTokenDigest.generate();
        emailChangeTokenMapper.insert(user.getId(), newEmail, OneTimeTokenDigest.sha256(rawToken),
            requestIp == null ? null : requestIp.address(), tokenExpiryMinutes, user.getSessionEpoch());
        emailChangeEmailService.sendVerificationEmail(user, newEmail, rawToken);

        auditService.record("user.email_change_requested", "user", user.getId(), user.getDisplayName(),
                "Requested a verified email change", null);
    }

    /**
     * Applies the privileged-account gate before the account lock, auditing a refusal. The audit
     * is an independent append, so it must not run while this transaction holds {@code app_user}.
     *
     * @param userId the requesting account
     */
    private void requirePrivilegedStepUpAuditingRefusal(int userId) {
        try {
            requirePrivilegedStepUp(userId);
        } catch (PasskeyEnrollmentRequiredException exception) {
            auditEmailChangeRefusal(userId, "privileged_mfa_enrollment_required");
            throw exception;
        } catch (RecentAuthenticationRequiredException exception) {
            auditEmailChangeRefusal(userId, "recent_authentication_required");
            throw exception;
        }
    }

    /**
     * Requires a privileged account to hold a passkey and a fresh WebAuthn step-up. Unprivileged
     * accounts pass unchanged. Only a WebAuthn ceremony writes the step-up stamp, so an account
     * that has never enrolled cannot satisfy it and is refused with the enrollment code instead.
     *
     * @param userId the requesting account
     */
    private void requirePrivilegedStepUp(int userId) {
        if (!privilegedAccountService.isPrivileged(userId)) {
            return;
        }
        if (!webAuthnService.hasPasskey(userId)) {
            throw new PasskeyEnrollmentRequiredException();
        }
        sessionSecurityService.requireRecentAuthentication(userId);
    }

    private void auditEmailChangeRefusal(int userId, String reason) {
        auditService.recordFailure("auth.email_change.refused", "user", userId, null,
                "Email change refused for a privileged account", reason);
    }

    private void validateRequest(User user, String newEmail) {
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
     * Claims the raw token for one browser and server-session lineage, after comparing its
     * issuance generation against the locked account's current session epoch.
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
        if (token == null || userMapper.lockById(token.getUserId()) == null) {
            throw invalidLink();
        }
        User user = userMapper.getUserByIdForShare(token.getUserId());
        if (user == null || token.getCredentialGeneration() == null
                || !token.getCredentialGeneration().equals(user.getSessionEpoch())) {
            throw invalidLink();
        }
        int claimed = emailChangeTokenMapper.claimExchange(tokenHash, exchangeOwnerHash);
        if (claimed != 1
                && emailChangeTokenMapper.lockExchangeOwnedBy(tokenHash, exchangeOwnerHash) == null) {
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
     * the token and invalidates outstanding email-change and password-reset tokens
     * under the account lock.
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
     * Applies an email change only while its issuance generation matches the locked account,
     * revoking pending grants addressed to the previous email. READ COMMITTED makes pending-grant
     * discovery current after waiting for the account lock, including grants committed while queued.
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

        if (userMapper.lockById(token.getUserId()) == null) {
            throw invalidLink();
        }

        User user = userMapper.getUserByIdForShare(token.getUserId());
        if (user == null || token.getCredentialGeneration() == null
                || !token.getCredentialGeneration().equals(user.getSessionEpoch())) {
            throw invalidLink();
        }

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
        passwordResetTokenMapper.invalidateForUser(user.getId());

        auditService.record("user.email_change_completed", "user", user.getId(), user.getDisplayName(),
                "Completed a verified email change", null);
        return revoked;
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
