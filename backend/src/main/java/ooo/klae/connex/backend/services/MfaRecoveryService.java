package ooo.klae.connex.backend.services;

import java.time.Clock;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.HttpServletRequest;
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
    }
}
