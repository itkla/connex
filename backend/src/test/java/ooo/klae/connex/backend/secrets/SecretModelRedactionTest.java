package ooo.klae.connex.backend.secrets;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.SsoConnection;
import ooo.klae.connex.backend.beans.WorkspaceMailConfig;
import ooo.klae.connex.backend.dto.MailConfigDto;

class SecretModelRedactionTest {

    @Test
    void workspaceMailConfigToStringRedactsStoredPassword() {
        WorkspaceMailConfig config = new WorkspaceMailConfig();
        config.setPasswordEnc("secret:v1:44");

        assertFalse(config.toString().contains("secret:v1:44"));
    }

    @Test
    void ssoConnectionToStringRedactsStoredSecrets() {
        SsoConnection connection = new SsoConnection();
        connection.setOidcClientSecretEnc("secret:v1:31");
        connection.setSamlSpPrivateKeyEnc("secret:v1:32");

        String rendered = connection.toString();
        assertFalse(rendered.contains("secret:v1:31"));
        assertFalse(rendered.contains("secret:v1:32"));
    }

    @Test
    void storedSecretToStringRedactsEncryptedMaterial() {
        StoredSecret secret = new StoredSecret();
        secret.setEncryptedDataKey("wrapped-data-key");
        secret.setCiphertext("encrypted-payload");

        String rendered = secret.toString();
        assertFalse(rendered.contains("wrapped-data-key"));
        assertFalse(rendered.contains("encrypted-payload"));
    }

    @Test
    void mailConfigDtoOmitsStoredPasswordReference() {
        WorkspaceMailConfig config = new WorkspaceMailConfig();
        config.setAuth(true);
        config.setPasswordEnc("secret:v1:44");

        MailConfigDto dto = MailConfigDto.from(config);

        assertFalse(dto.toString().contains("secret:v1:44"));
        assertFalse(dto.toString().contains("passwordEnc"));
    }
}
