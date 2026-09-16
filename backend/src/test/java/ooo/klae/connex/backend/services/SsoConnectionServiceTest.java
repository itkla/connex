package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import ooo.klae.connex.backend.sso.DbClientRegistrationRepository;
import ooo.klae.connex.backend.sso.SsoHttpClient;
import ooo.klae.connex.backend.sso.SsoHttpClientTestSupport;
import ooo.klae.connex.backend.sso.SsoProperties;
import ooo.klae.connex.backend.sso.SsoSecretCipher;

/**
 * Verifies SSO connection management: org-admin save/read round-trip, that the
 * OIDC client secret is encrypted at rest and never surfaced in the DTO, that a
 * blank secret preserves the stored one, and that a user without org membership
 * is denied. SSO configuration is gated on org membership (#316), so the acting
 * user is enrolled as an org owner of the workspace's organization.
 */
@Import(SsoConnectionServiceTest.SsoTransportTestConfiguration.class)
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
    @Autowired private SsoHttpClient ssoHttpClient;
    @MockitoBean private CloseableHttpClient oidcTransport;

    @TestConfiguration(proxyBeanMethods = false)
    static class SsoTransportTestConfiguration {
        @Bean
        @Primary
        SsoHttpClient testSsoHttpClient(SsoProperties properties, CloseableHttpClient oidcTransport)
                throws UnknownHostException {
            return SsoHttpClientTestSupport.stubbedClient(properties,
                    List.of("idp.example.com", "replacement.example.test"), oidcTransport);
        }
    }

    @BeforeEach
    void enrollActingUserAsOrgOwner() {
        Organization organization = new Organization();
        organization.setName("SSO connection test");
        organization.setSlug("sso-connection-" + UUID.randomUUID());
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
        req.setDomains(List.of("Example.com", "@corp.example.com"));
        return req;
    }

    @Test
    void save_encryptsSecretAtRest_andOmitsItFromTheDto() {
        SsoConnectionDto saved = ssoConnectionService.save(workspace.getId(), currentUser.getId(), oidcRequest());

        assertTrue(saved.isConfigured());
        assertTrue(saved.isHasClientSecret(), "the DTO must report a stored secret");
        assertEquals(List.of("corp.example.com", "example.com"), saved.getDomains(),
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
    void save_issuerTransitionNeverSendsPreviousSecret() throws Exception {
        ssoConnectionService.save(workspace.getId(), currentUser.getId(), oidcRequest());
        int orgId = workspace.getOrgId();
        String oldReference = ssoConnectionMapper.findByOrg(orgId).getOidcClientSecretEnc();
        String replacementIssuer = "https://replacement.example.test";
        String replacementSecret = "replacement-client-secret";
        Map<String, String> documents = Map.of(
                "https://idp.example.com/.well-known/openid-configuration", discoveryDocument("https://idp.example.com"),
                replacementIssuer + "/.well-known/openid-configuration", discoveryDocument(replacementIssuer));
        List<OutboundRequest> requests = new CopyOnWriteArrayList<>();
        when(oidcTransport.executeOpen(any(), any(), any())).thenAnswer(invocation -> {
            OutboundRequest request = captureRequest(invocation.getArgument(1, ClassicHttpRequest.class));
            requests.add(request);
            String document = documents.get(request.uri().toString());
            if (request.method().equals("GET") && document != null) {
                return jsonResponse(200, document);
            }
            if (request.method().equals("POST") && request.uri().equals(URI.create(replacementIssuer + "/token"))) {
                return jsonResponse(400, "{\"error\":\"invalid_grant\"}");
            }
            throw new IOException("Unexpected SSO fixture request");
        });
        ClientRegistration previous = clientRegistrationRepository.findByRegistrationId("org-" + orgId);
        assertNotNull(previous);
        assertEquals("https://idp.example.com", previous.getProviderDetails().getIssuerUri());
        assertEquals(PLAINTEXT_SECRET, previous.getClientSecret());

        SsoConnectionRequest update = oidcRequest();
        update.setOidcIssuer(replacementIssuer);
        update.setOidcClientSecret("");
        assertThrows(BadRequestException.class,
                () -> ssoConnectionService.save(workspace.getId(), currentUser.getId(), update));
        assertEquals("https://idp.example.com", ssoConnectionMapper.findByOrg(orgId).getOidcIssuer());
        assertEquals(oldReference, ssoConnectionMapper.findByOrg(orgId).getOidcClientSecretEnc());
        assertEquals(1, requests.size(), "the rejected transition must not contact the replacement issuer");

        update.setOidcClientSecret(replacementSecret);
        ssoConnectionService.save(workspace.getId(), currentUser.getId(), update);
        update.setOidcClientSecret(null);
        ssoConnectionService.save(workspace.getId(), currentUser.getId(), update);
        ClientRegistration registration = clientRegistrationRepository.findByRegistrationId("org-" + orgId);
        assertNotNull(registration);
        assertEquals(replacementIssuer, registration.getProviderDetails().getIssuerUri());
        assertEquals(replacementIssuer + "/token", registration.getProviderDetails().getTokenUri());
        assertEquals(replacementSecret, registration.getClientSecret());
        assertNotEquals(oldReference, ssoConnectionMapper.findByOrg(orgId).getOidcClientSecretEnc());
        assertFalse(secretStore.exists(SecretPurpose.ORG_SSO_OIDC_CLIENT_SECRET, orgId, oldReference));
        assertThrows(ResourceNotFoundException.class,
                () -> ssoSecretCipher.decryptOidcClientSecret(orgId, oldReference));

        RestClientAuthorizationCodeTokenResponseClient client = new RestClientAuthorizationCodeTokenResponseClient();
        client.setRestClient(RestClient.builder()
                .requestFactory(ssoHttpClient.forEnterpriseRegistration(registration.getRegistrationId()))
                .configureMessageConverters(converters -> converters.addCustomConverter(
                        new FormHttpMessageConverter()).addCustomConverter(
                        new OAuth2AccessTokenResponseHttpMessageConverter()))
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler()).build());
        OAuth2AuthorizationRequest authorization = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri(replacementIssuer + "/authorize").clientId("client-abc")
                .redirectUri("https://app.example.test/callback").state("state").build();
        OAuth2AuthorizationResponse response = OAuth2AuthorizationResponse.success("code")
                .redirectUri("https://app.example.test/callback").state("state").build();
        OAuth2AuthorizationCodeGrantRequest grant = new OAuth2AuthorizationCodeGrantRequest(
                registration, new OAuth2AuthorizationExchange(authorization, response));

        OAuth2AuthorizationException failure = assertThrows(OAuth2AuthorizationException.class,
                () -> client.getTokenResponse(grant));
        assertEquals("invalid_grant", failure.getError().getErrorCode());
        assertEquals(List.of(
                URI.create("https://idp.example.com/.well-known/openid-configuration"),
                URI.create(replacementIssuer + "/.well-known/openid-configuration"),
                URI.create(replacementIssuer + "/token")), requests.stream().map(OutboundRequest::uri).toList());
        assertEquals(List.of("GET", "GET", "POST"), requests.stream().map(OutboundRequest::method).toList());
        assertNull(requests.get(0).headers().getFirst(HttpHeaders.AUTHORIZATION));
        assertNull(requests.get(1).headers().getFirst(HttpHeaders.AUTHORIZATION));
        OutboundRequest tokenRequest = requests.getLast();
        assertEquals("Basic " + HttpHeaders.encodeBasicAuth("client-abc", replacementSecret, StandardCharsets.UTF_8),
                tokenRequest.headers().getFirst(HttpHeaders.AUTHORIZATION));
        assertTrue(tokenRequest.body().contains("grant_type=authorization_code"));
        assertTrue(tokenRequest.body().contains("code=code"));
        String previousBasicCredentials = HttpHeaders.encodeBasicAuth("client-abc", PLAINTEXT_SECRET,
                StandardCharsets.UTF_8);
        for (OutboundRequest request : requests) {
            String sent = request.uri() + request.headers().toString() + request.body();
            assertFalse(sent.contains(PLAINTEXT_SECRET), "the transport must never receive the previous secret");
            assertFalse(sent.contains(previousBasicCredentials), "the transport must never receive its Basic encoding");
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

    private static String discoveryDocument(String issuer) {
        return """
                {"issuer":"%1$s","authorization_endpoint":"%1$s/authorize",
                 "token_endpoint":"%1$s/token","jwks_uri":"%1$s/jwks",
                 "response_types_supported":["code"],"subject_types_supported":["public"],
                 "id_token_signing_alg_values_supported":["RS256"]}
                """.formatted(issuer);
    }

    private static BasicClassicHttpResponse jsonResponse(int status, String body) {
        BasicClassicHttpResponse response = new BasicClassicHttpResponse(status);
        response.setHeader(HttpHeaders.CONTENT_TYPE, "application/json");
        response.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
        return response;
    }

    private static OutboundRequest captureRequest(ClassicHttpRequest request) throws IOException, URISyntaxException {
        HttpHeaders headers = new HttpHeaders();
        for (Header header : request.getHeaders()) {
            headers.add(header.getName(), header.getValue());
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        if (request.getEntity() != null) {
            request.getEntity().writeTo(body);
        }
        return new OutboundRequest(request.getUri(), request.getMethod(), HttpHeaders.readOnlyHttpHeaders(headers),
                body.toString(StandardCharsets.UTF_8));
    }

    private record OutboundRequest(URI uri, String method, HttpHeaders headers, String body) {
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

        SsoDiscoveryDto routed = ssoConnectionService.discoverByEmail("alice@example.com");
        assertTrue(routed.isAvailable());
        assertEquals("org-" + orgId, routed.getRegistrationId());
        assertEquals("oidc", routed.getProtocol());
        assertFalse(routed.isEnforced());

        assertFalse(ssoConnectionService.discoverByEmail("bob@unmapped.example.org").isAvailable(),
                "an unmapped domain is unavailable");
        assertFalse(ssoConnectionService.discoverByEmail(null).isAvailable());
        assertFalse(ssoConnectionService.discoverByEmail("no-at-sign").isAvailable());
    }

    @Test
    void discoverByEmail_disabledConnection_isUnavailable() {
        SsoConnectionRequest disabled = oidcRequest();
        disabled.setEnabled(false);
        ssoConnectionService.save(workspace.getId(), currentUser.getId(), disabled);

        assertFalse(ssoConnectionService.discoverByEmail("alice@example.com").isAvailable(),
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
