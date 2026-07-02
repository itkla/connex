package ooo.klae.connex.backend.services;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

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

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

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

        String rawToken = generateToken();
        emailChangeTokenMapper.insert(user.getId(), newEmail, hashToken(rawToken), requestIp, tokenExpiryMinutes);
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
        return emailChangeTokenMapper.existsRedeemableByHash(hashToken(rawToken));
    }

    /**
     * Applies the pending email change bound to a redeemable token, then consumes
     * the token and invalidates the user's other outstanding email-change tokens.
     * Re-checks uniqueness at confirm time in case the address was claimed since.
     * @param rawToken the unhashed token from the verification link
     */
    @Transactional
    public void confirmChange(String rawToken) {
        String tokenHash = rawToken == null ? null : hashToken(rawToken);
        EmailChangeToken token = tokenHash == null ? null
                : emailChangeTokenMapper.findRedeemableByHash(tokenHash);
        if (token == null) {
            throw new BadRequestException("This verification link is invalid or has expired");
        }

        if (emailChangeTokenMapper.markConsumed(tokenHash) == 0) {
            throw new BadRequestException("This verification link is invalid or has expired");
        }

        User user = userMapper.getUserById(token.getUserId());
        if (user == null) {
            throw new BadRequestException("This verification link is invalid or has expired");
        }

        User existing = userMapper.getUserByEmail(token.getNewEmail());
        if (existing != null && existing.getId() != user.getId()) {
            throw new DuplicateResourceException("That email address is already in use");
        }

        userMapper.updateEmail(user.getId(), token.getNewEmail());
        emailChangeTokenMapper.invalidateForUser(user.getId());

        auditService.record("user.email_change_completed", "user", user.getId(), user.getDisplayName(),
                "Completed a verified email change", null);
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
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
