package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.Objects;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.FederatedIdentity;
import ooo.klae.connex.backend.beans.SsoConnection;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.WorkspaceLifecycleRef;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.FederatedIdentityMapper;
import ooo.klae.connex.backend.mappers.SsoConnectionMapper;
import ooo.klae.connex.backend.mappers.SsoDomainMapper;
import ooo.klae.connex.backend.mappers.TenantLifecycleControlMapper;
import ooo.klae.connex.backend.mappers.UserMapper;

/**
 * The federation core of SSO login: maps a verified IdP identity to a Connex account
 * and provisions one when appropriate. Called from the OAuth2 success handler once the
 * IdP has authenticated the user.
 *
 * <p>The resolution is security-critical and org-scoped throughout:
 * <ol>
 *   <li>a known {@code (org, provider, issuer, subject)} link signs the linked user straight in —
 *       the lookup is scoped to the organization, so one org's IdP cannot match another's identity;</li>
 *   <li>otherwise the IdP email must be <em>verified</em> and its domain must be one this
 *       organization owns in its {@code sso_domain} routing list (globally unique), binding the
 *       asserted email to the org so no IdP can assert or claim an address belonging to another org,
 *       and — when the org has set an {@code org_allowed_domain} membership ceiling (#316) — the
 *       domain must also satisfy that ceiling, so SSO cannot provision a member the org's own policy
 *       forbids;</li>
 *   <li>a matching existing password account never auto-links — it returns
 *       {@link SsoLoginResult.LinkRequired} so ownership is proven first, and a passwordless account
 *       already federated to a different organization is refused;</li>
 *   <li>a new email is JIT-provisioned into the connection's workspace and the identity recorded.</li>
 * </ol>
 * The target workspace, organization, and default role are read only from the stored
 * {@link SsoConnection}; nothing about the destination is taken from the caller.
 *
 * <p>Returning identities pin their stored user before the organization. Existing-account JIT
 * resolution pins the stored user, JIT workspace, and organization in that order. A new JIT user
 * has no user root to lock, so its stored workspace and organization are pinned before the
 * account is inserted. Every path revalidates its discovered identity or connection after those
 * locks, preventing federation side effects across a lifecycle fence.
 *
 * <p>JIT routing is discovered before its transaction opens. The target workspace placement is
 * then pinned by {@link FreshMembershipTransaction}, so a dedicated-catalog membership cleanup and
 * the control-plane identity writes share one correctly routed transaction.
 */
@Service
@RequiredArgsConstructor
public class SsoLoginService {

    private final FederatedIdentityMapper federatedIdentityMapper;
    private final UserMapper userMapper;
    private final SsoConnectionMapper ssoConnectionMapper;
    private final SsoDomainMapper ssoDomainMapper;
    private final TenantLifecycleControlMapper tenantLifecycleControlMapper;
    private final WorkspaceService workspaceService;
    private final OrgAllowedDomainService orgAllowedDomainService;
    private final SsoUserProvisioner ssoUserProvisioner;
    private final AuditService auditService;
    private final FreshMembershipTransaction freshMembershipTransaction;
    private final TransactionTemplate transactionTemplate;

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
     * @throws ForbiddenException when the organization is being removed, the email is unverified,
     *         its domain is not owned by this organization, or the account is already federated to
     *         a different organization
     * @throws BadRequestException when the organization has no SSO connection
     */
    public SsoLoginResult resolve(String provider, String issuer, String subject, String email,
            boolean emailVerified, int orgId, String displayName) {
        FederatedIdentity identity = federatedIdentityMapper.findByOrgProviderIssuerSubject(
                orgId, provider, issuer, subject);
        if (identity != null) {
            SsoLoginResult result = transactionTemplate.execute(status ->
                resolveReturningIdentity(identity, provider, issuer, subject, orgId));
            return Objects.requireNonNull(result, "returning SSO login result");
        }

        requireProvisioningAllowed(email, emailVerified, orgId);
        SsoConnection connection = requireSsoConnection(orgId);
        int jitWorkspaceId = requireJitWorkspace(connection);
        return freshMembershipTransaction.execute(
            jitWorkspaceId,
            () -> resolveFreshIdentity(
                provider,
                issuer,
                subject,
                email,
                emailVerified,
                orgId,
                displayName,
                jitWorkspaceId));
    }

