package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.SsoConnection;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.SsoConnectionDto;
import ooo.klae.connex.backend.dto.SsoConnectionRequest;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.SsoConnectionMapper;
import ooo.klae.connex.backend.sso.SsoSecretCipher;

/**
 * Verifies SSO connection management: owner save/read round-trip, that the OIDC
 * client secret is encrypted at rest and never surfaced in the DTO, that a blank
 * secret preserves the stored one, and that a non-admin member is denied.
 */
class SsoConnectionServiceTest extends AbstractServiceTest {

    private static final String PLAINTEXT_SECRET = "super-secret-client-value";

    @Autowired private SsoConnectionService ssoConnectionService;
    @Autowired private SsoConnectionMapper ssoConnectionMapper;
    @Autowired private SsoSecretCipher ssoSecretCipher;

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
        assertNotEquals(PLAINTEXT_SECRET, stored.getOidcClientSecretEnc(), "the secret must not be stored in plaintext");
        assertFalse(stored.getOidcClientSecretEnc().contains(PLAINTEXT_SECRET),
                "the ciphertext must not embed the plaintext");
        assertEquals(PLAINTEXT_SECRET, ssoSecretCipher.decrypt(stored.getOidcClientSecretEnc()),
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
    void nonAdminMember_isDenied() {
        User member = newUser();
        assertThrows(ForbiddenException.class,
                () -> ssoConnectionService.getForWorkspace(workspace.getId(), member.getId()));
        assertThrows(ForbiddenException.class,
                () -> ssoConnectionService.save(workspace.getId(), member.getId(), oidcRequest()));
    }
}
