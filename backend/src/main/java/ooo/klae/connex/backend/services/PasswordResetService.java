package ooo.klae.connex.backend.services;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.PasswordResetToken;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.SsoEnforcedException;
import ooo.klae.connex.backend.mappers.PasswordResetTokenMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.password.PasswordCredentialService;
import ooo.klae.connex.backend.password.PasswordScreening;
import ooo.klae.connex.backend.password.PasswordScreeningFlow;
import ooo.klae.connex.backend.util.OneTimeTokenDigest;

/**
 * Drives the forgot-password flow: issuing single-use reset tokens, validating
 * them, and applying a new password. Users are global, so nothing here is
 * workspace-scoped. Only the SHA-256 hash of a token is persisted; the raw token
 * is delivered by {@link PasswordResetEmailService} and never stored or returned.
 */
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private final UserMapper userMapper;
    private final PasswordResetTokenMapper passwordResetTokenMapper;
    private final PasswordCredentialService passwordCredentialService;
    private final PasswordResetEmailService passwordResetEmailService;
    private final PasswordResetRateLimiter rateLimiter;
    private final AuditService auditService;
    private final AccountSessionRevocationService accountSessionRevocationService;
    private final SsoConnectionService ssoConnectionService;

    @Value("${connex.password-reset.token-expiry-minutes:30}")
    private int tokenExpiryMinutes;

    @Value("${connex.password-reset.request-window-seconds:900}")
    private int requestWindowSeconds;

    @Value("${connex.password-reset.max-requests:5}")
    private int maxRequests;

    /**
     * Issues a reset token for the account with the given email and emails the link.
     * Enumeration-safe: returns identically whether or not the email exists, and
     * silently drops requests that exceed the per-account rate limit.
     * @param email the address that requested a reset
     * @param requestIp the requesting client IP, recorded for abuse audit
     */
    @Transactional
    public void requestReset(String email, String requestIp) {
        if (!rateLimiter.tryAcquire(requestIp, System.currentTimeMillis())) {
            auditService.record("auth.password_reset_throttled", "user", null, requestIp,
                    "Password reset requests throttled for this client", null);
            return;
        }

        User user = userMapper.getUserByEmail(email);
        if (user == null) {
            return;
        }
        if (ssoConnectionService.isSsoEnforcedForUser(user.getId())) {
            auditService.record("auth.password_reset_sso_enforced", "user", user.getId(), user.getDisplayName(),
                    "Password reset suppressed; SSO enforced", null);
            return;
        }
        if (passwordResetTokenMapper.countRecentByUser(user.getId(), requestWindowSeconds) >= maxRequests) {
            auditService.record("auth.password_reset_throttled", "user", user.getId(), user.getDisplayName(),
                    "Password reset request throttled", null);
            return;
        }

        passwordResetTokenMapper.invalidateForUser(user.getId());

        String rawToken = OneTimeTokenDigest.generate();
        passwordResetTokenMapper.insert(
            user.getId(), OneTimeTokenDigest.sha256(rawToken), requestIp, tokenExpiryMinutes);
        passwordResetEmailService.sendResetEmail(user, rawToken);

        auditService.record("auth.password_reset_requested", "user", user.getId(), user.getDisplayName(),
                "Password reset link requested", null);
    }

    /**
     * Reports whether a raw token is currently redeemable (unconsumed and unexpired).
     * @param rawToken the unhashed token from the reset link
     * @return true when the token can still be used to reset a password
     */
    public boolean validateToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return false;
        }
        return passwordResetTokenMapper.existsRedeemableByHash(OneTimeTokenDigest.sha256(rawToken));
    }

    /**
     * Claims the raw emailed token for one browser and server-session lineage. A retry from that
     * lineage remains valid until the source token expires; every other lineage is refused.
     * @param rawToken token carried in the fragment-to-body bootstrap request
     * @param exchangeOwnerHash one-way owner of the browser and server-session exchange
     * @return persisted source-token digest for the purpose-bound flow session
     */
    @Transactional
    public String exchangeToken(String rawToken, String exchangeOwnerHash) {
        String tokenHash = rawToken == null || rawToken.isBlank()
            ? null
            : OneTimeTokenDigest.sha256(rawToken);
        if (tokenHash == null || exchangeOwnerHash == null || exchangeOwnerHash.isBlank()) {
            throw invalidLink();
        }
        int claimed = passwordResetTokenMapper.claimExchange(tokenHash, exchangeOwnerHash);
        if (claimed != 1
                && !passwordResetTokenMapper.isExchangeOwnedBy(tokenHash, exchangeOwnerHash)) {
            throw invalidLink();
        }
        return tokenHash;
    }

    /** @return whether an exchanged source digest is still redeemable */
    public boolean validateExchangedTokenHash(String tokenHash) {
        return tokenHash != null
            && passwordResetTokenMapper.existsExchangedRedeemableByHash(tokenHash);
    }

    /**
     * Applies a new password using a redeemable token, then consumes the token,
     * invalidates the user's other outstanding tokens, and expires their live
     * sessions so the reset locks out anyone holding the old credentials.
     * @param rawToken the unhashed token from the reset link
     * @param newPassword the already policy-validated new password
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void resetPassword(String rawToken, String newPassword) {
        String tokenHash = rawToken == null ? null : OneTimeTokenDigest.sha256(rawToken);
        resetPasswordByHash(
            exchangeToken(rawToken, programmaticExchangeOwner(tokenHash)), newPassword);
    }

    /**
     * Screens a proposed reset password before any flow transaction is opened.
     *
     * <p>Under the default {@code REMOTE} source the lookup makes bounded HTTP requests. Callers
     * enter this flow through {@code OneTimeLinkFlowService}, which claims the grant row in its own
     * transaction, so screening from inside it would hold that row and a pooled connection for the
     * duration of an upstream stall.
     *
     * @param newPassword the already policy-validated new password
     * @return the screening to hand back to {@link #resetPasswordByHash(String, String,
     *         PasswordScreening)}
     */
    public PasswordScreening screenForReset(String newPassword) {
        return passwordCredentialService.screen(newPassword, PasswordScreeningFlow.SELF_SERVICE_RESET);
    }

    /** Screens and applies a new password through a purpose-bound browser-flow source digest. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void resetPasswordByHash(String tokenHash, String newPassword) {
        resetPasswordByHash(tokenHash, newPassword, screenForReset(newPassword));
    }

    /**
     * Applies a new password using a screening the caller performed outside this transaction.
     *
     * @param tokenHash the exchanged source-token digest
     * @param newPassword the already policy-validated new password
     * @param screening the result of {@link #screenForReset}
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void resetPasswordByHash(String tokenHash, String newPassword,
            PasswordScreening screening) {
        PasswordResetToken token = tokenHash == null ? null
                : passwordResetTokenMapper.findExchangedRedeemableByHash(tokenHash);
        if (token == null) {
            throw invalidLink();
        }

        User user = userMapper.getUserById(token.getUserId());
        if (user == null) {
            throw invalidLink();
        }
        if (ssoConnectionService.isSsoEnforcedForUser(user.getId())) {
            throw new SsoEnforcedException();
        }

        if (userMapper.lockById(token.getUserId()) == null) {
            throw invalidLink();
        }
        userMapper.lockAssignedCustomRoleIds(token.getUserId());
        String passwordHash = passwordCredentialService.encodeScreened(
                screening, newPassword, PasswordScreeningFlow.SELF_SERVICE_RESET, user.getId());
        if (passwordResetTokenMapper.markConsumed(tokenHash) == 0) {
            throw new BadRequestException("This reset link is invalid or has expired");
        }

        userMapper.updatePasswordHash(user.getId(), passwordHash);
        passwordResetTokenMapper.invalidateForUser(user.getId());
        expireSessions(user);

        auditService.record("auth.password_reset_completed", "user", user.getId(), user.getDisplayName(),
                "Password reset completed", null);
    }

    /**
     * Expires every session the user holds across the shared session store, forcing
     * re-authentication everywhere after a password reset. The Spring Session-backed
     * registry enumerates by the immutable account id, which
     * {@code AccountSessionIndexResolver} writes into the session index on every save.
     * @param user the user whose sessions should be invalidated
     */
    private void expireSessions(User user) {
        accountSessionRevocationService.expireAll(user.getId());
    }

    private static BadRequestException invalidLink() {
        return new BadRequestException("This reset link is invalid or has expired");
    }

    private static String programmaticExchangeOwner(String tokenHash) {
        return tokenHash == null ? "" : OneTimeTokenDigest.sha256("password-reset:" + tokenHash);
    }
}
