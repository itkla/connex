package ooo.klae.connex.backend.services;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mappers.UserMapper;

/**
 * Decides whether enrolling a FIRST passkey needs an out-of-band emailed confirmation (#1506).
 *
 * <p>The predicate is shared by the endpoint that issues registration options and by the
 * under-lock fence in {@code WebAuthnService.finishRegistration}, so a promotion that lands
 * between the two phases cannot slip an unconfirmed enrollment through. It deliberately takes no
 * credential-store dependency, which keeps it usable from inside the WebAuthn service without a
 * bean cycle.
 *
 * <p>Only password-backed accounts are covered. A passwordless account proves bootstrap with a
 * freshly established, same-account federated session rather than a replayable secret, so it is
 * not the population a stolen password endangers; requiring mail from it would add lockout risk
 * for no gain.
 *
 * <p>The requirement is independent of {@code privileged-mfa.enforced}. That flag governs
 * confinement, but a first enrollment stamps the session as stepped-up either way.
 */
@Service
@RequiredArgsConstructor
public class PasskeyBootstrapConfirmationPolicy {

    private final PrivilegedAccountService privilegedAccountService;
    private final UserMapper userMapper;

    @Value("${connex.security.privileged-mfa.bootstrap-confirmation.enabled:true}")
    private boolean confirmationEnabled;

    /**
     * Whether a first-passkey enrollment by this account must carry an emailed confirmation.
     *
     * @param userId the account enrolling its first passkey
     * @return true when the account is password-backed and currently holds privilege
     */
    public boolean requiresConfirmation(int userId) {
        if (!confirmationEnabled) {
            return false;
        }
        User user = userMapper.getUserById(userId);
        if (user == null || user.getPassword() == null) {
            return false;
        }
        return privilegedAccountService.isPrivileged(userId);
    }
}
