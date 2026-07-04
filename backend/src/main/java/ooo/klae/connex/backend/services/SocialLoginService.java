package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.FederatedIdentity;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.FederatedIdentityMapper;
import ooo.klae.connex.backend.mappers.UserMapper;

/**
 * The federation core of consumer social login (Sign in with Google / Microsoft) — the
 * instance-wide counterpart of {@link SsoLoginService}. Unlike enterprise SSO, the social
 * provider is a single trusted client this instance owns, so only the real owner of a
 * Google/Microsoft account can present its identity; there is no per-org routing and the
 * stored {@link FederatedIdentity} carries a {@code null} org.
 *
 * <p>The resolution stays safe by matching only on the stable, provider-verified identity and
 * never adopting an existing account on a bare email match (the nOAuth account-takeover class):
 * <ol>
 *   <li>a known {@code (provider, issuer, subject)} link signs the linked user in;</li>
 *   <li>a <em>verified</em> provider email is required (the caller asserts this — Google's
 *       {@code email_verified}, Microsoft's {@code xms_edov});</li>
 *   <li>an existing <em>password</em> account is never auto-linked — it returns
 *       {@link SsoLoginResult.LinkRequired} so ownership is proven first;</li>
 *   <li>an existing <em>passwordless</em> account (another provider, or an SSO-/JIT-provisioned
 *       account) is refused rather than silently merged by email — there is no ownership proof;</li>
 *   <li>only a brand-new email is provisioned as a fresh passwordless personal account (the caller
 *       then routes it through onboarding);</li>
 *   <li>an account whose organization enforces SSO is refused — it must use that org's IdP.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class SocialLoginService {

    private final FederatedIdentityMapper federatedIdentityMapper;
    private final UserMapper userMapper;
    private final SsoUserProvisioner ssoUserProvisioner;
    private final SsoConnectionService ssoConnectionService;

    /**
     * Resolves an authenticated social identity to a Connex login outcome.
     * @param provider the social provider ({@code google} or {@code microsoft})
     * @param issuer the provider's token issuer
     * @param subject the stable provider subject
     * @param email the provider-asserted email
     * @param emailVerified whether the provider asserts the email is verified
     * @param displayName the provider-asserted display name, used when provisioning
     * @return a login outcome, or a link-required outcome when the email collides with a password account
     * @throws ForbiddenException when the email is unverified or the account is SSO-enforced
     */
    @Transactional
    public SsoLoginResult resolve(String provider, String issuer, String subject, String email,
            boolean emailVerified, String displayName) {
        FederatedIdentity identity = federatedIdentityMapper.findByProviderIssuerSubject(provider, issuer, subject);
        if (identity != null) {
            User linked = userMapper.getUserById(identity.getUserId());
            requireNotSsoEnforced(linked);
            federatedIdentityMapper.touchLogin(identity.getId());
            return SsoLoginResult.login(linked);
        }

        if (!emailVerified) {
            throw new ForbiddenException("Your provider did not confirm a verified email address");
        }

        User existing = userMapper.getUserByEmail(email);
        if (existing != null) {
            requireNotSsoEnforced(existing);
            if (existing.getPassword() != null) {
                return SsoLoginResult.linkRequired(existing.getId(), provider, issuer, subject, null);
            }
            throw new ForbiddenException(
                    "An account already exists for this email. Sign in with the method you first used.");
        }

        User user = ssoUserProvisioner.provision(email, displayName, true);
        FederatedIdentity link = new FederatedIdentity();
        link.setUserId(user.getId());
        link.setOrgId(null);
        link.setProvider(provider);
        link.setIssuer(issuer);
        link.setExternalSubject(subject);
        federatedIdentityMapper.insert(link);

        return SsoLoginResult.login(user);
    }

    private void requireNotSsoEnforced(User user) {
        if (ssoConnectionService.isSsoEnforcedForUser(user.getId())) {
            throw new ForbiddenException(
                    "This account must sign in through your organization's identity provider");
        }
    }
}
