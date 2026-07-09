package ooo.klae.connex.backend.sso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.saml2.provider.service.registration.AssertingPartyMetadata;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.security.saml2.provider.service.registration.Saml2MessageBinding;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.SsoConnection;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.SsoConnectionMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

/**
 * Verifies the SAML relying-party resolver: an enabled SAML connection resolves to a registration
 * with the right SP assertion-consumer location, SP entityId, and asserting-party (IdP) entityId,
 * SSO location, REDIRECT binding, and verification credential; a disabled, OIDC, or unknown org
 * resolves to null; a malformed registration id or a malformed certificate resolves to null rather
 * than throwing; and built registrations are rebuilt rather than cached with decrypted key material.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class DbRelyingPartyRegistrationRepositoryTest {

    private static final String IDP_ENTITY_ID = "https://idp.example.com/saml/metadata";
    private static final String SSO_URL = "https://idp.example.com/saml/sso";

    private static final String TEST_CERT_PEM = """
            -----BEGIN CERTIFICATE-----
            MIIDHzCCAgegAwIBAgIUH6x02G6rv18t0cdsLOCQqvnQSEYwDQYJKoZIhvcNAQEL
            BQAwHzEdMBsGA1UEAwwUQ29ubmV4IFNBTUwgVGVzdCBJZFAwHhcNMjYwNzA0MDEx
            OTMwWhcNMzYwNzAxMDExOTMwWjAfMR0wGwYDVQQDDBRDb25uZXggU0FNTCBUZXN0
            IElkUDCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAMqgqAKUm9bPvKl7
            rRzX24UohWHIg6dGK5H7DDSXV4MdOVCJdsw7BBZTb9mrCUj7+YhGxVPYmMNN7q8+
            t2Gch4oc9qmKdVfzbWT8Usy9u7UqMdQylxWR+lBCVUUrDBznGNZ+Os5QPQlNuQWr
            IAVwTPkp5w9zn+grByurE7uVCBt02vgyTtPNHEP4SSoEih+BwCXSZ6F5oTRghTaC
            lxvnrsFSwUzO5hxCPESiXykEQlk4eztuV0vuw6SkZk1yS2mQbi3FsSeUpbH4bWjv
            A8/t5QZZGDhSqSZ9UxhUTFS0uwH2FYerO6KTZaDc4AOhZ5NsCsCsE7J4IUiBYvwl
            BbG2B9kCAwEAAaNTMFEwHQYDVR0OBBYEFLztAXzwXbaByJv8fyAQSlVeV1epMB8G
            A1UdIwQYMBaAFLztAXzwXbaByJv8fyAQSlVeV1epMA8GA1UdEwEB/wQFMAMBAf8w
            DQYJKoZIhvcNAQELBQADggEBAGyWZ3zF0w/cz5PD+LUQALFkYdq5+P908FBHrehC
            KFoTL1ZRfy4qRHrbc2ieoVARfJqTsMqJK/JVVyoJBtIItLrms/72PcXzkiChkz1k
            mGhBfnQk6+pV0FLvXk6q9zWlqyzN9/UClnBlwwI0ZRJxsrpih3kKpe1gJP8h5goU
            X1HDOE7Yxa9vDVufLCfiZmnbLSNCfG0ddAk3b8XPEoO8tM20LH/uDsqYc7YAOd5o
            cpnxT2PGdQ0E9i/YrCV8ko/AL8EYENAkUvsyvq3rPTyhq2uLTlBckuGKyG3Uks5y
            WJaMQG8Ynx1nMQWwLGAFXjewKQWohI6hzZefW0hbQTYysfU=
            -----END CERTIFICATE-----
            """;

    @Autowired private DbRelyingPartyRegistrationRepository repository;
    @Autowired private SsoConnectionMapper ssoConnectionMapper;
    @Autowired private WorkspaceMapper workspaceMapper;

    private int orgId;
    private int workspaceId;
    private String registrationId;

    @BeforeEach
    void setUp() {
        Workspace workspace = workspaceMapper.getDefaultWorkspace();
        if (workspace == null) {
            workspace = new Workspace();
            workspace.setName("Test Workspace");
            workspace.setSlug("default");
            workspaceMapper.insert(workspace);
        }
        workspaceId = workspace.getId();
        orgId = workspaceMapper.getOrgId(workspaceId);
        registrationId = "org-" + orgId;
        repository.evict(orgId);
    }

    private SsoConnection samlConnection() {
        SsoConnection connection = new SsoConnection();
        connection.setOrgId(orgId);
        connection.setProtocol("saml");
        connection.setEnabled(true);
        connection.setEnforceSso(false);
        connection.setJitWorkspaceId(workspaceId);
        connection.setDefaultRole("member");
        connection.setOidcScopes("openid,email,profile");
        connection.setSamlIdpEntityId(IDP_ENTITY_ID);
        connection.setSamlSsoUrl(SSO_URL);
        connection.setSamlIdpX509(TEST_CERT_PEM);
        return connection;
    }

    @Test
    void enabledSamlConnection_resolvesRegistrationFromExplicitFields() {
        ssoConnectionMapper.upsert(samlConnection());

        RelyingPartyRegistration registration = repository.findByRegistrationId(registrationId);

        assertNotNull(registration, "an enabled SAML connection must resolve to a registration");
        assertEquals(registrationId, registration.getRegistrationId());
        assertTrue(registration.getAssertionConsumerServiceLocation().contains("/api/login/saml2/sso/"),
                "the SP assertion-consumer location must be the relocated /api ACS");
        assertTrue(registration.getEntityId().contains("/api/saml2/service-provider-metadata/"),
                "the SP entityId must be the /api metadata identifier");

        AssertingPartyMetadata assertingParty = registration.getAssertingPartyMetadata();
        assertEquals(IDP_ENTITY_ID, assertingParty.getEntityId());
        assertEquals(SSO_URL, assertingParty.getSingleSignOnServiceLocation());
        assertEquals(Saml2MessageBinding.REDIRECT, assertingParty.getSingleSignOnServiceBinding());
        assertEquals(1, assertingParty.getVerificationX509Credentials().size(),
                "the IdP signing certificate must become a verification credential");
        assertNotNull(assertingParty.getVerificationX509Credentials().iterator().next().getCertificate());
    }

    @Test
    void resolvedRegistration_isRebuiltWithoutSecretBearingCache() {
        ssoConnectionMapper.upsert(samlConnection());

        RelyingPartyRegistration first = repository.findByRegistrationId(registrationId);
        RelyingPartyRegistration second = repository.findByRegistrationId(registrationId);
        assertNotSame(first, second, "registrations must not be cached with decrypted key material");

        repository.evict(orgId);
        RelyingPartyRegistration rebuilt = repository.findByRegistrationId(registrationId);
        assertNotNull(rebuilt);
    }

    @Test
    void disabledConnection_resolvesNull() {
        SsoConnection connection = samlConnection();
        connection.setEnabled(false);
        ssoConnectionMapper.upsert(connection);

        assertNull(repository.findByRegistrationId(registrationId),
                "a disabled connection must not resolve");
    }

    @Test
    void oidcConnection_resolvesNull() {
        SsoConnection connection = samlConnection();
        connection.setProtocol("oidc");
        ssoConnectionMapper.upsert(connection);

        assertNull(repository.findByRegistrationId(registrationId),
                "an OIDC connection must not resolve through the SAML repository");
    }

    @Test
    void unknownOrg_resolvesNull() {
        assertNull(repository.findByRegistrationId("org-999999"),
                "an org with no connection must not resolve");
    }

    @Test
    void malformedRegistrationId_resolvesNull() {
        assertNull(repository.findByRegistrationId("not-an-org"));
        assertNull(repository.findByRegistrationId("org-abc"));
        assertNull(repository.findByRegistrationId(null));
    }

    @Test
    void malformedCertificate_resolvesNullNotException() {
        SsoConnection connection = samlConnection();
        connection.setSamlIdpX509("-----BEGIN CERTIFICATE-----\nnot-a-real-certificate\n-----END CERTIFICATE-----");
        ssoConnectionMapper.upsert(connection);

        assertNull(repository.findByRegistrationId(registrationId),
                "a malformed certificate must be skipped (null), never a 500");
    }

    @Test
    void samlSpKeyMaterialToStringRedactsPrivateKey() {
        String rendered = new SamlSpKeyMaterial("private-key", "certificate").toString();

        assertFalse(rendered.contains("private-key"));
        assertTrue(rendered.contains("certificate"));
    }
}
