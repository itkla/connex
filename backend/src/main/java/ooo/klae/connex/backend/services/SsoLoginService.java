package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.FederatedIdentity;
import ooo.klae.connex.backend.beans.SsoConnection;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.FederatedIdentityMapper;
import ooo.klae.connex.backend.mappers.SsoConnectionMapper;
import ooo.klae.connex.backend.mappers.UserMapper;

/**
 * The federation core of SSO login: maps a verified IdP identity to a Connex account
 * and provisions one when appropriate. Called from the OAuth2 success handler once the
 * IdP has authenticated the user.
 *
 * <p>The resolution is security-critical and follows a strict order:
 * <ol>
 *   <li>a known {@code (provider, issuer, subject)} link signs the linked user straight in;</li>
 *   <li>a <em>verified</em> IdP email that matches an existing password account never
 *       auto-links — it returns {@link SsoLoginResult.LinkRequired} so ownership can be
 *       proven first;</li>
 *   <li>otherwise the user is found (an existing SSO account) or provisioned fresh, JIT-joined
 *       to the connection's workspace behind the domain allow-list, and the identity is recorded.</li>
 * </ol>
 * The target workspace, organization, and default role are read only from the stored
 * {@link SsoConnection}; nothing about the destination is taken from the caller.
 */
@Service
@RequiredArgsConstructor
public class SsoLoginService {

    private static final String FALLBACK_USERNAME = "user";
    private static final int MAX_USERNAME_LENGTH = 64;

    private final FederatedIdentityMapper federatedIdentityMapper;
    private final UserMapper userMapper;
    private final SsoConnectionMapper ssoConnectionMapper;
    private final AllowedDomainService allowedDomainService;
    private final WorkspaceService workspaceService;

    /**
     * Resolves an authenticated IdP identity to a Connex login outcome.
     * @param provider the IdP protocol (e.g. {@code oidc})
     * @param issuer the IdP issuer (OIDC {@code iss})
     * @param subject the stable IdP subject (OIDC {@code sub})
     * @param email the IdP-asserted email address
     * @param emailVerified whether the IdP asserts the email is verified
     * @param orgId the organization whose connection minted this login
     * @param displayName the IdP-asserted display name, used when provisioning
     * @return a login outcome, or a link-required outcome when the email collides with a password account
     * @throws ForbiddenException when the email's domain is not permitted to join the connection's workspace
     * @throws BadRequestException when the organization has no SSO connection
     */
    @Transactional
    public SsoLoginResult resolve(String provider, String issuer, String subject, String email,
            boolean emailVerified, int orgId, String displayName) {
        FederatedIdentity identity = federatedIdentityMapper.findByProviderIssuerSubject(provider, issuer, subject);
        if (identity != null) {
            federatedIdentityMapper.touchLogin(identity.getId());
            return SsoLoginResult.login(userMapper.getUserById(identity.getUserId()));
        }

        if (emailVerified) {
            User byEmail = userMapper.getUserByEmail(email);
            if (byEmail != null && byEmail.getPassword() != null) {
                return SsoLoginResult.linkRequired(byEmail.getId(), provider, issuer, subject, orgId);
            }
        }

        SsoConnection connection = ssoConnectionMapper.findByOrg(orgId);
        if (connection == null) {
            throw new BadRequestException("SSO is not configured for this organization");
        }
        int jitWorkspaceId = connection.getJitWorkspaceId();

        if (!allowedDomainService.isJoinAllowed(jitWorkspaceId, email)) {
            throw new ForbiddenException("Your email domain is not permitted to sign in to this organization");
        }

        User user = userMapper.getUserByEmail(email);
        if (user == null) {
            user = provisionUser(email, emailVerified, displayName);
        } else if (user.getPassword() != null) {
            return SsoLoginResult.linkRequired(user.getId(), provider, issuer, subject, orgId);
        }

        workspaceService.ensureActiveMember(jitWorkspaceId, user.getId(), connection.getDefaultRole());

        FederatedIdentity link = new FederatedIdentity();
        link.setUserId(user.getId());
        link.setOrgId(orgId);
        link.setProvider(provider);
        link.setIssuer(issuer);
        link.setExternalSubject(subject);
        federatedIdentityMapper.insert(link);

        return SsoLoginResult.login(user);
    }

    private User provisionUser(String email, boolean emailVerified, String displayName) {
        User user = new User();
        user.setUsername(deriveUsername(email));
        user.setDisplayName(resolveDisplayName(displayName, email));
        user.setEmail(email);
        user.setEmailVerified(emailVerified);
        user.setTimezone("UTC");
        user.setPasswordHash(null);
        userMapper.insert(user);
        return user;
    }

    private static String resolveDisplayName(String displayName, String email) {
        return displayName != null && !displayName.isBlank() ? displayName.trim() : email;
    }

    private String deriveUsername(String email) {
        int at = email.indexOf('@');
        String local = at > 0 ? email.substring(0, at) : email;
        String base = local.toLowerCase().replaceAll("[^a-z0-9._-]", "");
        if (base.isEmpty()) {
            base = FALLBACK_USERNAME;
        }
        if (base.length() > MAX_USERNAME_LENGTH) {
            base = base.substring(0, MAX_USERNAME_LENGTH);
        }
        String candidate = base;
        int suffix = 1;
        while (userMapper.getUserByUsername(candidate) != null) {
            String tag = Integer.toString(suffix++);
            int keep = MAX_USERNAME_LENGTH - tag.length();
            candidate = (base.length() > keep ? base.substring(0, keep) : base) + tag;
        }
        return candidate;
    }
}
