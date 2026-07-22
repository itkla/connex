package ooo.klae.connex.backend.services;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.SsoConnection;
import ooo.klae.connex.backend.dto.SsoConnectionDto;
import ooo.klae.connex.backend.dto.SsoConnectionRequest;
import ooo.klae.connex.backend.dto.SsoDiscoveryDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.SsoConnectionMapper;
import ooo.klae.connex.backend.mappers.SsoDomainMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.sso.DbClientRegistrationRepository;
import ooo.klae.connex.backend.sso.DbRelyingPartyRegistrationRepository;
import ooo.klae.connex.backend.sso.SamlSpCredentialFactory;
import ooo.klae.connex.backend.sso.SamlSpKeyMaterial;
import ooo.klae.connex.backend.sso.SsoProperties;
import ooo.klae.connex.backend.sso.SsoSecretCipher;
import ooo.klae.connex.backend.sso.SsoUrlSafety;

/**
 * Org-administrator management of an organization's SSO/IdP connection. SSO is
 * org-scoped configuration, so read and upsert require org admin/owner
 * ({@code OrgMemberService}) on the acting workspace's organization — not a
 * workspace-level permission, which a workspace owner could otherwise obtain by
 * creating a workspace inside the org (#316). One connection per organization.
 * The OIDC client secret is encrypted at rest and never returned; a blank secret
 * on save keeps the stored one. Email-domain routing is replaced wholesale on each
 * save. The org-scoped accessors and domain lookup back the login flow.
 */
@Service
@RequiredArgsConstructor
public class SsoConnectionService {

    private static final String DEFAULT_SCOPES = "openid,email,profile";
    private static final String DEFAULT_ROLE = "member";
    private static final String REGISTRATION_PREFIX = "org-";
    private static final Set<String> ALLOWED_ROLES = Set.of("member", "admin");

    private final SsoConnectionMapper ssoConnectionMapper;
    private final SsoDomainMapper ssoDomainMapper;
    private final WorkspaceMapper workspaceMapper;
    private final OrganizationMapper organizationMapper;
    private final UserMapper userMapper;
    private final OrgMemberService orgMemberService;
    private final SsoSecretCipher ssoSecretCipher;
    private final SamlSpCredentialFactory samlSpCredentialFactory;
    private final SsoProperties ssoProperties;
    private final AuditService auditService;
    private final DbClientRegistrationRepository dbClientRegistrationRepository;
    private final DbRelyingPartyRegistrationRepository dbRelyingPartyRegistrationRepository;
    private final SessionSecurityService sessionSecurityService;

    /**
     * Returns the SSO connection for the acting workspace's organization, with the
     * client secret omitted.
     * @param workspaceId the acting workspace
     * @param actorId the requesting user
     * @return the connection view (unconfigured default when none exists)
     */
    public SsoConnectionDto getForWorkspace(int workspaceId, int actorId) {
        int orgId = requireAdministrableOrg(workspaceId, actorId);
        return SsoConnectionDto.from(ssoConnectionMapper.findByOrg(orgId), ssoDomainMapper.listByOrg(orgId));
    }

    /**
     * Resolves the workspace's organization and requires the actor to administer it.
     * A workspace the actor cannot administer and a workspace that does not exist
     * both yield {@link ForbiddenException}, so the endpoint is not a workspace-id
     * existence oracle for an authenticated non-member.
     */
    private int requireAdministrableOrg(int workspaceId, int actorId) {
        Integer orgId = workspaceMapper.getOrgId(workspaceId);
        if (orgId == null) {
            throw new ForbiddenException("Requires an organization administrator role");
        }
        orgMemberService.requireOrgAdmin(orgId, actorId);
        return orgId;
    }

