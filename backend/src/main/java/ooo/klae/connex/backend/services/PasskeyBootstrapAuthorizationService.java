package ooo.klae.connex.backend.services;

import java.util.Map;

import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.config.PrivilegedMfaProperties;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.PrivilegedBootstrapForbiddenException;

/**
 * Decides whether a first passkey may be enrolled on the strength of the account's own password.
 *
 * <p>A password alone must not enroll the second factor for an account that administers other
 * principals, because that is precisely the escalation the privileged MFA policy exists to prevent:
 * a stolen password would otherwise yield an attacker-held authenticator and, through it, role
 * changes, invitations, provider secrets, and exports.
 *
 * <p>An account that administers nobody but itself is deliberately still allowed to enroll with its
 * password. Every self-serve registration provisions its own workspace and owner membership in the
 * same transaction, so such an account is privileged from the instant it exists and holds no other
 * credential; refusing it would make first enrollment impossible without an operator. The exclusion
 * cannot be manufactured: refusal is evaluated across every scope the account holds privilege in, so
 * acquiring a further sole-member scope can only lose the exclusion, never earn it.
 */
@Service
@RequiredArgsConstructor
public class PasskeyBootstrapAuthorizationService {
    private final PrivilegedMfaProperties privilegedMfaProperties;
    private final PrivilegedAccountService privilegedAccountService;
    private final AuthService authService;
    private final MfaRecoveryService mfaRecoveryService;
    private final AuditService auditService;

    /**
     * The authorization that lets this account begin first-passkey enrollment.
     */
    private enum Authorization {
        /** The policy does not apply: unenforced, passwordless, or not a privileged account. */
        NOT_APPLICABLE,
        /** Privileged, but administers no other principal. */
        SOLE_PRINCIPAL,
        /** Holds an operator-authorized recovery grant bound to this ceremony session. */
        OPERATOR_RECOVERY,
        /** Administers other principals and offered only a password. */
        REFUSED
    }

    /**
     * Refuses first-passkey enrollment that rests on the account's password alone when the account
     * administers other principals.
     *
     * <p>Called after the account proof has already been verified, so a caller who does not hold the
     * password never reaches it and the refusal cannot be used to probe which accounts are
     * privileged. It deliberately does not live in
     * {@link AuthService#requireFirstPasskeyBootstrapAuthentication}: the recovery ceremony calls
     * that method before validating the operator token, so a refusal there would also refuse the
     * ceremony that is supposed to resolve it.
     *
     * @param user the authenticated account enrolling its first passkey
     * @param httpRequest the authenticated servlet request carrying the ceremony session
     * @throws PrivilegedBootstrapForbiddenException when only a password backs the enrollment
     */
    public void requireFirstPasskeyBootstrapAuthorization(User user, HttpServletRequest httpRequest) {
        Authorization authorization = evaluate(user.getId(), httpRequest);
        if (authorization == Authorization.NOT_APPLICABLE) {
            return;
        }
        if (authorization == Authorization.REFUSED) {
            auditService.recordStrictFailureIndependentScoped(
                    "auth.passkey.bootstrap.denied",
                    "user",
                    user.getId(),
                    null,
                    null,
                    user.getDisplayName(),
                    "Privileged first-passkey enrollment refused",
                    "privileged_bootstrap_unauthorized");
            throw new PrivilegedBootstrapForbiddenException(
                    "This account administers other members; enrolling its first passkey needs more "
                            + "than a password. Ask an administrator to remove that authority while you "
                            + "enroll, or an operator for a recovery authorization.");
        }
        auditService.recordStrictScoped(
                "auth.passkey.bootstrap.authorized",
                "user",
                user.getId(),
                null,
                null,
                user.getDisplayName(),
                "Privileged first-passkey enrollment authorized",
                Map.of("grant", authorization == Authorization.SOLE_PRINCIPAL
                        ? "sole_principal"
                        : "operator_recovery"));
    }

    /**
     * Whether this account currently needs an authorization beyond its password to enroll a first
     * passkey, so the enrollment screen can say so before the attempt.
     *
     * @param user the authenticated account
     * @param httpRequest the authenticated servlet request carrying the ceremony session
     * @return whether an operator recovery authorization is required
     */
    public boolean operatorAuthorizationRequired(User user, HttpServletRequest httpRequest) {
        return evaluate(user.getId(), httpRequest) == Authorization.REFUSED;
    }

    private Authorization evaluate(int userId, HttpServletRequest httpRequest) {
        if (!privilegedMfaProperties.isEnforced()
                || !authService.hasPasswordCredential(userId)
                || !privilegedAccountService.isPrivileged(userId)) {
            return Authorization.NOT_APPLICABLE;
        }
        if (!privilegedAccountService.hasPrivilegeOverOtherAccounts(userId)) {
            return Authorization.SOLE_PRINCIPAL;
        }
        if (mfaRecoveryService.hasOutstandingRecoveryGrant(userId, httpRequest)) {
            return Authorization.OPERATOR_RECOVERY;
        }
        return Authorization.REFUSED;
    }
}