    private SsoLoginResult resolveFreshIdentity(
            String provider,
            String issuer,
            String subject,
            String email,
            boolean emailVerified,
            int orgId,
            String displayName,
            int expectedJitWorkspaceId) {
        FederatedIdentity identity = federatedIdentityMapper.findByOrgProviderIssuerSubject(
                orgId, provider, issuer, subject);
        if (identity != null) {
            return resolveReturningIdentity(identity, provider, issuer, subject, orgId);
        }

        requireProvisioningAllowed(email, emailVerified, orgId);
        SsoConnection connection = requireSsoConnection(orgId);
        int jitWorkspaceId = requireJitWorkspace(connection);
        if (jitWorkspaceId != expectedJitWorkspaceId) {
            throw new ForbiddenException(
                    "SSO provisioning is unavailable while its target workspace is being removed");
        }

        User user = userMapper.getUserByEmail(email);
        if (user != null) {
            user = lockExistingJitRoots(
                user,
                connection,
                provider,
                issuer,
                subject,
                email,
                orgId);
            if (user.getPassword() != null) {
                return SsoLoginResult.linkRequired(user.getId(), provider, issuer, subject, orgId);
            }
            if (federatedIdentityMapper.countByUserIdExcludingOrg(user.getId(), orgId) > 0) {
                throw new ForbiddenException("This account is managed by a different organization");
            }
        } else {
            lockNewJitRoots(
                connection,
                provider,
                issuer,
                subject,
                email,
                orgId);
            user = ssoUserProvisioner.provision(email, displayName, true);
            auditService.record("org.sso_user.provision", "organization", orgId, user.getDisplayName(),
                    "SSO user provisioned", Map.of("userId", user.getId(), "provider", provider));
        }

        workspaceService.ensureActiveMember(jitWorkspaceId, user.getId(),
                connection.getDefaultRole());

        FederatedIdentity link = new FederatedIdentity();
        link.setUserId(user.getId());
        link.setOrgId(orgId);
        link.setProvider(provider);
        link.setIssuer(issuer);
        link.setExternalSubject(subject);
        federatedIdentityMapper.insert(link);
        auditService.record("org.federated_identity.link", "organization", orgId, user.getDisplayName(),
                "Federated identity linked", Map.of("userId", user.getId(), "provider", provider));

        return SsoLoginResult.login(user);
    }

    private void requireProvisioningAllowed(String email, boolean emailVerified, int orgId) {
        if (!emailVerified) {
            throw new ForbiddenException(
                    "Your identity provider did not assert a verified email address");
        }
        if (!isDomainAuthorizedForOrg(email, orgId)) {
            throw new ForbiddenException(
                    "Your email domain is not permitted to sign in to this organization");
        }
        if (!orgAllowedDomainService.isJoinAllowed(orgId, email)) {
            throw new ForbiddenException(
                    "Your email domain is not permitted to sign in to this organization");
        }
    }

    private SsoConnection requireSsoConnection(int orgId) {
        SsoConnection connection = ssoConnectionMapper.findByOrg(orgId);
        if (connection == null) {
            throw new BadRequestException("SSO is not configured for this organization");
        }
        return connection;
    }

    private int requireJitWorkspace(SsoConnection connection) {
        Integer jitWorkspaceId = connection.getJitWorkspaceId();
        if (jitWorkspaceId == null) {
            throw new ForbiddenException(
                    "SSO provisioning is unavailable while its target workspace is being removed");
        }
        return jitWorkspaceId;
    }

