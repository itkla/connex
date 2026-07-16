package ooo.klae.connex.backend.secrets;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.SsoConnection;
import ooo.klae.connex.backend.beans.WorkspaceMailConfig;
import ooo.klae.connex.backend.mail.SecretCipher;
import ooo.klae.connex.backend.mappers.MailConfigMapper;
import ooo.klae.connex.backend.mappers.SsoConnectionMapper;
import ooo.klae.connex.backend.sso.SsoSecretCipher;

/**
 * Rewraps pre-secret-store SMTP and SSO ciphertext into the central envelope
 * store on startup. This keeps legacy keys as a temporary decrypt-only bridge
 * and removes legacy blobs from feature tables as soon as the application can
 * decrypt them.
 */
@Component
@ConditionalOnProperty(
    prefix = "connex.maintenance",
    name = "mode",
    havingValue = "off",
    matchIfMissing = true)
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
public class LegacySecretRewrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LegacySecretRewrapRunner.class);

    private final MailConfigMapper mailConfigMapper;
    private final SsoConnectionMapper ssoConnectionMapper;
    private final SecretCipher secretCipher;
    private final SsoSecretCipher ssoSecretCipher;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int mailCount = rewrapWorkspaceMailSecrets();
        SsoRewrapCounts ssoCounts = rewrapSsoSecrets();
        if (mailCount > 0 || ssoCounts.total() > 0) {
            log.info("Rewrapped legacy integration secrets into central store: workspaceSmtp={} oidc={} saml={}",
                    mailCount, ssoCounts.oidc(), ssoCounts.saml());
        }
    }

    private int rewrapWorkspaceMailSecrets() {
        List<WorkspaceMailConfig> configs = mailConfigMapper.listLegacySecretConfigs();
        if (configs.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (WorkspaceMailConfig config : configs) {
            if (!config.isEnabled() || !config.isAuth()) {
                mailConfigMapper.updatePasswordReference(config.getWorkspaceId(), null);
                continue;
            }
            if (!secretCipher.hasLegacyKey()) {
                throw new IllegalStateException(
                        "CONNEX_MAIL_SECRET_KEY is required until existing workspace SMTP secrets are rewrapped");
            }
            String plaintext = secretCipher.decryptForWorkspace(config.getWorkspaceId(), config.getPasswordEnc());
            String reference = secretCipher.encryptForWorkspace(config.getWorkspaceId(), plaintext);
            mailConfigMapper.updatePasswordReference(config.getWorkspaceId(), reference);
            count++;
        }
        return count;
    }

    private SsoRewrapCounts rewrapSsoSecrets() {
        List<SsoConnection> connections = ssoConnectionMapper.listLegacySecretConnections();
        if (connections.isEmpty()) {
            return new SsoRewrapCounts(0, 0);
        }
        int oidc = 0;
        int saml = 0;
        for (SsoConnection connection : connections) {
            if (isLegacySecret(connection.getOidcClientSecretEnc())) {
                if (!"oidc".equals(connection.getProtocol())) {
                    ssoConnectionMapper.updateOidcClientSecretReference(connection.getOrgId(), null);
                } else {
                    requireLegacySsoKey();
                    String plaintext = ssoSecretCipher.decryptOidcClientSecret(connection.getOrgId(),
                            connection.getOidcClientSecretEnc());
                    String reference = ssoSecretCipher.encryptOidcClientSecret(connection.getOrgId(), plaintext);
                    ssoConnectionMapper.updateOidcClientSecretReference(connection.getOrgId(), reference);
                    oidc++;
                }
            }
            if (isLegacySecret(connection.getSamlSpPrivateKeyEnc())) {
                if (!"saml".equals(connection.getProtocol())) {
                    ssoConnectionMapper.updateSamlSpPrivateKeyReference(connection.getOrgId(), null);
                } else {
                    requireLegacySsoKey();
                    String plaintext = ssoSecretCipher.decryptSamlSpPrivateKey(connection.getOrgId(),
                            connection.getSamlSpPrivateKeyEnc());
                    String reference = ssoSecretCipher.encryptSamlSpPrivateKey(connection.getOrgId(), plaintext);
                    ssoConnectionMapper.updateSamlSpPrivateKeyReference(connection.getOrgId(), reference);
                    saml++;
                }
            }
        }
        return new SsoRewrapCounts(oidc, saml);
    }

    private void requireLegacySsoKey() {
        if (!ssoSecretCipher.hasLegacyKey()) {
            throw new IllegalStateException(
                    "CONNEX_SSO_SECRET_KEY is required until existing SSO secrets are rewrapped");
        }
    }

    private static boolean isLegacySecret(String value) {
        return value != null && !value.isBlank() && !SecretReference.isReference(value);
    }

    private record SsoRewrapCounts(int oidc, int saml) {
        int total() {
            return oidc + saml;
        }
    }
}
