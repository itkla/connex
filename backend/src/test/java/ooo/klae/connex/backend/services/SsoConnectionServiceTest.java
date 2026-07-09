package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.SsoConnection;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.SsoConnectionDto;
import ooo.klae.connex.backend.dto.SsoConnectionRequest;
import ooo.klae.connex.backend.dto.SsoDiscoveryDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.OrgMemberMapper;
import ooo.klae.connex.backend.mappers.SecretValueMapper;
import ooo.klae.connex.backend.mappers.SsoConnectionMapper;
import ooo.klae.connex.backend.secrets.SecretPurpose;
import ooo.klae.connex.backend.secrets.SecretReference;
import ooo.klae.connex.backend.secrets.SecretStore;
import ooo.klae.connex.backend.secrets.StoredSecret;
import ooo.klae.connex.backend.sso.SsoSecretCipher;

/**
 * Verifies SSO connection management: org-admin save/read round-trip, that the
 * OIDC client secret is encrypted at rest and never surfaced in the DTO, that a
 * blank secret preserves the stored one, and that a user without org membership
 * is denied. SSO configuration is gated on org membership (#316), so the acting
 * user is enrolled as an org owner of the workspace's organization.
 */
class SsoConnectionServiceTest extends AbstractServiceTest {

    private static final String PLAINTEXT_SECRET = "super-secret-client-value";

    @Autowired private SsoConnectionService ssoConnectionService;
    @Autowired private SsoConnectionMapper ssoConnectionMapper;
    @Autowired private SecretValueMapper secretValueMapper;
    @Autowired private SsoSecretCipher ssoSecretCipher;
    @Autowired private SecretStore secretStore;
    @Autowired private OrgMemberMapper orgMemberMapper;

    @BeforeEach
    void enrollActingUserAsOrgOwner() {
        orgMemberMapper.addMember(workspaceMapper.getOrgId(workspace.getId()), currentUser.getId(), "owner");
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
        update.setOidcClientId("client-xyz");
        SsoConnectionDto saved = ssoConnectionService.save(workspace.getId(), currentUser.getId(), update);

        assertTrue(saved.isHasClientSecret());
        assertEquals("client-xyz", saved.getOidcClientId());
        assertEquals(firstEnc, ssoConnectionMapper.findByOrg(orgId).getOidcClientSecretEnc(),
                "a blank secret must keep the previously stored ciphertext");
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
