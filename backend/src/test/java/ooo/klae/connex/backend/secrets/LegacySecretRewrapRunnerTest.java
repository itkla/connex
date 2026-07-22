package ooo.klae.connex.backend.secrets;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.SsoConnection;
import ooo.klae.connex.backend.beans.WorkspaceMailConfig;
import ooo.klae.connex.backend.mail.SecretCipher;
import ooo.klae.connex.backend.mappers.MailConfigMapper;
import ooo.klae.connex.backend.mappers.SsoConnectionMapper;
import ooo.klae.connex.backend.sso.SsoSecretCipher;

@ExtendWith(MockitoExtension.class)
class LegacySecretRewrapRunnerTest {

    @Mock private MailConfigMapper mailConfigMapper;
    @Mock private SsoConnectionMapper ssoConnectionMapper;
    @Mock private SecretCipher secretCipher;
    @Mock private SsoSecretCipher ssoSecretCipher;

    @Test
    void run_rewrapsLegacyWorkspaceMailSecrets() {
        WorkspaceMailConfig config = new WorkspaceMailConfig();
        config.setWorkspaceId(7);
        config.setEnabled(true);
        config.setAuth(true);
        config.setPasswordEnc("legacy-mail-blob");
        when(mailConfigMapper.listLegacySecretConfigs()).thenReturn(List.of(config));
        when(ssoConnectionMapper.listLegacySecretConnections()).thenReturn(List.of());
        when(secretCipher.hasLegacyKey()).thenReturn(true);
        when(secretCipher.decryptForWorkspace(7, "legacy-mail-blob")).thenReturn("plain-mail");
        when(secretCipher.encryptForWorkspace(7, "plain-mail")).thenReturn("secret:v1:77");

        runner().run(null);

        verify(mailConfigMapper).updatePasswordReference(7, "secret:v1:77");
    }

    @Test
    void run_failsWhenLegacyMailRowsExistWithoutLegacyKey() {
        WorkspaceMailConfig config = new WorkspaceMailConfig();
        config.setWorkspaceId(7);
        config.setEnabled(true);
        config.setAuth(true);
        config.setPasswordEnc("legacy-mail-blob");
        when(mailConfigMapper.listLegacySecretConfigs()).thenReturn(List.of(config));
        when(secretCipher.hasLegacyKey()).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> runner().run(null));

        verify(secretCipher, never()).decryptForWorkspace(7, "legacy-mail-blob");
    }

    @Test
    void run_rewrapsLegacySsoSecrets() {
        SsoConnection oidc = new SsoConnection();
        oidc.setOrgId(3);
        oidc.setProtocol("oidc");
        oidc.setOidcClientSecretEnc("legacy-oidc-blob");
        SsoConnection saml = new SsoConnection();
        saml.setOrgId(4);
        saml.setProtocol("saml");
        saml.setSamlSpPrivateKeyEnc("legacy-saml-blob");
        when(mailConfigMapper.listLegacySecretConfigs()).thenReturn(List.of());
        when(ssoConnectionMapper.listLegacySecretConnections()).thenReturn(List.of(oidc, saml));
        when(ssoSecretCipher.hasLegacyKey()).thenReturn(true);
        when(ssoSecretCipher.decryptOidcClientSecret(3, "legacy-oidc-blob")).thenReturn("plain-oidc");
        when(ssoSecretCipher.encryptOidcClientSecret(3, "plain-oidc")).thenReturn("secret:v1:31");
        when(ssoSecretCipher.decryptSamlSpPrivateKey(4, "legacy-saml-blob")).thenReturn("plain-saml");
        when(ssoSecretCipher.encryptSamlSpPrivateKey(4, "plain-saml")).thenReturn("secret:v1:32");

        runner().run(null);

        verify(ssoConnectionMapper).updateOidcClientSecretReference(3, "secret:v1:31");
        verify(ssoConnectionMapper).updateSamlSpPrivateKeyReference(4, "secret:v1:32");
    }

    @Test
    void run_clearsInactiveLegacyMailSecretWithoutLegacyKey() {
        WorkspaceMailConfig config = new WorkspaceMailConfig();
        config.setWorkspaceId(7);
        config.setEnabled(true);
        config.setAuth(false);
        config.setPasswordEnc("legacy-mail-blob");
        when(mailConfigMapper.listLegacySecretConfigs()).thenReturn(List.of(config));
        when(ssoConnectionMapper.listLegacySecretConnections()).thenReturn(List.of());

        runner().run(null);

        verify(mailConfigMapper).updatePasswordReference(7, null);
        verify(secretCipher, never()).decryptForWorkspace(7, "legacy-mail-blob");
    }

    @Test
    void run_clearsOffProtocolLegacySsoSecretsWithoutLegacyKey() {
        SsoConnection connection = new SsoConnection();
        connection.setOrgId(3);
        connection.setProtocol("saml");
        connection.setOidcClientSecretEnc("legacy-oidc-blob");
        when(mailConfigMapper.listLegacySecretConfigs()).thenReturn(List.of());
        when(ssoConnectionMapper.listLegacySecretConnections()).thenReturn(List.of(connection));

        runner().run(null);

        verify(ssoConnectionMapper).updateOidcClientSecretReference(3, null);
        verify(ssoSecretCipher, never()).decryptOidcClientSecret(3, "legacy-oidc-blob");
    }

    private LegacySecretRewrapRunner runner() {
        return new LegacySecretRewrapRunner(mailConfigMapper, ssoConnectionMapper,
                secretCipher, ssoSecretCipher);
    }
}
