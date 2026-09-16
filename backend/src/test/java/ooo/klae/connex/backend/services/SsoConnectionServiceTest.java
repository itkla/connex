package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.SsoConnection;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.SsoConnectionDto;
import ooo.klae.connex.backend.dto.SsoConnectionRequest;
import ooo.klae.connex.backend.dto.SsoDiscoveryDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.OrgMemberMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.SecretValueMapper;
import ooo.klae.connex.backend.mappers.SsoConnectionMapper;
import ooo.klae.connex.backend.secrets.SecretPurpose;
import ooo.klae.connex.backend.secrets.SecretReference;
import ooo.klae.connex.backend.secrets.SecretStore;
import ooo.klae.connex.backend.secrets.StoredSecret;
import ooo.klae.connex.backend.sso.SsoSecretCipher;
import ooo.klae.connex.backend.sso.DbClientRegistrationRepository;
import ooo.klae.connex.backend.sso.SsoUrlSafety;

/**
 * Verifies SSO connection management: org-admin save/read round-trip, that the
 * OIDC client secret is encrypted at rest and never surfaced in the DTO, that a
 * blank secret preserves the stored one, and that a user without org membership
 * is denied. SSO configuration is gated on org membership (#316), so the acting
 * user is enrolled as an org owner of the workspace's organization.
 * Each test claims its own domain because committed verified accounts in other
 * test fixtures legitimately prevent claiming their shared email domains.
 */
class SsoConnectionServiceTest extends AbstractServiceTest {

    private static final String PLAINTEXT_SECRET = "super-secret-client-value";

    @Autowired private SsoConnectionService ssoConnectionService;
    @Autowired private SsoConnectionMapper ssoConnectionMapper;
    @Autowired private SecretValueMapper secretValueMapper;
    @Autowired private SsoSecretCipher ssoSecretCipher;
    @Autowired private SecretStore secretStore;
    @Autowired private OrgMemberMapper orgMemberMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private DbClientRegistrationRepository clientRegistrationRepository;

    private String emailDomain;

    @BeforeEach
    void enrollActingUserAsOrgOwner() {
        Organization organization = new Organization();
        organization.setName("SSO connection test");
        organization.setSlug("sso-connection-" + UUID.randomUUID());
        emailDomain = organization.getSlug() + ".example.test";
        organizationMapper.insert(organization);
        workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("SSO connection test");
        workspace.setSlug(organization.getSlug());
        workspaceMapper.insert(workspace);
        workspaceMapper.addMember(workspace.getId(), currentUser.getId(), "member");
        orgMemberMapper.addMember(workspaceMapper.getOrgId(workspace.getId()), currentUser.getId(), "owner");
        authenticateAs(currentUser, workspace.getId());
    }

    private SsoConnectionRequest oidcRequest() {
        SsoConnectionRequest req = new SsoConnectionRequest();
        req.setProtocol("oidc");
        req.setEnabled(true);
        req.setJitWorkspaceId(workspace.getId());
        req.setOidcIssuer("https://idp.example.com");
        req.setOidcClientId("client-abc");
        req.setOidcClientSecret(PLAINTEXT_SECRET);
        req.setOidcScopes("openid,email");
        req.setDomains(List.of(emailDomain.toUpperCase(Locale.ROOT), "@corp." + emailDomain));
        return req;
    }

    @Test
    void save_withUnrelatedVerifiedAccount_keepsDomainClaimsScoped() {
        User outside = new User();
        outside.setUsername("outside_" + unique());
        outside.setDisplayName("Outside account");
        outside.setEmail(unique() + "@example.com");
        outside.setEmailVerified(true);
        outside.setPasswordHash("hash_" + unique());
        outside.setTimezone("UTC");
        userMapper.insert(outside);

        SsoConnectionDto saved = ssoConnectionService.save(
                workspace.getId(), currentUser.getId(), oidcRequest());
        assertTrue(saved.isConfigured());

        SsoConnectionRequest conflicting = oidcRequest();
        conflicting.setDomains(List.of("example.com"));
        BadRequestException exception = assertThrows(BadRequestException.class,
                () -> ssoConnectionService.save(workspace.getId(), currentUser.getId(), conflicting));
        assertEquals("The domain is used by accounts outside this organization", exception.getMessage());
    }

