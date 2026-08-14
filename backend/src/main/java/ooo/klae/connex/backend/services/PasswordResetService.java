package ooo.klae.connex.backend.services;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.PasswordResetToken;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.SsoEnforcedException;
import ooo.klae.connex.backend.mappers.PasswordResetTokenMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
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
    private final PasswordEncoder passwordEncoder;
    private final PasswordResetEmailService passwordResetEmailService;
    private final PasswordResetRateLimiter rateLimiter;
    private final AuditService auditService;
    private final SessionRegistry sessionRegistry;
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
     * Atomically claims the raw emailed token for one browser session. A retry from that same
     * session remains valid until the source token expires; every other session is refused.
     * @param rawToken token carried in the fragment-to-body bootstrap request
     * @param exchangeSessionHash one-way owner of the browser exchange
     * @return persisted source-token digest for the purpose-bound flow session
     */
    @Transactional
    public String exchangeToken(String rawToken, String exchangeSessionHash) {
        String tokenHash = rawToken == null || rawToken.isBlank()
            ? null
            : OneTimeTokenDigest.sha256(rawToken);
        if (tokenHash == null || exchangeSessionHash == null || exchangeSessionHash.isBlank()) {
            throw invalidLink();
        }
        int claimed = passwordResetTokenMapper.claimExchange(tokenHash, exchangeSessionHash);
        if (claimed != 1
                && !passwordResetTokenMapper.isExchangeOwnedBy(tokenHash, exchangeSessionHash)) {
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
    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        String tokenHash = rawToken == null ? null : OneTimeTokenDigest.sha256(rawToken);
        resetPasswordByHash(
            exchangeToken(rawToken, programmaticExchangeOwner(tokenHash)), newPassword);
    }

    /** Applies a new password through a purpose-bound flow-session source digest. */
    @Transactional
    public void resetPasswordByHash(String tokenHash, String newPassword) {
        PasswordResetToken token = tokenHash == null ? null
                : passwordResetTokenMapper.findExchangedRedeemableByHash(tokenHash);
        if (token == null) {
            throw invalidLink();
        }

        if (passwordResetTokenMapper.markConsumed(tokenHash) == 0) {
            throw invalidLink();
        }

        User user = userMapper.getUserById(token.getUserId());
        if (user == null) {
            throw invalidLink();
        }
        if (ssoConnectionService.isSsoEnforcedForUser(user.getId())) {
            throw new SsoEnforcedException();
        }

        userMapper.updatePasswordHash(user.getId(), passwordEncoder.encode(newPassword));
        passwordResetTokenMapper.invalidateForUser(user.getId());
        expireSessions(user);

        auditService.record("auth.password_reset_completed", "user", user.getId(), user.getDisplayName(),
                "Password reset completed", null);
    }

    /**
     * Expires every session the user holds across the shared session store, forcing
     * re-authentication everywhere after a password reset. The Spring Session-backed
     * registry resolves sessions by principal name (the username), so the {@code User}
     * (a {@code UserDetails}) is looked up directly rather than by scanning all principals.
     * @param user the user whose sessions should be invalidated
     */
    private void expireSessions(User user) {
        for (SessionInformation session : sessionRegistry.getAllSessions(user, false)) {
            session.expireNow();
        }
    }

    private static BadRequestException invalidLink() {
        return new BadRequestException("This reset link is invalid or has expired");
    }

    private static String programmaticExchangeOwner(String tokenHash) {
        return tokenHash == null ? "" : OneTimeTokenDigest.sha256("password-reset:" + tokenHash);
    }
}
