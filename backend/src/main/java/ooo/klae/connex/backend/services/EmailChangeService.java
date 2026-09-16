package ooo.klae.connex.backend.services;

import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.EmailChangeToken;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.DuplicateResourceException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;
import ooo.klae.connex.backend.mappers.EmailChangeTokenMapper;
import ooo.klae.connex.backend.mappers.PasswordResetTokenMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.util.ClientIpResolver.ResolvedClientIp;
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
    private final EmailChangeTokenMapper emailChangeTokenMapper;
    private final PasswordResetTokenMapper passwordResetTokenMapper;
    private final EmailChangeEmailService emailChangeEmailService;
    private final AuthService authService;
    private final SessionSecurityService sessionSecurityService;
    private final AuditService auditService;
    private final LoginRateLimiter loginRateLimiter;

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
     * @param newEmailRaw the requested new email address
     * @param currentPassword the caller's current password, verified before issuing
     * @param requestIp the resolved client address and trusted-proxy provenance, recorded for
     *     abuse audit and used by the shared confirmation throttle
     */
    @Transactional
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
        if (userMapper.lockById(user.getId()) == null) {
            throw new ForbiddenException("Your current password is incorrect");
        }
        User lockedUser = userMapper.getUserByIdForShare(user.getId());
        if (lockedUser == null || lockedUser.getSessionEpoch() == null
                || !Objects.equals(user.getPasswordHash(), lockedUser.getPasswordHash())
                || !Objects.equals(sessionEpoch, lockedUser.getSessionEpoch())) {
            throw new ForbiddenException("Your current password is incorrect");
        }
        user = lockedUser;
        String newEmail = normalizeEmail(newEmailRaw);
        if (newEmail.equalsIgnoreCase(normalizeEmail(user.getEmail()))) {
            throw new BadRequestException("That is already your email address");
        }
        if (userMapper.getUserByEmail(newEmail) != null) {
            throw new DuplicateResourceException("That email address is already in use");
        }
        if (emailChangeTokenMapper.countRecentByUser(user.getId(), requestWindowSeconds) >= maxRequests) {
            throw new BadRequestException("Too many email-change requests; please try again later");
        }

        emailChangeTokenMapper.invalidateForUser(user.getId());

        String rawToken = OneTimeTokenDigest.generate();
        emailChangeTokenMapper.insert(user.getId(), newEmail, OneTimeTokenDigest.sha256(rawToken),
            requestIp == null ? null : requestIp.address(), tokenExpiryMinutes, user.getSessionEpoch());
        emailChangeEmailService.sendVerificationEmail(user, newEmail, rawToken);

        auditService.record("user.email_change_requested", "user", user.getId(), user.getDisplayName(),
                "Requested a verified email change", null);
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
    @Transactional
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
     * @param rawToken the unhashed token from the verification link
     */
    @Transactional
    public void confirmChange(String rawToken) {
        String tokenHash = rawToken == null ? null : OneTimeTokenDigest.sha256(rawToken);
        confirmChangeByHash(exchangeToken(rawToken, programmaticExchangeOwner(tokenHash)));
    }

    /** Applies an email change only while its issuance generation matches the locked account. */
    @Transactional
    public void confirmChangeByHash(String tokenHash) {
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
                || !token.getCredentialGeneration().equals(user.getSessionEpoch())
                || emailChangeTokenMapper.markConsumed(tokenHash) == 0) {
            throw invalidLink();
        }

        User existing = userMapper.getUserByEmail(token.getNewEmail());
        if (existing != null && existing.getId() != user.getId()) {
            throw new DuplicateResourceException("That email address is already in use");
        }

        userMapper.updateEmail(user.getId(), token.getNewEmail());
        userMapper.markEmailVerified(user.getId());
        emailChangeTokenMapper.invalidateForUser(user.getId());
        passwordResetTokenMapper.invalidateForUser(user.getId());

        auditService.record("user.email_change_completed", "user", user.getId(), user.getDisplayName(),
                "Completed a verified email change", null);
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