    @Test
    void save_encryptsSecretAtRest_andOmitsItFromTheDto() {
        SsoConnectionDto saved = ssoConnectionService.save(workspace.getId(), currentUser.getId(), oidcRequest());

        assertTrue(saved.isConfigured());
        assertTrue(saved.isHasClientSecret(), "the DTO must report a stored secret");
        assertEquals(List.of("corp." + emailDomain, emailDomain), saved.getDomains(),
                "domains are normalized to lowercase, @ stripped, and sorted");

        SsoConnectionDto fetched = ssoConnectionService.getForWorkspace(workspace.getId(), currentUser.getId());
        assertTrue(fetched.isHasClientSecret());
        assertEquals("openid,email", fetched.getOidcScopes());

        int orgId = workspaceMapper.getOrgId(workspace.getId());
        SsoConnection stored = ssoConnectionMapper.findByOrg(orgId);
        assertNotNull(stored.getOidcClientSecretEnc(), "the secret must be persisted");
        assertTrue(SecretReference.isReference(stored.getOidcClientSecretEnc()),
                "the feature row must store a central secret reference");
        assertNotEquals(PLAINTEXT_SECRET, stored.getOidcClientSecretEnc(), "the secret must not be stored in plaintext");
        assertFalse(stored.getOidcClientSecretEnc().contains(PLAINTEXT_SECRET),
                "the ciphertext must not embed the plaintext");
        StoredSecret secret = secretValueMapper.findById(SecretReference.parse(stored.getOidcClientSecretEnc()).id());
        assertNotNull(secret);
        assertFalse(secret.getCiphertext().contains(PLAINTEXT_SECRET),
                "the central ciphertext must not embed the plaintext");
        assertFalse(secret.getEncryptedDataKey().contains(PLAINTEXT_SECRET),
                "the wrapped data key must not embed the plaintext");
        assertEquals(PLAINTEXT_SECRET, ssoSecretCipher.decryptOidcClientSecret(orgId, stored.getOidcClientSecretEnc()),
                "the stored blob must decrypt back to the original secret");
    }

    @Test
    void save_blankSecret_preservesStoredSecret() {
        ssoConnectionService.save(workspace.getId(), currentUser.getId(), oidcRequest());
        int orgId = workspaceMapper.getOrgId(workspace.getId());
        String firstEnc = ssoConnectionMapper.findByOrg(orgId).getOidcClientSecretEnc();

        SsoConnectionRequest update = oidcRequest();
        update.setOidcClientSecret(null);
        update.setOidcScopes("openid,email,profile");
        SsoConnectionDto saved = ssoConnectionService.save(workspace.getId(), currentUser.getId(), update);

        assertTrue(saved.isHasClientSecret());
        assertEquals("client-abc", saved.getOidcClientId());
        assertEquals("openid,email,profile", saved.getOidcScopes());
        assertEquals(firstEnc, ssoConnectionMapper.findByOrg(orgId).getOidcClientSecretEnc(),
                "a blank secret must keep the previously stored ciphertext");
    }

    @ParameterizedTest
    @CsvSource({"issuer,true", "issuer,false", "client,true", "client,false"})
    void save_changedCredentialIdentityRequiresFreshSecret(String changedField, boolean enabled) {
        ssoConnectionService.save(workspace.getId(), currentUser.getId(), oidcRequest());
        int orgId = workspace.getOrgId();
        SsoConnection before = ssoConnectionMapper.findByOrg(orgId);
        SsoConnectionRequest update = oidcRequest();
        update.setEnabled(enabled);
        update.setOidcClientSecret("  ");
        if ("issuer".equals(changedField)) {
            update.setOidcIssuer("https://replacement.example.test");
        } else {
            update.setOidcClientId("replacement-client");
        }

        assertThrows(BadRequestException.class,
                () -> ssoConnectionService.save(workspace.getId(), currentUser.getId(), update));

        assertEquals(before, ssoConnectionMapper.findByOrg(orgId));
        assertEquals(PLAINTEXT_SECRET,
                ssoSecretCipher.decryptOidcClientSecret(orgId, before.getOidcClientSecretEnc()));
    }