    /**
     * Creates or updates the SSO connection for the acting workspace's organization.
     * Validates the protocol's required fields when enabled, encrypts a supplied
     * client secret (preserving the stored one when blank), and replaces the org's
     * routing domains. The JIT workspace must belong to the same organization.
     * @param workspaceId the acting workspace
     * @param actorId the requesting user
     * @param request the submitted connection
     * @return the saved connection view
     */
    @Transactional
    public SsoConnectionDto save(int workspaceId, int actorId, SsoConnectionRequest request) {
        if (userMapper.lockByIdForShare(actorId) == null) {
            throw new ForbiddenException("Requires an organization administrator role");
        }
        int orgId = lockAdministrableOrg(workspaceId, request.getJitWorkspaceId(), actorId);
        sessionSecurityService.requireRecentAuthentication(actorId);
        if (!ssoProperties.isEnabled()) {
            throw new BadRequestException("Single sign-on is not enabled on this instance");
        }

        String protocol = request.getProtocol().trim().toLowerCase();
        String role = resolveRole(request.getDefaultRole());
        SsoConnection existing = ssoConnectionMapper.findByOrg(orgId);
        if (request.isEnabled()) {
            validateEnabled(protocol, request, existing);
        }

        SsoConnection connection = new SsoConnection();
        connection.setOrgId(orgId);
        connection.setProtocol(protocol);
        connection.setEnabled(request.isEnabled());
        connection.setEnforceSso(request.isEnforceSso());
        connection.setJitWorkspaceId(request.getJitWorkspaceId());
        connection.setDefaultRole(role);
        connection.setOidcIssuer(trimToNull(request.getOidcIssuer()));
        connection.setOidcClientId(trimToNull(request.getOidcClientId()));
        connection.setOidcScopes(resolveScopes(request.getOidcScopes()));
        connection.setSamlIdpEntityId(trimToNull(request.getSamlIdpEntityId()));
        connection.setSamlSsoUrl(trimToNull(request.getSamlSsoUrl()));
        connection.setSamlIdpMetadataXml(trimToNull(request.getSamlIdpMetadataXml()));
        connection.setSamlIdpX509(trimToNull(request.getSamlIdpX509()));
        if ("oidc".equals(protocol)) {
            if (!isBlank(request.getOidcClientSecret())) {
                connection.setOidcClientSecretEnc(
                        ssoSecretCipher.encryptOidcClientSecret(orgId, request.getOidcClientSecret().trim()));
            } else if (existing != null && "oidc".equals(existing.getProtocol())) {
                connection.setOidcClientSecretEnc(existing.getOidcClientSecretEnc());
            }
        }
        if ("saml".equals(protocol)) {
            ensureSamlSpCredential(connection, existing, orgId);
        }

        ssoConnectionMapper.upsert(connection);
        deleteReplacedSecretReferences(existing, connection);
        replaceDomains(orgId, request.getDomains());
        dbClientRegistrationRepository.evict(orgId);
        dbRelyingPartyRegistrationRepository.evict(orgId);
        auditService.record("org.sso_config.save", "organization", orgId, protocol,
                "Updated SSO configuration", null);
        return getForWorkspace(workspaceId, actorId);
    }

    private int lockAdministrableOrg(int workspaceId, Integer jitWorkspaceId, int actorId) {
        if (jitWorkspaceId == null) {
            throw new BadRequestException("The provisioning workspace must belong to this organization");
        }
        TreeSet<Integer> workspaceIds = new TreeSet<>();
        workspaceIds.add(workspaceId);
        workspaceIds.add(jitWorkspaceId);
        for (int lockedWorkspaceId : workspaceIds) {
            if (workspaceMapper.lockWorkspaceForShare(lockedWorkspaceId) == null) {
                if (lockedWorkspaceId == workspaceId) {
                    throw new ForbiddenException("Requires an organization administrator role");
                }
                throw new BadRequestException("The provisioning workspace must belong to this organization");
            }
        }
        Integer orgId = workspaceMapper.getOrgId(workspaceId);
        Integer jitOrgId = workspaceMapper.getOrgId(jitWorkspaceId);
        if (orgId == null || !orgId.equals(jitOrgId)) {
            throw new BadRequestException("The provisioning workspace must belong to this organization");
        }
        if (organizationMapper.lockById(orgId) == null) {
            throw new ForbiddenException("Requires an organization administrator role");
        }
        orgMemberService.requireOrgAdminForUpdate(orgId, actorId);
        return orgId;
    }

