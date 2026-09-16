package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.WorkspaceMailConfig;
import ooo.klae.connex.backend.dto.MailConfigDto;
import ooo.klae.connex.backend.dto.MailConfigRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mail.EmailTemplateRenderer;
import ooo.klae.connex.backend.mail.MailConfigResolver;
import ooo.klae.connex.backend.mail.MailMessage;
import ooo.klae.connex.backend.mail.MailProperties;
import ooo.klae.connex.backend.mail.MailService;
import ooo.klae.connex.backend.mail.ResolvedMailConfig;
import ooo.klae.connex.backend.mail.SecretCipher;
import ooo.klae.connex.backend.mail.SmtpDestinationGuard;
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
    @Mock private SessionSecurityService sessionSecurityService;

    private final MailProperties mailProperties = new MailProperties();

    @BeforeEach
    void configureDefaultMailProperties() {
        mailProperties.setManaged(false);
        mailProperties.setAllowInternalHosts(true);
    }

    private WorkspaceMailConfigService service() {
        return service(templateRenderer);
    }

    private WorkspaceMailConfigService service(EmailTemplateRenderer renderer) {
        return new WorkspaceMailConfigService(mailConfigMapper, workspaceService, auditService,
                secretCipher, mailConfigResolver, mailService, renderer, userMapper,
                sessionSecurityService, new SmtpDestinationGuard(mailProperties), mailProperties);
    }

    private MailConfigRequest enabledRequest() {
        MailConfigRequest req = new MailConfigRequest();
        req.setEnabled(true);
        req.setHost("smtp.test");
        req.setPort(587);
        req.setFromAddress("no-reply@test");
        return req;
    }

    @Test
    void getConfig_requiresWorkspaceSettingsPermission() {
        service().getConfig(3, 9);
        verify(workspaceService).requirePermission(3, 9, Permission.WORKSPACE_SETTINGS);
    }

    @Test
    void getConfig_managed_stillReadsWorkspaceConfig() {
        mailProperties.setManaged(true);
        WorkspaceMailConfig existing = new WorkspaceMailConfig();
        when(mailConfigMapper.findByWorkspace(3)).thenReturn(existing);

        service().getConfig(3, 9);

        verify(workspaceService).requirePermission(3, 9, Permission.WORKSPACE_SETTINGS);
        verify(mailConfigMapper).findByWorkspace(3);
    }

    @Test
    void getConfig_reportsTheResolvedInstanceDefaultPortInsteadOfALiteral() {
        mailProperties.setPort(2525);
        when(mailConfigMapper.findByWorkspace(3)).thenReturn(null);

        MailConfigDto unconfigured = service().getConfig(3, 9);

        assertEquals(2525, unconfigured.getDefaultPort());
        assertEquals(Integer.valueOf(2525), unconfigured.getPort());
    }

    @Test
    void getConfig_reportsTheInstanceDefaultAlongsideAStoredNullPort() {
        mailProperties.setPort(2525);
        WorkspaceMailConfig existing = new WorkspaceMailConfig();
        existing.setHost("smtp.test");
        existing.setPort(null);
        when(mailConfigMapper.findByWorkspace(3)).thenReturn(existing);

        MailConfigDto stored = service().getConfig(3, 9);

        assertEquals(2525, stored.getDefaultPort());
        assertNull(stored.getPort());
    }

    @Test
    void saveConfig_managed_rejectedWithoutWork() {
        mailProperties.setManaged(true);

        assertThrows(ForbiddenException.class, () -> service().saveConfig(3, 9, enabledRequest()));

        verifyNoInteractions(workspaceService, sessionSecurityService, mailConfigMapper, secretCipher,
                mailConfigResolver, mailService, templateRenderer, userMapper, auditService);
    }

    @Test
    void deleteConfig_managed_rejectedWithoutWork() {
        mailProperties.setManaged(true);

        assertThrows(ForbiddenException.class, () -> service().deleteConfig(3, 9));

        verifyNoInteractions(workspaceService, sessionSecurityService, mailConfigMapper, secretCipher,
                mailConfigResolver, mailService, templateRenderer, userMapper, auditService);
    }

    @Test
    void sendTest_managed_rejectedWithoutWork() {
        mailProperties.setManaged(true);

        assertThrows(ForbiddenException.class, () -> service().sendTest(3, 9));

        verifyNoInteractions(workspaceService, sessionSecurityService, mailConfigMapper, secretCipher,
                mailConfigResolver, mailService, templateRenderer, userMapper, auditService);
    }

    @Test
    void saveConfig_encryptsPassword_neverStoresPlaintext() {
        when(secretCipher.encryptForWorkspace(3, "rawpw")).thenReturn("secret:v1:99");
        MailConfigRequest req = enabledRequest();
        req.setPassword("rawpw");

        service().saveConfig(3, 9, req);

        verify(sessionSecurityService).requireRecentAuthentication(9);
        ArgumentCaptor<WorkspaceMailConfig> captor = ArgumentCaptor.forClass(WorkspaceMailConfig.class);
        verify(secretCipher).encryptForWorkspace(3, "rawpw");
        verify(mailConfigMapper).upsert(captor.capture());
        assertEquals("secret:v1:99", captor.getValue().getPasswordEnc());
        assertFalse("rawpw".equals(captor.getValue().getPasswordEnc()), "password must be stored encrypted");
    }

    @Test
    void saveConfig_blankPassword_preservesStoredPassword() {
        WorkspaceMailConfig existing = storedConfig();
        when(mailConfigMapper.findByWorkspaceForUpdate(3)).thenReturn(existing);
        MailConfigRequest request = enabledRequest();
        request.setFromAddress("new-sender@test");
        request.setFromName("New sender name");

        service().saveConfig(3, 9, request);

        ArgumentCaptor<WorkspaceMailConfig> captor = ArgumentCaptor.forClass(WorkspaceMailConfig.class);
        verify(mailConfigMapper).upsert(captor.capture());
        assertEquals("OLD-ENC", captor.getValue().getPasswordEnc());
        verify(secretCipher, never()).encryptForWorkspace(eq(3), any());
    }

    @ParameterizedTest
    @ValueSource(ints = {587, 2525})
    void saveConfig_inheritedPortMatchesResolvedDefaultForUnrelatedEdits(int defaultPort) {
        mailProperties.setPort(defaultPort);
        WorkspaceMailConfig existing = storedConfig();
        existing.setPort(null);
        existing.setUsername(" ");
        when(mailConfigMapper.findByWorkspaceForUpdate(3)).thenReturn(existing);
        MailConfigRequest request = enabledRequest();
        request.setPort(defaultPort);
        request.setFromName("Updated sender name");
        request.setPassword("");

        service().saveConfig(3, 9, request);

        ArgumentCaptor<WorkspaceMailConfig> saved = ArgumentCaptor.forClass(WorkspaceMailConfig.class);
        verify(mailConfigMapper).upsert(saved.capture());
        assertEquals("OLD-ENC", saved.getValue().getPasswordEnc());
        assertEquals("Updated sender name", saved.getValue().getFromName());
        verifyNoInteractions(secretCipher);
    }

    @Test
    void saveConfig_actualChangeFromInheritedPortStillRequiresPassword() {
        WorkspaceMailConfig existing = storedConfig();
        existing.setPort(null);
        when(mailConfigMapper.findByWorkspaceForUpdate(3)).thenReturn(existing);
        MailConfigRequest request = enabledRequest();
        request.setPort(465);

        assertThrows(BadRequestException.class, () -> service().saveConfig(3, 9, request));

        verify(mailConfigMapper, never()).upsert(any());
        verifyNoInteractions(secretCipher);
    }

    @ParameterizedTest
    @ValueSource(strings = {"host", "port", "username", "starttls", "ssl"})
    void saveConfig_blankPasswordRejectsChangedCredentialBindingBeforePersistenceOrEgress(String field) {
        when(mailConfigMapper.findByWorkspaceForUpdate(3)).thenReturn(storedConfig());
        MailConfigRequest request = changedConnection(field);
        request.setPassword(" ");

        BadRequestException failure = assertThrows(BadRequestException.class,
                () -> service().saveConfig(3, 9, request));

        assertEquals("Re-enter the credential to change the endpoint", failure.getMessage());
        verify(mailConfigMapper, never()).upsert(any());
        verifyNoInteractions(secretCipher, mailConfigResolver, mailService, auditService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"host", "port", "username", "starttls", "ssl"})
    void saveConfig_reenteredPasswordAllowsChangedCredentialBinding(String field) {
        when(mailConfigMapper.findByWorkspaceForUpdate(3)).thenReturn(storedConfig());
        when(secretCipher.encryptForWorkspace(3, "replacement")).thenReturn("secret:v1:100");
        MailConfigRequest request = changedConnection(field);
        request.setPassword("replacement");

        service().saveConfig(3, 9, request);

        ArgumentCaptor<WorkspaceMailConfig> captor = ArgumentCaptor.forClass(WorkspaceMailConfig.class);
        verify(mailConfigMapper).upsert(captor.capture());
        assertEquals("secret:v1:100", captor.getValue().getPasswordEnc());
    }

    private WorkspaceMailConfig storedConfig() {
        WorkspaceMailConfig config = new WorkspaceMailConfig();
        config.setEnabled(true);
        config.setHost("smtp.test");
        config.setPort(587);
        config.setStarttls(true);
        config.setAuth(true);
        config.setPasswordEnc("OLD-ENC");
        return config;
    }

    private MailConfigRequest changedConnection(String field) {
        MailConfigRequest request = enabledRequest();
        switch (field) {
            case "host" -> request.setHost("smtp.attacker.example");
            case "port" -> request.setPort(465);
            case "username" -> request.setUsername("different-account");
            case "starttls" -> request.setStarttls(false);
            case "ssl" -> request.setSsl(true);
            default -> throw new IllegalArgumentException("Unknown connection field");
        }
        return request;
    }

    @Test
    void saveConfig_disabledClearsStoredPassword() {
        WorkspaceMailConfig existing = new WorkspaceMailConfig();
        existing.setPasswordEnc("secret:v1:88");
        when(mailConfigMapper.findByWorkspaceForUpdate(3)).thenReturn(existing);
        MailConfigRequest req = enabledRequest();
        req.setEnabled(false);

        service().saveConfig(3, 9, req);

        ArgumentCaptor<WorkspaceMailConfig> captor = ArgumentCaptor.forClass(WorkspaceMailConfig.class);
        verify(mailConfigMapper).upsert(captor.capture());
        assertEquals(null, captor.getValue().getPasswordEnc());
        verify(secretCipher).deleteReferenceForWorkspace(3, "secret:v1:88");
    }

    @Test
    void saveConfig_authDisabledClearsStoredPassword() {
        WorkspaceMailConfig existing = new WorkspaceMailConfig();
        existing.setPasswordEnc("secret:v1:89");
        when(mailConfigMapper.findByWorkspaceForUpdate(3)).thenReturn(existing);
        MailConfigRequest req = enabledRequest();
        req.setAuth(false);

        service().saveConfig(3, 9, req);

        ArgumentCaptor<WorkspaceMailConfig> captor = ArgumentCaptor.forClass(WorkspaceMailConfig.class);
        verify(mailConfigMapper).upsert(captor.capture());
        assertEquals(null, captor.getValue().getPasswordEnc());
        verify(secretCipher).deleteReferenceForWorkspace(3, "secret:v1:89");
    }

    @Test
    void saveAndDeleteConfigAreTransactional() throws Exception {
        assertTrue(WorkspaceMailConfigService.class
            .getMethod("saveConfig", int.class, int.class, MailConfigRequest.class)
            .isAnnotationPresent(Transactional.class));
        assertTrue(WorkspaceMailConfigService.class
            .getMethod("deleteConfig", int.class, int.class)
            .isAnnotationPresent(Transactional.class));
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
    void saveConfig_omittedPortUsesTheInstanceDefaultForValidation() {
        mailProperties.setAllowInternalHosts(false);
        MailConfigRequest req = enabledRequest();
        req.setHost("8.8.8.8");
        req.setPort(null);

        service().saveConfig(3, 9, req);

        ArgumentCaptor<WorkspaceMailConfig> captor = ArgumentCaptor.forClass(WorkspaceMailConfig.class);
        verify(mailConfigMapper).upsert(captor.capture());
        assertEquals(null, captor.getValue().getPort());
    }

    @Test
    void saveConfig_disallowedPort_rejectedWhenInternalHostsBlocked() {
        mailProperties.setAllowInternalHosts(false);
        MailConfigRequest req = enabledRequest();
        req.setHost("8.8.8.8");
        req.setPort(8080);
        assertThrows(BadRequestException.class, () -> service().saveConfig(3, 9, req));
        verify(mailConfigMapper, never()).upsert(any());
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
    void sendTest_revalidatesResolvedHost_rejectsInternalTarget() {
        mailProperties.setAllowInternalHosts(false);
        User actor = new User();
        actor.setEmail("owner@test");
        when(userMapper.getUserById(9)).thenReturn(actor);
        ResolvedMailConfig internal = new ResolvedMailConfig("127.0.0.1", 587, null, null,
                "no-reply@test", "Connex", true, false, false, 10000, 10000, 10000, true);
        when(mailConfigResolver.resolveWorkspaceOnly(3)).thenReturn(internal);

        assertFalse(service().sendTest(3, 9).success());
        verify(mailService, never()).sendNow(any(), any());
    }

    @Test
    void sendTest_localizesJapaneseActorMessage() {
        User actor = new User();
        actor.setEmail("owner@test");
        actor.setLocale("ja");
        when(userMapper.getUserById(9)).thenReturn(actor);
        ResolvedMailConfig config = new ResolvedMailConfig("smtp.test", 587, null, null,
                "no-reply@test", "Connex", true, false, false, 10000, 10000, 10000, true);
        when(mailConfigResolver.resolveWorkspaceOnly(3)).thenReturn(config);

        assertTrue(service(new EmailTemplateRenderer()).sendTest(3, 9).success());

        ArgumentCaptor<MailMessage> message = ArgumentCaptor.forClass(MailMessage.class);
        verify(mailService).sendNow(eq(config), message.capture());
        assertEquals("Connex テストメール", message.getValue().subject());
        assertTrue(message.getValue().htmlBody().contains("lang=\"ja\""));
        assertTrue(message.getValue().htmlBody().contains("メール設定は正常です"));
        assertFalse(message.getValue().htmlBody().contains("{{"));
    }

    @Test
    void sendTest_fallsBackToEnglishForUntrustedStoredLocale() {
        User actor = new User();
        actor.setEmail("owner@test");
        actor.setLocale("../../ja");
        when(userMapper.getUserById(9)).thenReturn(actor);
        ResolvedMailConfig config = new ResolvedMailConfig("smtp.test", 587, null, null,
                "no-reply@test", "Connex", true, false, false, 10000, 10000, 10000, true);
        when(mailConfigResolver.resolveWorkspaceOnly(3)).thenReturn(config);

        assertTrue(service(new EmailTemplateRenderer()).sendTest(3, 9).success());

        ArgumentCaptor<MailMessage> message = ArgumentCaptor.forClass(MailMessage.class);
        verify(mailService).sendNow(eq(config), message.capture());
        assertEquals("Connex email test", message.getValue().subject());
        assertTrue(message.getValue().htmlBody().contains("lang=\"en\""));
        assertTrue(message.getValue().htmlBody().contains("Your email settings work"));
        assertFalse(message.getValue().htmlBody().contains("{{"));
    }

    @Test
    void saveConfig_carrierGradeNatHost_rejectedWhenInternalHostsBlocked() {
        mailProperties.setAllowInternalHosts(false);
        MailConfigRequest req = enabledRequest();
        req.setHost("100.64.1.1");
        assertThrows(BadRequestException.class, () -> service().saveConfig(3, 9, req));
        verify(mailConfigMapper, never()).upsert(any());
    }

    @Test
    void deleteConfig_deletesAndRequiresPermission() {
        WorkspaceMailConfig existing = new WorkspaceMailConfig();
        existing.setPasswordEnc("secret:v1:44");
        when(mailConfigMapper.findByWorkspaceForUpdate(3)).thenReturn(existing);
        service().deleteConfig(3, 9);
        verify(workspaceService).requirePermission(3, 9, Permission.WORKSPACE_SETTINGS);
        verify(sessionSecurityService).requireRecentAuthentication(9);
        verify(mailConfigMapper).delete(3);
        verify(secretCipher).deleteReferenceForWorkspace(3, "secret:v1:44");
        assertTrue(true);
    }
}
