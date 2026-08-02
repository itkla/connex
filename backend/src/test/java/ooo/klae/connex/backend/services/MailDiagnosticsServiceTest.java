package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.MailDiagnosticTestDto;
import ooo.klae.connex.backend.dto.MailDiagnosticTestDto.Dns;
import ooo.klae.connex.backend.dto.MailDiagnosticTestDto.DnsRecord;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.RecentAuthenticationRequiredException;
import ooo.klae.connex.backend.mail.EmailTemplateRenderer;
import ooo.klae.connex.backend.mail.MailConfigResolver;
import ooo.klae.connex.backend.mail.MailDnsDiagnosticsService;
import ooo.klae.connex.backend.mail.MailMessage;
import ooo.klae.connex.backend.mail.MailService;
import ooo.klae.connex.backend.mail.ResolvedMailConfig;
import ooo.klae.connex.backend.mail.SmtpDestinationGuard;
import ooo.klae.connex.backend.mappers.UserMapper;

class MailDiagnosticsServiceTest {
    private static final int WORKSPACE_ID = 17;
    private static final int ACTOR_ID = 29;
    private static final String ACTOR_EMAIL = "actor@example.com";

    private SessionSecurityService sessionSecurityService;
    private UserMapper userMapper;
    private MailConfigResolver mailConfigResolver;
    private SmtpDestinationGuard smtpDestinationGuard;
    private MailService mailService;
    private MailDnsDiagnosticsService dnsDiagnosticsService;
    private AuditService auditService;
    private MailDiagnosticsService service;

    private boolean managedMail;

    @BeforeEach
    void setUp() {
        sessionSecurityService = mock(SessionSecurityService.class);
        userMapper = mock(UserMapper.class);
        mailConfigResolver = mock(MailConfigResolver.class);
        when(mailConfigResolver.effectiveMode(any())).thenAnswer(invocation -> {
            ResolvedMailConfig resolved = invocation.getArgument(0);
            if (managedMail) {
                return "managed";
            }
            if (resolved == null || !resolved.usable()) {
                return "unconfigured";
            }
            return resolved.workspaceSupplied() ? "workspace_override" : "instance_default";
        });
        smtpDestinationGuard = mock(SmtpDestinationGuard.class);
        mailService = mock(MailService.class);
        dnsDiagnosticsService = mock(MailDnsDiagnosticsService.class);
        auditService = mock(AuditService.class);
        service = new MailDiagnosticsService(
                sessionSecurityService,
                userMapper,
                mailConfigResolver,
                smtpDestinationGuard,
                new EmailTemplateRenderer(),
                mailService,
                dnsDiagnosticsService,
                auditService);
        when(userMapper.getUserById(ACTOR_ID)).thenReturn(actor());
        when(dnsDiagnosticsService.diagnose(any())).thenReturn(dns());
    }

    @Test
    void managedTransportSucceedsToExactlyTheActorEmail() {
        managedMail = true;
        when(mailConfigResolver.resolveForWorkspace(WORKSPACE_ID))
                .thenReturn(config(false));

        MailDiagnosticTestDto result = service.testSend(WORKSPACE_ID, ACTOR_ID);

        assertEquals("managed", result.transport().mode());
        assertEquals("succeeded", result.transport().outcome());
        assertRecipientAndAudit();
        verify(mailConfigResolver, never()).resolveWorkspaceOnly(WORKSPACE_ID);
    }

    @Test
    void workspaceOverrideSucceedsWithoutConfigurationMutation() {
        when(mailConfigResolver.resolveForWorkspace(WORKSPACE_ID))
                .thenReturn(config(true));

        MailDiagnosticTestDto result = service.testSend(WORKSPACE_ID, ACTOR_ID);

        assertEquals("workspace_override", result.transport().mode());
        assertEquals("succeeded", result.transport().outcome());
        assertRecipientAndAudit();
        verify(mailConfigResolver, never()).resolveWorkspaceOnly(WORKSPACE_ID);
    }

    @Test
    void instanceFallbackSucceedsAndDnsUnknownDoesNotAlterOutcome() {
        when(mailConfigResolver.resolveForWorkspace(WORKSPACE_ID))
                .thenReturn(config(false));
        when(dnsDiagnosticsService.diagnose("sender@example.com"))
                .thenReturn(new Dns(
                        true,
                        "example.com",
                        new DnsRecord("example.com", "unknown", 0),
                        new DnsRecord("", "not_configured", 0),
                        new DnsRecord("_dmarc.example.com", "unknown", 0)));

        MailDiagnosticTestDto result = service.testSend(WORKSPACE_ID, ACTOR_ID);

        assertEquals("instance_default", result.transport().mode());
        assertEquals("succeeded", result.transport().outcome());
        assertEquals("unknown", result.dns().spf().status());
        assertRecipientAndAudit();
    }