    /**
     * The SSO connection for an organization, or null when none is configured.
     * Org-scoped accessor for the login flow; callers own their own gating.
     * @param orgId the organization
     * @return the connection, or null
     */
    public SsoConnection findByOrg(int orgId) {
        return ssoConnectionMapper.findByOrg(orgId);
    }

    /**
     * The enabled SSO connection for an organization.
     * @param orgId the organization
     * @return the enabled connection
     * @throws BadRequestException when no connection exists or it is disabled
     */
    public SsoConnection requireEnabledByOrg(int orgId) {
        SsoConnection connection = ssoConnectionMapper.findByOrg(orgId);
        if (connection == null || !connection.isEnabled()) {
            throw new BadRequestException("SSO is not enabled for this organization");
        }
        return connection;
    }

    /**
     * Whether SSO is mandatory for a user: true when the user is an active member of any
     * workspace whose organization has an enabled connection with {@code enforce_sso}. Such
     * a user must sign in through the IdP, so password login, passkey login, and forgot-password
     * are all refused for them. Membership in even one enforcing organization is enough.
     * @param userId the user to check
     * @return true when at least one active membership sits in an enforcing organization
     */
    public boolean isSsoEnforcedForUser(int userId) {
        return ssoProperties.isEnabled() && workspaceMapper.countEnforcingSsoMemberships(userId) > 0;
    }

    /**
     * Whether the SSO subsystem is enabled on this instance. Public and unauthenticated so the
     * login screen can decide whether to offer an SSO affordance at all.
     * @return true when {@code connex.sso.enabled} is set
     */
    public boolean isInstanceEnabled() {
        return ssoProperties.isEnabled();
    }

    /**
     * Pre-login IdP discovery for the login screen: maps an email's domain to its
     * organization and reports whether that org has an <em>enabled</em> SSO connection the
     * browser can start, along with the registration to navigate to and whether password
     * login is disabled. Reveals only domain-level routing — never whether an account exists
     * — and performs no network calls. An unmapped domain or a disabled connection is
     * reported as unavailable.
     * @param email the email typed on the login screen
     * @return the discovery result, unavailable when the domain has no enabled connection
     */
    public SsoDiscoveryDto discoverByEmail(String email) {
        if (!ssoProperties.isEnabled()) {
            return SsoDiscoveryDto.unavailable();
        }
        Integer orgId = findOrgByDomain(email);
        if (orgId == null) {
            return SsoDiscoveryDto.unavailable();
        }
        SsoConnection connection = ssoConnectionMapper.findByOrg(orgId);
        if (connection == null || !connection.isEnabled()) {
            return SsoDiscoveryDto.unavailable();
        }
        return SsoDiscoveryDto.available(REGISTRATION_PREFIX + orgId,
                connection.getProtocol(), connection.isEnforceSso());
    }

    /**
     * Resolves the organization an email address routes to via its domain, or null
     * when the domain is not mapped. Used by the login flow to select an IdP.
     * @param email the login email address
     * @return the owning organization id, or null
     */
    public Integer findOrgByDomain(String email) {
        if (email == null) {
            return null;
        }
        int at = email.lastIndexOf('@');
        if (at < 0 || at == email.length() - 1) {
            return null;
        }
        String domain = email.substring(at + 1).trim().toLowerCase();
        return domain.isEmpty() ? null : ssoDomainMapper.findOrgByDomain(domain);
    }