    @Test
    void save_issuerTransitionNeverSendsPreviousSecret() {
        ssoConnectionService.save(workspace.getId(), currentUser.getId(), oidcRequest());
        int orgId = workspace.getOrgId();
        String oldReference = ssoConnectionMapper.findByOrg(orgId).getOidcClientSecretEnc();
        String replacementIssuer = "https://replacement.example.test";
        String replacementSecret = "replacement-client-secret";
        try (var safety = mockStatic(SsoUrlSafety.class);
                var discovery = mockStatic(ClientRegistrations.class)) {
            safety.when(() -> SsoUrlSafety.isFetchableHttpUrl("https://idp.example.com", false)).thenReturn(true);
            safety.when(() -> SsoUrlSafety.isFetchableHttpUrl(replacementIssuer, false)).thenReturn(true);
            discovery.when(() -> ClientRegistrations.fromIssuerLocation("https://idp.example.com"))
                    .thenReturn(discoveredRegistration("https://idp.example.com"));
            discovery.when(() -> ClientRegistrations.fromIssuerLocation(replacementIssuer))
                    .thenReturn(discoveredRegistration(replacementIssuer));
            assertNotNull(clientRegistrationRepository.findByRegistrationId("org-" + orgId));

            SsoConnectionRequest update = oidcRequest();
            update.setOidcIssuer(replacementIssuer);
            update.setOidcClientSecret("");
            assertThrows(BadRequestException.class,
                    () -> ssoConnectionService.save(workspace.getId(), currentUser.getId(), update));
            assertEquals("https://idp.example.com", ssoConnectionMapper.findByOrg(orgId).getOidcIssuer());

            update.setOidcClientSecret(replacementSecret);
            ssoConnectionService.save(workspace.getId(), currentUser.getId(), update);
            update.setOidcClientSecret(null);
            ssoConnectionService.save(workspace.getId(), currentUser.getId(), update);
            ClientRegistration registration = clientRegistrationRepository.findByRegistrationId("org-" + orgId);
            assertNotNull(registration);
            assertEquals(replacementSecret, registration.getClientSecret());
            assertNotEquals(oldReference, ssoConnectionMapper.findByOrg(orgId).getOidcClientSecretEnc());
            assertFalse(secretStore.exists(SecretPurpose.ORG_SSO_OIDC_CLIENT_SECRET, orgId, oldReference));
            assertThrows(ResourceNotFoundException.class,
                    () -> ssoSecretCipher.decryptOidcClientSecret(orgId, oldReference));

            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            server.expect(requestTo(replacementIssuer + "/token"))
                    .andExpect(header(HttpHeaders.AUTHORIZATION, "Basic " + HttpHeaders.encodeBasicAuth(
                            "client-abc", replacementSecret, StandardCharsets.UTF_8)))
                    .andRespond(withStatus(HttpStatus.BAD_REQUEST));
            RestClientAuthorizationCodeTokenResponseClient client = new RestClientAuthorizationCodeTokenResponseClient();
            client.setRestClient(builder.build());
            OAuth2AuthorizationRequest authorization = OAuth2AuthorizationRequest.authorizationCode()
                    .authorizationUri(replacementIssuer + "/authorize").clientId("client-abc")
                    .redirectUri("https://app.example.test/callback").state("state").build();
            OAuth2AuthorizationResponse response = OAuth2AuthorizationResponse.success("code")
                    .redirectUri("https://app.example.test/callback").state("state").build();
            OAuth2AuthorizationCodeGrantRequest grant = new OAuth2AuthorizationCodeGrantRequest(
                    registration, new OAuth2AuthorizationExchange(authorization, response));

            assertThrows(OAuth2AuthorizationException.class, () -> client.getTokenResponse(grant));
            server.verify();
        }
    }

    @Test
    void save_clientIdTransitionInvalidatesPreviousSecretReference() {
        ssoConnectionService.save(workspace.getId(), currentUser.getId(), oidcRequest());
        int orgId = workspace.getOrgId();
        SsoConnection previous = ssoConnectionMapper.findByOrg(orgId);
        SsoConnectionRequest update = oidcRequest();
        update.setOidcClientId("replacement-client");
        update.setOidcClientSecret("replacement-client-secret");

        ssoConnectionService.save(workspace.getId(), currentUser.getId(), update);

        SsoConnection current = ssoConnectionMapper.findByOrg(orgId);
        assertNotEquals(previous.getOidcClientSecretEnc(), current.getOidcClientSecretEnc());
        assertEquals("replacement-client-secret",
                ssoSecretCipher.decryptOidcClientSecret(orgId, current.getOidcClientSecretEnc()));
        assertThrows(ResourceNotFoundException.class,
                () -> ssoSecretCipher.decryptOidcClientSecret(orgId, previous.getOidcClientSecretEnc()));
    }

