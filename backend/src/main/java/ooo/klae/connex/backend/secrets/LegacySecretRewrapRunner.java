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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.SsoConnection;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.WorkspaceMailConfig;
import ooo.klae.connex.backend.mail.SecretCipher;
import ooo.klae.connex.backend.mappers.MailConfigMapper;
import ooo.klae.connex.backend.mappers.SsoConnectionMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
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
    private final UserMapper userMapper;
    private final WorkspaceMapper workspaceMapper;
    private final PlatformTransactionManager transactionManager;

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
            TransactionTemplate transaction = new TransactionTemplate(transactionManager);
            transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            if (Boolean.TRUE.equals(transaction.execute(status -> rewrapWorkspaceMailSecret(config.getWorkspaceId())))) {
                count++;
            }
        }
        return count;
    }

    /** Each workspace commits independently so no SMTP child lock precedes another workspace root. */
    private boolean rewrapWorkspaceMailSecret(int workspaceId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof User user
                && userMapper.lockByIdForShare(user.getId()) == null) {
            return false;
        }
        if (workspaceMapper.lockWorkspace(workspaceId) == null) {
            return false;
        }
        WorkspaceMailConfig config = mailConfigMapper.findByWorkspaceForUpdate(workspaceId);
        if (config == null || !isLegacySecret(config.getPasswordEnc())) {
            return false;
        }
        if (!config.isEnabled() || !config.isAuth()) {
            mailConfigMapper.updatePasswordReference(workspaceId, null);
            return false;
        }
        if (!secretCipher.hasLegacyKey()) {
            throw new IllegalStateException(
                    "CONNEX_MAIL_SECRET_KEY is required until existing workspace SMTP secrets are rewrapped");
        }
        String plaintext = secretCipher.decryptForWorkspace(workspaceId, config.getPasswordEnc());
        String reference = secretCipher.encryptForWorkspace(workspaceId, plaintext);
        mailConfigMapper.updatePasswordReference(workspaceId, reference);
        return true;
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
