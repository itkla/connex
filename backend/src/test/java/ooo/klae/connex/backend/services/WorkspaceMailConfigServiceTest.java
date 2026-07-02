package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.WorkspaceMailConfig;
import ooo.klae.connex.backend.dto.MailConfigRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mail.EmailTemplateRenderer;
import ooo.klae.connex.backend.mail.MailConfigResolver;
import ooo.klae.connex.backend.mail.MailProperties;
import ooo.klae.connex.backend.mail.MailService;
import ooo.klae.connex.backend.mail.SecretCipher;
import ooo.klae.connex.backend.mappers.MailConfigMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.tenant.Permission;

/**
 * Verifies workspace SMTP config management: permission gating, password
 * encryption at rest, blank-password preservation, enable-time validation, and
 * the SSRF host guard.
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceMailConfigServiceTest {

    @Mock private MailConfigMapper mailConfigMapper;
    @Mock private WorkspaceService workspaceService;
    @Mock private AuditService auditService;
    @Mock private SecretCipher secretCipher;
    @Mock private MailConfigResolver mailConfigResolver;
    @Mock private MailService mailService;
    @Mock private EmailTemplateRenderer templateRenderer;
    @Mock private UserMapper userMapper;

    private final MailProperties mailProperties = new MailProperties();

    @BeforeEach
    void allowInternalHostsByDefault() {
        mailProperties.setAllowInternalHosts(true);
    }

    private WorkspaceMailConfigService service() {
        return new WorkspaceMailConfigService(mailConfigMapper, workspaceService, auditService,
                secretCipher, mailConfigResolver, mailService, templateRenderer, userMapper, mailProperties);
    }

    private MailConfigRequest enabledRequest() {
        MailConfigRequest req = new MailConfigRequest();
        req.setEnabled(true);
        req.setHost("smtp.test");
        req.setFromAddress("no-reply@test");
        return req;
    }

    @Test
    void getConfig_requiresWorkspaceSettingsPermission() {
        service().getConfig(3, 9);
        verify(workspaceService).requirePermission(3, 9, Permission.WORKSPACE_SETTINGS);
    }

    @Test
    void saveConfig_encryptsPassword_neverStoresPlaintext() {
        when(secretCipher.encrypt("rawpw")).thenReturn("ENC::rawpw");
        MailConfigRequest req = enabledRequest();
        req.setPassword("rawpw");

        service().saveConfig(3, 9, req);

        ArgumentCaptor<WorkspaceMailConfig> captor = ArgumentCaptor.forClass(WorkspaceMailConfig.class);
        verify(secretCipher).encrypt("rawpw");
        verify(mailConfigMapper).upsert(captor.capture());
        assertEquals("ENC::rawpw", captor.getValue().getPasswordEnc());
        assertFalse("rawpw".equals(captor.getValue().getPasswordEnc()), "password must be stored encrypted");
    }

    @Test
    void saveConfig_blankPassword_preservesStoredPassword() {
        WorkspaceMailConfig existing = new WorkspaceMailConfig();
        existing.setPasswordEnc("OLD-ENC");
        when(mailConfigMapper.findByWorkspace(3)).thenReturn(existing);

        service().saveConfig(3, 9, enabledRequest());

        ArgumentCaptor<WorkspaceMailConfig> captor = ArgumentCaptor.forClass(WorkspaceMailConfig.class);
        verify(mailConfigMapper).upsert(captor.capture());
        assertEquals("OLD-ENC", captor.getValue().getPasswordEnc());
        verify(secretCipher, never()).encrypt(any());
    }

    @Test
    void saveConfig_enabledWithoutHost_rejected() {
        MailConfigRequest req = new MailConfigRequest();
        req.setEnabled(true);
        req.setFromAddress("no-reply@test");
        assertThrows(BadRequestException.class, () -> service().saveConfig(3, 9, req));
        verify(mailConfigMapper, never()).upsert(any());
    }

    @Test
    void saveConfig_enabledWithoutFrom_rejected() {
        MailConfigRequest req = new MailConfigRequest();
        req.setEnabled(true);
        req.setHost("smtp.test");
        assertThrows(BadRequestException.class, () -> service().saveConfig(3, 9, req));
    }

    @Test
    void saveConfig_requiresPermission() {
        service().saveConfig(3, 9, enabledRequest());
        verify(workspaceService).requirePermission(eq(3), eq(9), eq(Permission.WORKSPACE_SETTINGS));
    }

    @Test
    void saveConfig_loopbackHost_rejectedWhenInternalHostsBlocked() {
        mailProperties.setAllowInternalHosts(false);
        MailConfigRequest req = enabledRequest();
        req.setHost("127.0.0.1");
        assertThrows(BadRequestException.class, () -> service().saveConfig(3, 9, req));
        verify(mailConfigMapper, never()).upsert(any());
    }

    @Test
    void saveConfig_privateHost_rejectedWhenInternalHostsBlocked() {
        mailProperties.setAllowInternalHosts(false);
        MailConfigRequest req = enabledRequest();
        req.setHost("10.0.0.5");
        assertThrows(BadRequestException.class, () -> service().saveConfig(3, 9, req));
    }

    @Test
    void saveConfig_linkLocalMetadataHost_rejectedWhenInternalHostsBlocked() {
        mailProperties.setAllowInternalHosts(false);
        MailConfigRequest req = enabledRequest();
        req.setHost("169.254.169.254");
        assertThrows(BadRequestException.class, () -> service().saveConfig(3, 9, req));
    }

    @Test
    void saveConfig_publicHost_allowedWhenInternalHostsBlocked() {
        mailProperties.setAllowInternalHosts(false);
        MailConfigRequest req = enabledRequest();
        req.setHost("8.8.8.8");
        service().saveConfig(3, 9, req);
        verify(mailConfigMapper).upsert(any());
    }

    @Test
    void sendTest_noWorkspaceConfig_returnsFailure() {
        User actor = new User();
        actor.setEmail("owner@test");
        when(userMapper.getUserById(9)).thenReturn(actor);
        when(mailConfigResolver.resolveWorkspaceOnly(3)).thenReturn(null);
        assertFalse(service().sendTest(3, 9).success());
    }

    @Test
    void deleteConfig_deletesAndRequiresPermission() {
        service().deleteConfig(3, 9);
        verify(workspaceService).requirePermission(3, 9, Permission.WORKSPACE_SETTINGS);
        verify(mailConfigMapper).delete(3);
        assertTrue(true);
    }
}
