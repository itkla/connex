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
import ooo.klae.connex.backend.exceptions.ForbiddenException;
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
     * @return the new account session epoch committed by recovery
     */
    @Transactional
    public int recover(PasskeyRecoveryRequest request, HttpServletRequest httpRequest) {
        HttpSession ceremonySession = httpRequest.getSession(false);
        if (ceremonySession == null) {
            throw new ForbiddenException("Authenticated session required");
        }
        String ceremonySessionId = ceremonySession.getId();
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
        if (userMapper.bumpSessionEpoch(user.getId()) != 1) {
            throw new IllegalStateException("Session epoch advance failed");
        }
        Integer newEpoch = userMapper.currentSessionEpoch(user.getId());
        if (newEpoch == null) {
            throw new IllegalStateException("Advanced session epoch is unavailable");
        }
        if (userMapper.grantEpochRestamp(user.getId(), ceremonySessionId, newEpoch) != 1) {
            throw new IllegalStateException("Session epoch restamp grant failed");
        }
        sessionSecurityService.clearRecentAuthentication(httpRequest);
        expireOtherSessions(user, ceremonySessionId);
        return newEpoch;
    }

    /**
     * Expires every session the account holds apart from the one completing the ceremony.
     *
     * <p>Recovery advances the account session epoch and durably grants this logical session the
     * right to adopt the new value after commit. The controller performs the ordinary post-commit
     * stamp; if that session write is lost, the epoch filter can repeat the stamp only for the
     * session id and epoch recorded by the locked recovery transaction. The repeatable handoff
     * keeps a passwordless account with no remaining credential from being permanently locked out.
     *
     * <p>A passwordless account proves bootstrap with a fresh authenticated session rather than a
     * password, so a session that predates recovery would otherwise be able to enroll the
     * replacement passkey through the confinement-allowed registration endpoints. Only the session
     * that satisfied the operator-authorized proof survives to do that. Enumeration is retained to
     * expire every session it can see immediately; the epoch is the fail-closed backstop for a
     * session whose row is written after that enumeration.
     *
     * @param user the recovering account
     * @param ceremonySessionId the logical session id completing the ceremony
     */
    private void expireOtherSessions(User user, String ceremonySessionId) {
        accountSessionRevocationService.expireAllExcept(user.getId(), ceremonySessionId);
    }
}
