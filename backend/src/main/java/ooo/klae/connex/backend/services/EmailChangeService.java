package ooo.klae.connex.backend.services;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.EmailChangeToken;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.DuplicateResourceException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.EmailChangeTokenMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
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
     * @param newEmailRaw the requested new email address
     * @param currentPassword the caller's current password, verified before issuing
     * @param requestIp the requesting client IP, recorded for abuse audit
     */
    @Transactional
    public void requestChange(String newEmailRaw, String currentPassword, String requestIp) {
        User user = authService.getCurrentUser();
        if (currentPassword == null || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new ForbiddenException("Your current password is incorrect");
        }
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
        emailChangeTokenMapper.insert(
            user.getId(), newEmail, OneTimeTokenDigest.sha256(rawToken), requestIp, tokenExpiryMinutes);
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

    /** Atomically claims the raw emailed token for its one browser exchange. */
    @Transactional
    public String exchangeToken(String rawToken) {
        String tokenHash = rawToken == null || rawToken.isBlank()
            ? null
            : OneTimeTokenDigest.sha256(rawToken);
        if (tokenHash == null || emailChangeTokenMapper.claimExchange(tokenHash) != 1) {
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
     * @param rawToken the unhashed token from the verification link
     */
    @Transactional
    public void confirmChange(String rawToken) {
        confirmChangeByHash(exchangeToken(rawToken));
    }

    /** Applies an email change through a purpose-bound flow-session source digest. */
    @Transactional
    public void confirmChangeByHash(String tokenHash) {
        EmailChangeToken token = tokenHash == null ? null
                : emailChangeTokenMapper.findExchangedRedeemableByHash(tokenHash);
        if (token == null) {
            throw invalidLink();
        }

        if (emailChangeTokenMapper.markConsumed(tokenHash) == 0) {
            throw invalidLink();
        }

        User user = userMapper.getUserById(token.getUserId());
        if (user == null) {
            throw invalidLink();
        }

        User existing = userMapper.getUserByEmail(token.getNewEmail());
        if (existing != null && existing.getId() != user.getId()) {
            throw new DuplicateResourceException("That email address is already in use");
        }

        userMapper.updateEmail(user.getId(), token.getNewEmail());
        // Redeeming this token proved control of the new address, so the account is email-verified
        // — the same trust signal registration verification records, shared across both flows.
        userMapper.markEmailVerified(user.getId());
        emailChangeTokenMapper.invalidateForUser(user.getId());

        auditService.record("user.email_change_completed", "user", user.getId(), user.getDisplayName(),
                "Completed a verified email change", null);
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private static BadRequestException invalidLink() {
        return new BadRequestException("This verification link is invalid or has expired");
    }
}