    private void replaceDomains(int orgId, List<String> requested) {
        Set<String> desired = normalizeDomains(requested);
        Set<String> current = new LinkedHashSet<>(ssoDomainMapper.listByOrg(orgId));
        for (String domain : current) {
            if (!desired.contains(domain)) {
                ssoDomainMapper.delete(domain);
            }
        }
        for (String domain : desired) {
            if (current.contains(domain)) {
                continue;
            }
            Integer owner = ssoDomainMapper.findOrgByDomain(domain);
            if (owner != null && owner != orgId) {
                throw new BadRequestException(
                        "The domain " + domain + " is already routed to another organization");
            }
            ssoDomainMapper.insert(domain, orgId);
        }
    }

    private void ensureSamlSpCredential(SsoConnection connection, SsoConnection existing, int orgId) {
        if (existing != null && "saml".equals(existing.getProtocol()) && existing.getSamlSpPrivateKeyEnc() != null) {
            connection.setSamlSpPrivateKeyEnc(existing.getSamlSpPrivateKeyEnc());
            connection.setSamlSpCertificate(existing.getSamlSpCertificate());
            return;
        }
        SamlSpKeyMaterial material = samlSpCredentialFactory.generate(REGISTRATION_PREFIX + orgId);
        connection.setSamlSpPrivateKeyEnc(ssoSecretCipher.encryptSamlSpPrivateKey(orgId, material.privateKeyBase64()));
        connection.setSamlSpCertificate(material.certificatePem());
    }

    private void deleteReplacedSecretReferences(SsoConnection existing, SsoConnection replacement) {
        if (existing == null) {
            return;
        }
        if (!Objects.equals(existing.getOidcClientSecretEnc(), replacement.getOidcClientSecretEnc())) {
            ssoSecretCipher.deleteOidcClientSecretReference(existing.getOrgId(), existing.getOidcClientSecretEnc());
        }
        if (!Objects.equals(existing.getSamlSpPrivateKeyEnc(), replacement.getSamlSpPrivateKeyEnc())) {
            ssoSecretCipher.deleteSamlSpPrivateKeyReference(existing.getOrgId(), existing.getSamlSpPrivateKeyEnc());
        }
    }

    private void validateEnabled(String protocol, SsoConnectionRequest request, SsoConnection existing) {
        if ("oidc".equals(protocol)) {
            if (isBlank(request.getOidcIssuer()) || isBlank(request.getOidcClientId())) {
                throw new BadRequestException("An OIDC issuer and client id are required to enable SSO");
            }
            SsoUrlSafety.requireValidHttpUrl(request.getOidcIssuer());
            boolean hasSecret = !isBlank(request.getOidcClientSecret())
                    || (existing != null && "oidc".equals(existing.getProtocol())
                        && existing.getOidcClientSecretEnc() != null);
            if (!hasSecret) {
                throw new BadRequestException("An OIDC client secret is required to enable SSO");
            }
        } else {
            if (isBlank(request.getSamlIdpEntityId())) {
                throw new BadRequestException("A SAML IdP entity id is required to enable SSO");
            }
            if (isBlank(request.getSamlSsoUrl()) && isBlank(request.getSamlIdpMetadataXml())) {
                throw new BadRequestException(
                        "A SAML SSO URL or IdP metadata is required to enable SSO");
            }
        }
    }

    private static String resolveRole(String requested) {
        if (isBlank(requested)) {
            return DEFAULT_ROLE;
        }
        String role = requested.trim().toLowerCase();
        if (!ALLOWED_ROLES.contains(role)) {
            throw new BadRequestException("The default SSO role must be member or admin");
        }
        return role;
    }

    private static String resolveScopes(String requested) {
        return isBlank(requested) ? DEFAULT_SCOPES : requested.trim();
    }

    private static Set<String> normalizeDomains(List<String> domains) {
        Set<String> normalized = new TreeSet<>();
        if (domains == null) {
            return normalized;
        }
        for (String raw : domains) {
            if (raw == null) {
                continue;
            }
            String domain = raw.trim().toLowerCase();
            if (domain.startsWith("@")) {
                domain = domain.substring(1);
            }
            if (!domain.isEmpty()) {
                normalized.add(domain);
            }
        }
        return normalized;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