    private static ClientRegistration.Builder discoveredRegistration(String issuer) {
        return ClientRegistration.withRegistrationId("discovery")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .issuerUri(issuer).authorizationUri(issuer + "/authorize")
                .tokenUri(issuer + "/token").jwkSetUri(issuer + "/jwks");
    }

    @Test
    void save_switchToSaml_clearsOidcSecretReference() {
        ssoConnectionService.save(workspace.getId(), currentUser.getId(), oidcRequest());
        int orgId = workspaceMapper.getOrgId(workspace.getId());
        String oidcReference = ssoConnectionMapper.findByOrg(orgId).getOidcClientSecretEnc();

        SsoConnectionRequest saml = new SsoConnectionRequest();
        saml.setProtocol("saml");
        saml.setEnabled(true);
        saml.setJitWorkspaceId(workspace.getId());
        saml.setSamlIdpEntityId("https://idp.example.com/saml");
        saml.setSamlSsoUrl("https://idp.example.com/saml/sso");
        ssoConnectionService.save(workspace.getId(), currentUser.getId(), saml);

        SsoConnection stored = ssoConnectionMapper.findByOrg(orgId);
        assertNull(stored.getOidcClientSecretEnc());
        assertFalse(secretStore.exists(SecretPurpose.ORG_SSO_OIDC_CLIENT_SECRET, orgId, oidcReference));
        assertTrue(SecretReference.isReference(stored.getSamlSpPrivateKeyEnc()));
        assertNotNull(stored.getSamlSpCertificate());
    }

    @Test
    void save_oidcBlankSecretDoesNotReuseOffProtocolStaleSecret() {
        int orgId = workspaceMapper.getOrgId(workspace.getId());
        SsoConnection stale = new SsoConnection();
        stale.setOrgId(orgId);
        stale.setProtocol("saml");
        stale.setEnabled(false);
        stale.setJitWorkspaceId(workspace.getId());
        stale.setDefaultRole("member");
        stale.setOidcScopes("openid,email,profile");
        stale.setOidcClientSecretEnc("legacy-off-protocol-secret");
        stale.setSamlIdpEntityId("https://idp.example.com/saml");
        ssoConnectionMapper.upsert(stale);

        SsoConnectionRequest update = oidcRequest();
        update.setOidcClientSecret(null);

        assertThrows(BadRequestException.class,
                () -> ssoConnectionService.save(workspace.getId(), currentUser.getId(), update));
    }

    @Test
    void discoverByEmail_routesAnEnabledDomain_andHidesAccountExistence() {
        ssoConnectionService.save(workspace.getId(), currentUser.getId(), oidcRequest());
        int orgId = workspaceMapper.getOrgId(workspace.getId());

        SsoDiscoveryDto routed = ssoConnectionService.discoverByEmail("alice@" + emailDomain);
        assertTrue(routed.isAvailable());
        assertEquals("org-" + orgId, routed.getRegistrationId());
        assertEquals("oidc", routed.getProtocol());
        assertFalse(routed.isEnforced());

        assertFalse(ssoConnectionService.discoverByEmail("bob@unmapped." + emailDomain).isAvailable(),
                "an unmapped domain is unavailable");
        assertFalse(ssoConnectionService.discoverByEmail(null).isAvailable());
        assertFalse(ssoConnectionService.discoverByEmail("no-at-sign").isAvailable());
    }

    @Test
    void discoverByEmail_disabledConnection_isUnavailable() {
        SsoConnectionRequest disabled = oidcRequest();
        disabled.setEnabled(false);
        ssoConnectionService.save(workspace.getId(), currentUser.getId(), disabled);

        assertFalse(ssoConnectionService.discoverByEmail("alice@" + emailDomain).isAvailable(),
                "a domain routed to a disabled connection must not be startable");
    }

    @Test
    void nonAdminMember_isDenied() {
        User member = newUser();
        assertThrows(ForbiddenException.class,
                () -> ssoConnectionService.getForWorkspace(workspace.getId(), member.getId()));
        assertThrows(ForbiddenException.class,
                () -> ssoConnectionService.save(workspace.getId(), member.getId(), oidcRequest()));
    }

    @Test
    void unknownWorkspace_isForbiddenNotFound() {
        assertThrows(ForbiddenException.class,
                () -> ssoConnectionService.getForWorkspace(999_999, currentUser.getId()),
                "an unknown workspace must not be distinguishable from an unauthorized one");
        assertThrows(ForbiddenException.class,
                () -> ssoConnectionService.save(999_999, currentUser.getId(), oidcRequest()));
    }
}