    private SsoLoginResult resolveReturningIdentity(
            FederatedIdentity discovered,
            String provider,
            String issuer,
            String subject,
            int orgId) {
        User user = userMapper.getUserByIdForShare(discovered.getUserId());
        if (user == null) {
            throw new ForbiddenException("This organization is not accepting sign-ins");
        }
        requireActiveOrganization(orgId);
        FederatedIdentity current = federatedIdentityMapper.findByOrgProviderIssuerSubject(
            orgId,
            provider,
            issuer,
            subject);
        if (!discovered.equals(current) || current.getUserId() != user.getId()) {
            throw new ForbiddenException("This organization is not accepting sign-ins");
        }
        federatedIdentityMapper.touchLogin(current.getId());
        return SsoLoginResult.login(user);
    }

    private User lockExistingJitRoots(
            User discovered,
            SsoConnection connection,
            String provider,
            String issuer,
            String subject,
            String email,
            int orgId) {
        User user = userMapper.getUserByIdForShare(discovered.getId());
        if (user == null) {
            throw new ForbiddenException("SSO account resolution changed; retry sign-in");
        }
        lockJitWorkspace(connection, orgId);
        requireActiveOrganization(orgId);
        requireJitStateUnchanged(connection, provider, issuer, subject, email, orgId);
        User currentByEmail = userMapper.getUserByEmail(email);
        if (currentByEmail == null || currentByEmail.getId() != user.getId()) {
            throw new ForbiddenException("SSO account resolution changed; retry sign-in");
        }
        return user;
    }

    private void lockNewJitRoots(
            SsoConnection connection,
            String provider,
            String issuer,
            String subject,
            String email,
            int orgId) {
        lockJitWorkspace(connection, orgId);
        requireActiveOrganization(orgId);
        requireJitStateUnchanged(connection, provider, issuer, subject, email, orgId);
        if (userMapper.getUserByEmail(email) != null) {
            throw new ForbiddenException("SSO account resolution changed; retry sign-in");
        }
    }

    private void lockJitWorkspace(SsoConnection connection, int orgId) {
        Integer workspaceId = connection.getJitWorkspaceId();
        WorkspaceLifecycleRef workspace = workspaceId == null
            ? null
            : tenantLifecycleControlMapper.lockWorkspaceForShare(workspaceId);
        if (workspace == null
                || workspace.orgId() != orgId
                || !"active".equals(workspace.lifecycleState())) {
            throw new ForbiddenException(
                "SSO provisioning is unavailable while its target workspace is being removed");
        }
    }

    private void requireJitStateUnchanged(
            SsoConnection discovered,
            String provider,
            String issuer,
            String subject,
            String email,
            int orgId) {
        SsoConnection current = ssoConnectionMapper.findByOrg(orgId);
        if (!discovered.equals(current)
                || federatedIdentityMapper.findByProviderIssuerSubject(
                    provider,
                    issuer,
                    subject) != null
                || !isDomainAuthorizedForOrg(email, orgId)
                || !orgAllowedDomainService.isJoinAllowedForShare(orgId, email)) {
            throw new ForbiddenException("SSO account resolution changed; retry sign-in");
        }
    }

    private void requireActiveOrganization(int orgId) {
        if (tenantLifecycleControlMapper.lockActiveOrganizationForShare(orgId) == null) {
            throw new ForbiddenException("This organization is not accepting sign-ins");
        }
    }

    private boolean isDomainAuthorizedForOrg(String email, int orgId) {
        if (email == null) {
            return false;
        }
        int at = email.lastIndexOf('@');
        if (at < 0 || at == email.length() - 1) {
            return false;
        }
        String domain = email.substring(at + 1).trim().toLowerCase();
        if (domain.isEmpty()) {
            return false;
        }
        Integer owner = ssoDomainMapper.findOrgByDomain(domain);
        return owner != null && owner == orgId;
    }
}
