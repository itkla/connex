package ooo.klae.connex.backend.services;

import jakarta.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.config.PrivilegedMfaProperties;
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
 * <p>The requirement is evaluated independently of {@code privileged-mfa.enforced}, because a
 * first enrollment stamps the session as stepped-up whether or not confinement is on. The
 * confirmation is only as strong as the account address it is delivered to, so
 * {@code EmailChangeService.requestChange} refuses to move a privileged account's address without
 * an enrolled passkey and a fresh WebAuthn step-up, also independently of that flag. A stolen
 * password therefore cannot redirect the delivery address. Privilege granted to an account that
 * has never enrolled, and a passkey enrolled before a promotion, remain open (#1506, #1534). See
 * docs/PRIVILEGED_MFA.md.
 */
@Service
@RequiredArgsConstructor
public class PasskeyBootstrapConfirmationPolicy {

    private final PrivilegedAccountService privilegedAccountService;
    private final UserMapper userMapper;
    private final PrivilegedMfaProperties privilegedMfaProperties;

    @Value("${connex.security.privileged-mfa.bootstrap-confirmation.enabled:true}")
    private boolean confirmationEnabled;

    /** Refuses an unattributed exception before this policy can serve enrollment requests. */
    @PostConstruct
    public void validateConfiguration() {
        privilegedMfaProperties.validateBootstrapConfirmation(confirmationEnabled);
    }

    /** Returns the effective setting shared by enrollment enforcement and the startup audit. */
    public boolean isConfirmationEnabled() {
        return confirmationEnabled;
    }

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
