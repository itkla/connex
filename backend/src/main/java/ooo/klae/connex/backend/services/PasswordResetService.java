package ooo.klae.connex.backend.services;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
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

/**
 * Drives the forgot-password flow: issuing single-use reset tokens, validating
 * them, and applying a new password. Users are global, so nothing here is
 * workspace-scoped. Only the SHA-256 hash of a token is persisted; the raw token
 * is delivered by {@link PasswordResetEmailService} and never stored or returned.
 */
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

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

        String rawToken = generateToken();
        passwordResetTokenMapper.insert(user.getId(), hashToken(rawToken), requestIp, tokenExpiryMinutes);
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
        return passwordResetTokenMapper.existsRedeemableByHash(hashToken(rawToken));
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
        String tokenHash = rawToken == null ? null : hashToken(rawToken);
        PasswordResetToken token = tokenHash == null ? null
                : passwordResetTokenMapper.findRedeemableByHash(tokenHash);
        if (token == null) {
            throw new BadRequestException("This reset link is invalid or has expired");
        }

        if (passwordResetTokenMapper.markConsumed(tokenHash) == 0) {
            throw new BadRequestException("This reset link is invalid or has expired");
        }

        User user = userMapper.getUserById(token.getUserId());
        if (user == null) {
            throw new BadRequestException("This reset link is invalid or has expired");
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

    /**
     * Generates a 256-bit URL-safe random token.
     * @return the raw token
     */
    private static String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Computes the SHA-256 hex digest used to store and look up a token.
     * @param rawToken the unhashed token
     * @return the lowercase hex digest
     */
    private static String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
