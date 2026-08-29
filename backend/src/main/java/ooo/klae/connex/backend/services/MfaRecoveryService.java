package ooo.klae.connex.backend.services;

import java.time.Clock;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.config.PrivilegedMfaProperties;
import ooo.klae.connex.backend.dto.PasskeyRecoveryRequest;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.webauthn.WebAuthnService;

/**
 * Executes the fail-closed, audited passkey recovery ceremony.
 */
@Service
@RequiredArgsConstructor
public class MfaRecoveryService {
    private final AuthService authService;
    private final UserMapper userMapper;
    private final WebAuthnService webAuthnService;
    private final SessionSecurityService sessionSecurityService;
    private final PrivilegedMfaProperties privilegedMfaProperties;
    private final AuditService auditService;
    private final AccountSessionRevocationService accountSessionRevocationService;
    private final Clock clock;

    /**
     * Verifies the account and operator proofs, removes inaccessible credentials, and appends the
     * audit record in the same transaction.
     *
     * @param request submitted recovery proofs
     * @param httpRequest authenticated servlet request
     */
    @Transactional
    public void recover(PasskeyRecoveryRequest request, HttpServletRequest httpRequest) {
        User user = authService.getCurrentUser();
        if (userMapper.lockById(user.getId()) == null) {
            throw new ResourceNotFoundException("Not authenticated");
        }
        authService.requireFirstPasskeyBootstrapAuthentication(
                user.getId(), request.getCurrentPassword(), httpRequest);
        String operator = privilegedMfaProperties.requireValidRecoveryToken(
                request.getRecoveryToken(), clock);
        int removed = webAuthnService.recover(user.getId());
        auditService.recordStrictScoped(
                "auth.mfa.recovery.used",
                "user",
                user.getId(),
                null,
                null,
                user.getDisplayName(),
                "Operator-authorized passkey recovery used",
                Map.of("operator", operator, "credentialsRemoved", removed));
        sessionSecurityService.clearRecentAuthentication(httpRequest);
        expireOtherSessions(user, httpRequest);
    }

    /**
     * Expires every session the account holds apart from the one completing the ceremony.
     *
     * <p>This deliberately does not advance the account session epoch, unlike password reset. For a
     * passwordless account the retained session is the only way to enroll the replacement passkey —
     * the credentials are already gone — so it would have to be re-stamped after the transaction
     * commits. Session attributes are written when the request completes, so a failure in that
     * window would leave the sole usable session stamped behind an advanced epoch, permanently
     * refused, with no credential left to sign in and fix it. Recovery therefore keeps enumeration
     * alone and carries its fail-open residual, rather than trading it for a fail-shut one.
     *
     * <p>A passwordless account proves bootstrap with a fresh authenticated session rather than a
     * password, so a session that predates recovery would otherwise be able to enroll the
     * replacement passkey through the confinement-allowed registration endpoints. Only the session
     * that satisfied the operator-authorized proof survives to do that.
     *
     * @param user the recovering account
     * @param httpRequest the request completing the ceremony
     */
    private void expireOtherSessions(User user, HttpServletRequest httpRequest) {
        HttpSession current = httpRequest.getSession(false);
        accountSessionRevocationService.expireAllExcept(
                user.getId(), current == null ? null : current.getId());
    }
}
