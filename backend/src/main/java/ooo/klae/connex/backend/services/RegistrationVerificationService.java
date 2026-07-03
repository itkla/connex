package ooo.klae.connex.backend.services;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.RegistrationVerificationToken;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mappers.RegistrationVerificationTokenMapper;
import ooo.klae.connex.backend.mappers.UserMapper;

/**
 * Proves that a newly-registered account controls its email address. Registration issues a
 * single-use token delivered to the account's own address; redeeming it marks the account
 * {@code email_verified}. This is what makes the workspace domain allow-list trustworthy: a
 * shareable, domain-restricted invite link only admits addresses whose ownership has been
 * proven, so it cannot be satisfied by an unverified (spoofable) registration email.
 *
 * <p>Gated by {@code connex.registration-verification.enabled}; when disabled, no tokens are
 * issued and the redeem gate does not apply. Only the SHA-256 hash of a token is persisted;
 * the raw token is delivered by {@link RegistrationVerificationEmailService} and never stored.
 */
@Service
@RequiredArgsConstructor
public class RegistrationVerificationService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private final UserMapper userMapper;
    private final RegistrationVerificationTokenMapper tokenMapper;
    private final RegistrationVerificationEmailService emailService;
    private final AuditService auditService;

    @Value("${connex.registration-verification.enabled:false}")
    private boolean enabled;

    @Value("${connex.registration-verification.token-expiry-minutes:1440}")
    private int tokenExpiryMinutes;

    @Value("${connex.registration-verification.request-window-seconds:900}")
    private int requestWindowSeconds;

    @Value("${connex.registration-verification.max-requests:5}")
    private int maxRequests;

    /** @return whether registration email verification is enabled for this instance */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Issues a verification token for the user and emails the link to their address. No-op when
     * the feature is disabled or the user is already verified. Silently drops requests over the
     * per-account rate limit. Called at registration and by the authenticated resend endpoint.
     * @param user the account to verify
     * @param requestIp the requesting client IP, recorded for abuse audit
     */
    @Transactional
    public void issue(User user, String requestIp) {
        if (!enabled || user == null || user.isEmailVerified()) {
            return;
        }
        if (tokenMapper.countRecentByUser(user.getId(), requestWindowSeconds) >= maxRequests) {
            return;
        }
        tokenMapper.invalidateForUser(user.getId());

        String rawToken = generateToken();
        tokenMapper.insert(user.getId(), hashToken(rawToken), requestIp, tokenExpiryMinutes);
        emailService.sendVerificationEmail(user, rawToken);

        auditService.record("user.email_verification_requested", "user", user.getId(), user.getDisplayName(),
                "Requested an email verification link", null);
    }

    /**
     * Reports whether a raw token is currently redeemable (unconsumed and unexpired).
     * @param rawToken the unhashed token from the verification link
     * @return true when the token can still be used
     */
    public boolean validateToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return false;
        }
        return tokenMapper.existsRedeemableByHash(hashToken(rawToken));
    }

    /**
     * Marks the account behind a redeemable token verified, then consumes the token and
     * invalidates the user's other outstanding verification tokens.
     * @param rawToken the unhashed token from the verification link
     */
    @Transactional
    public void confirm(String rawToken) {
        String tokenHash = rawToken == null ? null : hashToken(rawToken);
        RegistrationVerificationToken token = tokenHash == null ? null
                : tokenMapper.findRedeemableByHash(tokenHash);
        if (token == null) {
            throw new BadRequestException("This verification link is invalid or has expired");
        }
        if (tokenMapper.markConsumed(tokenHash) == 0) {
            throw new BadRequestException("This verification link is invalid or has expired");
        }
        User user = userMapper.getUserById(token.getUserId());
        if (user == null) {
            throw new BadRequestException("This verification link is invalid or has expired");
        }
        userMapper.markEmailVerified(user.getId());
        tokenMapper.invalidateForUser(user.getId());

        auditService.record("user.email_verified", "user", user.getId(), user.getDisplayName(),
                "Verified their email address", null);
    }

    private static String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

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
