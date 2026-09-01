package ooo.klae.connex.backend.services;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.PasskeyBootstrapConfirmationToken;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.MailTransportUnavailableException;
import ooo.klae.connex.backend.mappers.PasskeyBootstrapConfirmationTokenMapper;
import ooo.klae.connex.backend.mappers.SpringSessionMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.session.SessionEpochRestampGrant;
import ooo.klae.connex.backend.util.OneTimeTokenDigest;
import ooo.klae.connex.backend.webauthn.WebAuthnService;

/**
 * Issues and redeems the out-of-band confirmation required to enroll a FIRST passkey on an
 * account that currently holds privilege.
 *
 * <p>An unenrolled privileged account is confined to the enrollment endpoints, so enrollment is
 * the only reachable door and a stolen password was previously enough to walk through it and
 * receive a step-up stamp. This service adds a second, out-of-band factor: a single-use bearer
 * delivered to the account's own mailbox.
 *
 * <p>The requirement is deliberately independent of {@code privileged-mfa.enforced}. That flag
 * governs confinement, but the escalation it defends against — a first enrollment stamping the
 * session as stepped-up — exists whether or not confinement is switched on.
 *
 * <p>Redemption is bound to the requesting session's stable store-row identity as well as to the
 * account. Binding to the account alone would let an attacker holding the password request a
 * confirmation and have the legitimate owner's click authorize the attacker's waiting session.
 */
@Service
@RequiredArgsConstructor
public class PasskeyBootstrapConfirmationService {

    private final PasskeyBootstrapConfirmationTokenMapper tokenMapper;
    private final PasskeyBootstrapConfirmationEmailService emailService;
    private final PasskeyBootstrapConfirmationPolicy policy;
    private final WebAuthnService webAuthnService;
    private final SpringSessionMapper springSessionMapper;
    private final UserMapper userMapper;
    private final SessionSecurityService sessionSecurityService;
    private final AuditService auditService;

    @Value("${connex.security.privileged-mfa.bootstrap-confirmation.token-expiry-minutes:30}")
    private int tokenExpiryMinutes;

    @Value("${connex.security.privileged-mfa.bootstrap-confirmation.request-window-seconds:900}")
    private int requestWindowSeconds;

    @Value("${connex.security.privileged-mfa.bootstrap-confirmation.max-requests:5}")
    private int maxRequests;

    /**
     * Whether enrolling a first passkey on this account requires an emailed confirmation.
     *
     * @param userId the account attempting enrollment
     * @return true when the account currently holds privilege and has no passkey yet
     */
    public boolean isRequiredFor(int userId) {
        return !webAuthnService.hasPasskey(userId) && policy.requiresConfirmation(userId);
    }

    /**
     * Whether this session already carries an acceptable out-of-band proof for first enrollment.
     *
     * <p>A redeemed emailed confirmation is the ordinary route. A session holding the durable
     * recovery epoch-restamp grant is also accepted: operator-authorized break-glass recovery
     * already supplied an out-of-band factor, and the grant survives until the replacement
     * credential commits. Without that second route, an account whose credentials recovery just
     * removed could be left unable to re-enroll at all once a short-lived stamp lapsed, because
     * recovery refuses an account that has no credential to remove.
     *
     * @param user the authenticated account
     * @param httpRequest the current request
     * @return true when enrollment may proceed without a further confirmation
     */
    public boolean isSatisfiedFor(User user, HttpServletRequest httpRequest) {
        if (sessionSecurityService.hasFreshPasskeyBootstrapConfirmation(httpRequest, user.getId())) {
            return true;
        }
        SessionEpochRestampGrant grant = userMapper.epochRestampGrant(user.getId());
        if (grant == null) {
            return false;
        }
        HttpSession session = httpRequest.getSession(false);
        if (session == null) {
            return false;
        }
        String primaryId = springSessionMapper.primaryIdBySessionId(session.getId());
        return primaryId != null && primaryId.equals(grant.sessionPrimaryId());
    }

