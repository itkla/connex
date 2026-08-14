package ooo.klae.connex.backend.services;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.RegistrationVerificationToken;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mappers.RegistrationVerificationTokenMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.util.OneTimeTokenDigest;

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

        String rawToken = OneTimeTokenDigest.generate();
        tokenMapper.insert(
            user.getId(), OneTimeTokenDigest.sha256(rawToken), requestIp, tokenExpiryMinutes);
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
        return tokenMapper.existsRedeemableByHash(OneTimeTokenDigest.sha256(rawToken));
    }

    /** Atomically claims the raw emailed token for its one browser exchange. */
    @Transactional
    public String exchangeToken(String rawToken) {
        String tokenHash = rawToken == null || rawToken.isBlank()
            ? null
            : OneTimeTokenDigest.sha256(rawToken);
        if (tokenHash == null || tokenMapper.claimExchange(tokenHash) != 1) {
            throw invalidLink();
        }
        return tokenHash;
    }

    /** @return whether an exchanged source digest is still redeemable */
    public boolean validateExchangedTokenHash(String tokenHash) {
        return tokenHash != null && tokenMapper.existsExchangedRedeemableByHash(tokenHash);
    }

    /**
     * Marks the account behind a redeemable token verified, then consumes the token and
     * invalidates the user's other outstanding verification tokens.
     * @param rawToken the unhashed token from the verification link
     */
    @Transactional
    public void confirm(String rawToken) {
        confirmByHash(exchangeToken(rawToken));
    }

    /** Verifies an account through a purpose-bound flow-session source digest. */
    @Transactional
    public void confirmByHash(String tokenHash) {
        RegistrationVerificationToken token = tokenHash == null ? null
                : tokenMapper.findExchangedRedeemableByHash(tokenHash);
        if (token == null) {
            throw invalidLink();
        }
        if (tokenMapper.markConsumed(tokenHash) == 0) {
            throw invalidLink();
        }
        User user = userMapper.getUserById(token.getUserId());
        if (user == null) {
            throw invalidLink();
        }
        userMapper.markEmailVerified(user.getId());
        tokenMapper.invalidateForUser(user.getId());

        auditService.record("user.email_verified", "user", user.getId(), user.getDisplayName(),
                "Verified their email address", null);
    }

    private static BadRequestException invalidLink() {
        return new BadRequestException("This verification link is invalid or has expired");
    }
}