    @Test
    void recentAuthenticationIsRequiredBeforeActorOrTransportLoad() {
        doThrow(new RecentAuthenticationRequiredException())
                .when(sessionSecurityService).requireRecentAuthentication(ACTOR_ID);

        assertThrows(
                RecentAuthenticationRequiredException.class,
                () -> service.testSend(WORKSPACE_ID, ACTOR_ID));

        verifyNoInteractions(userMapper, mailConfigResolver, mailService, auditService);
    }

    @Test
    void destinationRejectionReturnsOnlyAStableRedactedCode() {
        when(mailConfigResolver.resolveForWorkspace(WORKSPACE_ID))
                .thenReturn(config(true));
        doThrow(new BadRequestException("sentinel destination detail"))
                .when(smtpDestinationGuard)
                .requirePublicDestination("smtp.example.com", 587);

        MailDiagnosticTestDto result = service.testSend(WORKSPACE_ID, ACTOR_ID);

        assertEquals("failed", result.transport().outcome());
        assertEquals("smtp_destination_rejected", result.transport().errorCode());
        assertFalse(result.toString().contains("sentinel"));
        verify(mailService, never()).sendNow(any(), any());
    }

    @Test
    void transportFailureReturnsOnlyAStableRedactedCode() {
        when(mailConfigResolver.resolveForWorkspace(WORKSPACE_ID))
                .thenReturn(config(false));
        doThrow(new IllegalStateException("password=credential-sentinel"))
                .when(mailService).sendNow(any(), any());

        MailDiagnosticTestDto result = service.testSend(WORKSPACE_ID, ACTOR_ID);

        assertEquals("failed", result.transport().outcome());
        assertEquals("smtp_transport_failed", result.transport().errorCode());
        assertFalse(result.toString().contains("credential-sentinel"));
        verify(auditService, never()).record(
                eq("workspace.mail_config.test"), any(), any(), any(), any(), any());
    }

    @Test
    void unconfiguredTransportReturnsWithoutAttemptingDelivery() {
        when(mailConfigResolver.resolveForWorkspace(WORKSPACE_ID)).thenReturn(null);

        MailDiagnosticTestDto result = service.testSend(WORKSPACE_ID, ACTOR_ID);

        assertEquals("unconfigured", result.transport().outcome());
        assertEquals("mail_unconfigured", result.transport().errorCode());
        verifyNoInteractions(mailService, auditService);
    }

    @Test
    void credentialResolutionFailureReturnsOnlyAStableRedactedCode() {
        when(mailConfigResolver.resolveForWorkspace(WORKSPACE_ID))
                .thenThrow(new IllegalStateException("ciphertext=credential-sentinel"));

        MailDiagnosticTestDto result = service.testSend(WORKSPACE_ID, ACTOR_ID);

        assertEquals("failed", result.transport().outcome());
        assertEquals("mail_resolution_failed", result.transport().errorCode());
        assertFalse(result.toString().contains("credential-sentinel"));
        verifyNoInteractions(mailService, auditService);
    }

    @Test
    void usernameFallbackIsNeverReturnedAsTheSenderAddress() {
        String credentialUsername = "credential-sentinel@example.com";
        when(mailConfigResolver.resolveForWorkspace(WORKSPACE_ID))
                .thenReturn(new ResolvedMailConfig(
                        "smtp.example.com",
                        587,
                        credentialUsername,
                        "credential-password",
                        credentialUsername,
                        "Connex",
                        true,
                        false,
                        true,
                        1000,
                        1000,
                        1000,
                        true));

        MailDiagnosticTestDto result = service.testSend(WORKSPACE_ID, ACTOR_ID);

        assertEquals("succeeded", result.transport().outcome());
        assertNull(result.sender().address());
        assertFalse(result.toString().contains(credentialUsername));
        assertRecipientAndAudit();
    }

    private void assertRecipientAndAudit() {
        ArgumentCaptor<MailMessage> message = ArgumentCaptor.forClass(MailMessage.class);
        verify(mailService).sendNow(any(), message.capture());
        assertEquals(ACTOR_EMAIL, message.getValue().to());
        verify(auditService).record(
                "workspace.mail_config.test",
                "workspace",
                WORKSPACE_ID,
                ACTOR_EMAIL,
                "Sent a diagnostic test email",
                null);
    }

    private static User actor() {
        User user = new User();
        user.setId(ACTOR_ID);
        user.setEmail(ACTOR_EMAIL);
        user.setLocale("en");
        return user;
    }

    private static ResolvedMailConfig config(boolean workspaceSupplied) {
        return new ResolvedMailConfig(
                "smtp.example.com",
                587,
                "credential-user",
                "credential-password",
                "sender@example.com",
                "Connex",
                true,
                false,
                true,
                1000,
                1000,
                1000,
                workspaceSupplied);
    }

    private static Dns dns() {
        return new Dns(
                true,
                "example.com",
                new DnsRecord("example.com", "present", 1),
                new DnsRecord("", "not_configured", 0),
                new DnsRecord("_dmarc.example.com", "present", 1));
    }
}