    /**
     * Issues a single-use confirmation for the caller and mails the link to the account address.
     * Any outstanding confirmation is invalidated first, so only one is ever live.
     *
     * @param user the authenticated account
     * @param httpRequest the current request, whose session the confirmation is bound to
     * @param requestIp the resolved client address, recorded for abuse audit
     */
    @Transactional
    public void request(User user, HttpServletRequest httpRequest, String requestIp) {
        if (!isRequiredFor(user.getId())) {
            throw new BadRequestException("No enrollment confirmation is required for this account");
        }
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            throw new BadRequestException("This account has no email address to confirm with");
        }
        if (!emailService.canDeliver()) {
            throw new MailTransportUnavailableException(
                    "This instance cannot send the enrollment confirmation email");
        }
        String sessionPrimaryId = requireSessionPrimaryId(httpRequest);
        if (tokenMapper.countRecentByUser(user.getId(), requestWindowSeconds) >= maxRequests) {
            throw new BadRequestException(
                    "Too many enrollment confirmation requests; please try again later");
        }
        tokenMapper.invalidateForUser(user.getId());

        String rawToken = OneTimeTokenDigest.generate();
        tokenMapper.insert(user.getId(), OneTimeTokenDigest.sha256(rawToken), sessionPrimaryId,
                requestIp, tokenExpiryMinutes);
        afterCommit(() -> emailService.sendConfirmationEmail(user, rawToken));

        auditService.recordStrictScoped(
                "auth.passkey.bootstrap_confirmation.requested",
                "user",
                user.getId(),
                null,
                null,
                user.getDisplayName(),
                "First-passkey enrollment confirmation requested",
                Map.of("expiryMinutes", tokenExpiryMinutes));
    }

    /**
     * Redeems an emailed confirmation for the caller and stamps the current session.
     *
     * <p>The single {@code markConsumed} statement matches on the digest, the account and the
     * requesting session together, so a mismatch on any of them consumes nothing and two
     * concurrent redemptions cannot both succeed.
     *
     * @param user the authenticated account
     * @param rawToken the bearer carried in the emailed link fragment
     * @param httpRequest the current request, which must be the session that requested it
     */
    @Transactional
    public void redeem(User user, String rawToken, HttpServletRequest httpRequest) {
        if (rawToken == null || rawToken.isBlank()) {
            throw invalidLink(user);
        }
        String sessionPrimaryId = requireSessionPrimaryId(httpRequest);
        String tokenHash = OneTimeTokenDigest.sha256(rawToken);
        PasskeyBootstrapConfirmationToken token = tokenMapper.findRedeemableByHash(tokenHash);
        if (token == null
                || token.getUserId() != user.getId()
                || !OneTimeTokenDigest.constantTimeEquals(
                        token.getSessionPrimaryId(), sessionPrimaryId)) {
            throw invalidLink(user);
        }
        if (tokenMapper.markConsumed(tokenHash, user.getId(), sessionPrimaryId) != 1) {
            throw invalidLink(user);
        }
        afterCommit(() -> sessionSecurityService.markPasskeyBootstrapConfirmation(
                httpRequest, user.getId()));
        auditService.recordStrictScoped(
                "auth.passkey.bootstrap_confirmation.redeemed",
                "user",
                user.getId(),
                null,
                null,
                user.getDisplayName(),
                "First-passkey enrollment confirmation redeemed",
                null);
    }

    /**
     * Invalidates every outstanding confirmation for an account.
     *
     * @param userId the account whose confirmations are being retired
     */
    @Transactional
    public void invalidateForUser(int userId) {
        tokenMapper.invalidateForUser(userId);
    }

    /**
     * Defers an effect until the surrounding transaction commits.
     *
     * <p>Both the emailed bearer and the session stamp must describe committed state. Dispatching
     * mail inside the transaction can hand out a link for a row a later rollback removes, and
     * stamping the session before commit can leave a session that looks confirmed while the
     * consumption was rolled back, which would leave the token replayable.
     *
     * @param effect work to run only once the transaction has committed
     */
    private static void afterCommit(Runnable effect) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            effect.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                effect.run();
            }
        });
    }

    private String requireSessionPrimaryId(HttpServletRequest httpRequest) {
        HttpSession session = httpRequest.getSession(false);
        if (session == null) {
            throw new ForbiddenException("Authenticated session required");
        }
        String primaryId = springSessionMapper.primaryIdBySessionId(session.getId());
        if (primaryId == null) {
            throw new ForbiddenException("Authenticated session required");
        }
        return primaryId;
    }

    private BadRequestException invalidLink(User user) {
        auditService.recordStrictFailureIndependentScoped(
                "auth.passkey.bootstrap_confirmation.denied",
                "user",
                user.getId(),
                null,
                null,
                user.getDisplayName(),
                "First-passkey enrollment confirmation rejected",
                "invalid_confirmation");
        return new BadRequestException("This confirmation link is invalid or has expired");
    }
}
