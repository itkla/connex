package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.AiProviderReadiness;
import ooo.klae.connex.backend.beans.JobRun;
import ooo.klae.connex.backend.capability.Capability;
import ooo.klae.connex.backend.capability.CapabilityRegistry;
import ooo.klae.connex.backend.config.DeploymentProperties;
import ooo.klae.connex.backend.delivery.DeliveryChannel;
import ooo.klae.connex.backend.delivery.DeliveryProviderReadiness;
import ooo.klae.connex.backend.dto.ProviderCaptureDiagnosticsRow;
import ooo.klae.connex.backend.dto.SecretStoreDiagnosticsDto;
import ooo.klae.connex.backend.dto.TenantDiagnosticsDto;
import ooo.klae.connex.backend.dto.TenantDiagnosticsDto.Finding;
import ooo.klae.connex.backend.dto.TenantDiagnosticsDto.Job;
import ooo.klae.connex.backend.dto.TenantDiagnosticsDto.WorkspaceProviders;
import ooo.klae.connex.backend.mail.MailConfigResolver;
import ooo.klae.connex.backend.mail.ResolvedMailConfig;
import ooo.klae.connex.backend.mappers.JobRunMapper;
import ooo.klae.connex.backend.mappers.ProviderCaptureMapper;
import ooo.klae.connex.backend.observability.JobRunRecorder;
import ooo.klae.connex.backend.secrets.SecretStoreLifecycleService;
import ooo.klae.connex.backend.services.OrganizationWorkspaceScopeControlOperations.WorkspaceScope;
import tools.jackson.databind.ObjectMapper;

class TenantDiagnosticsServiceTest {
    private static final int ORG_ID = 7;
    private static final int WORKSPACE_ID = 11;
    private static final int ACTOR_ID = 17;

    private DeploymentProperties deploymentProperties;
    private CapabilityRegistry capabilityRegistry;
    private AiProviderReadiness aiProviderReadiness;
    private MailConfigResolver mailConfigResolver;
    private DeliveryProviderReadiness deliveryProviderReadiness;
    private BusinessCardService businessCardService;
    private ProviderCaptureMapper providerCaptureMapper;
    private JobRunMapper jobRunMapper;
    private OrganizationWorkspaceScopeControlAccess scopeControlAccess;
    private TenantDiagnosticsTenantAccess tenantAccess;
    private SecretStoreLifecycleService secretStoreLifecycleService;
    private TenantDiagnosticsService service;

    private boolean managedMail;

    @BeforeEach
    void setUp() {
        deploymentProperties = new DeploymentProperties();
        capabilityRegistry = mock(CapabilityRegistry.class);
        aiProviderReadiness = mock(AiProviderReadiness.class);
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
        deliveryProviderReadiness = mock(DeliveryProviderReadiness.class);
        businessCardService = mock(BusinessCardService.class);
        providerCaptureMapper = mock(ProviderCaptureMapper.class);
        jobRunMapper = mock(JobRunMapper.class);
        scopeControlAccess = mock(OrganizationWorkspaceScopeControlAccess.class);
        tenantAccess = mock(TenantDiagnosticsTenantAccess.class);
        secretStoreLifecycleService = mock(SecretStoreLifecycleService.class);
        service = new TenantDiagnosticsService(
                deploymentProperties,
                capabilityRegistry,
                aiProviderReadiness,
                mailConfigResolver,
                deliveryProviderReadiness,
                businessCardService,
                providerCaptureMapper,
                jobRunMapper,
                scopeControlAccess,
                tenantAccess,
                secretStoreLifecycleService,
                new ObjectMapper());
        when(tenantAccess.inWorkspace(anyInt(), anyInt(), anyInt(), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(3)).get());
        when(tenantAccess.inOrganization(any(), anyInt(), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(2)).get());
        when(secretStoreLifecycleService.diagnosticsForWorkspace(anyInt()))
                .thenReturn(new SecretStoreDiagnosticsDto());
        when(secretStoreLifecycleService.diagnosticsForOrg(anyInt()))
                .thenReturn(new SecretStoreDiagnosticsDto());
        when(providerCaptureMapper.findDiagnosticsAggregates(anyInt(), any()))
                .thenReturn(List.of());
        when(jobRunMapper.findLatestVisible(anyInt(), any(), isNull())).thenReturn(List.of());
        when(jobRunMapper.findLatestVisible(anyInt(), any(), eq("succeeded"))).thenReturn(List.of());
        when(jobRunMapper.findLatestVisible(anyInt(), any(), eq("failed"))).thenReturn(List.of());
    }

    @Test
    void composesCapabilityMailDeliveryCaptureFindingsAndJobSelections() throws Exception {
        deploymentProperties.setProfile(DeploymentProperties.PROFILE_ON_PREM);
        when(capabilityRegistry.isAvailable(Capability.MANAGED_MAIL)).thenReturn(true);
        when(scopeControlAccess.getForWorkspace(WORKSPACE_ID))
                .thenReturn(new WorkspaceScope(ORG_ID, List.of(WORKSPACE_ID, 12), "[11,12]"));
        when(mailConfigResolver.resolveForWorkspace(WORKSPACE_ID)).thenReturn(config(true));
        when(deliveryProviderReadiness.isReady(WORKSPACE_ID, DeliveryChannel.EMAIL)).thenReturn(true);
        when(deliveryProviderReadiness.isReady(WORKSPACE_ID, DeliveryChannel.SMS)).thenReturn(false);
        when(providerCaptureMapper.findDiagnosticsAggregates(WORKSPACE_ID, "[11]"))
                .thenReturn(List.of(
                        capture("account_paused", 2, 1, 0, 5, 8, "2026-07-01 01:00:00"),
                        capture("policy_paused", 3, 0, 2, 7, 9, "2026-07-02 01:00:00")));
        when(aiProviderReadiness.isReadyForOrg(ORG_ID)).thenReturn(true);
        when(aiProviderReadiness.isImageInputReadyForOrg(ORG_ID)).thenReturn(false);
        JobRun last = run(
                3,
                "failed",
                "{\"phase\":\"delivery_failed\",\"password\":\"credential-sentinel\"}");
        JobRun success = run(2, "succeeded", "{\"recipientCount\":2}");
        JobRun global = run(
                4,
                "succeeded",
                "{\"phase\":\"catalog_sweep\",\"recipientCount\":99,"
                        + "\"password\":\"credential-sentinel\"}");
        global.setJobName(JobRunRecorder.NOTIFICATION_RECONCILIATION);
        global.setWorkspaceId(null);
        when(jobRunMapper.findLatestVisible(WORKSPACE_ID, "[11]", null))
                .thenReturn(List.of(last, global));
        when(jobRunMapper.findLatestVisible(WORKSPACE_ID, "[11]", "succeeded")).thenReturn(List.of(success));
        when(jobRunMapper.findLatestVisible(WORKSPACE_ID, "[11]", "failed")).thenReturn(List.of(last));

        TenantDiagnosticsDto result = service.forWorkspace(WORKSPACE_ID, ACTOR_ID);

        assertEquals("workspace", result.scope().type());
        assertEquals(DeploymentProperties.PROFILE_ON_PREM, result.deployment().profile());
        TenantDiagnosticsDto.CapabilityState managed = result.deployment().capabilities().stream()
                .filter(capability -> "managed_mail".equals(capability.capability()))
                .findFirst()
                .orElseThrow();
        assertFalse(managed.profileAllowed());
        assertTrue(managed.available());
        WorkspaceProviders workspace = result.providers().workspaces().getFirst();
        assertEquals("workspace_override", workspace.mail().mode());
        assertTrue(workspace.mail().configured());
        assertTrue(workspace.delivery().getFirst().ready());
        assertFalse(workspace.delivery().get(1).ready());
        assertFalse(workspace.delivery().get(2).implemented());
        assertFalse(workspace.delivery().get(3).implemented());
        assertEquals(1, workspace.capture().size());
        TenantDiagnosticsDto.Capture capture = workspace.capture().getFirst();
        assertEquals(5, capture.stateCount());
        assertEquals(1, capture.stableCursorCount());
        assertEquals(2, capture.pageCursorCount());
        assertEquals(12, capture.processedItems());
        assertEquals(17, capture.estimatedItems());
        assertEquals("2026-07-02 01:00:00", capture.lastSuccessAt());
        assertEquals(List.of("account_paused", "policy_paused"), capture.errorCodes());
        Set<String> findingCodes = result.findings().stream()
                .map(Finding::code)
                .collect(Collectors.toSet());
        assertTrue(findingCodes.contains("ai_provider_incomplete"));
        assertTrue(findingCodes.contains("delivery_provider_missing"));
        assertTrue(findingCodes.contains("capture_paused"));
        assertFalse(findingCodes.contains("mail_unconfigured"));
        Job report = result.jobs().stream()
                .filter(job -> JobRunRecorder.REPORT_DELIVERY.equals(job.jobName()))
                .findFirst()
                .orElseThrow();
        assertEquals(3, report.last().id());
        assertEquals(2, report.lastSuccess().id());
        assertEquals(3, report.lastFailure().id());
        assertEquals("delivery_failed", report.last().detail().get("phase"));
        assertFalse(report.last().detail().containsKey("password"));
        Job notification = result.jobs().stream()
                .filter(job -> JobRunRecorder.NOTIFICATION_RECONCILIATION.equals(job.jobName()))
                .findFirst()
                .orElseThrow();
        assertEquals(Map.of("phase", "catalog_sweep"), notification.last().detail());
        assertFalse(new ObjectMapper().writeValueAsString(result).contains("credential-sentinel"));
        verify(jobRunMapper).findLatestVisible(WORKSPACE_ID, "[11]", null);
        verify(jobRunMapper).findLatestVisible(WORKSPACE_ID, "[11]", "succeeded");
        verify(jobRunMapper).findLatestVisible(WORKSPACE_ID, "[11]", "failed");
    }

    @Test
    void organizationReportsOverrideFallbackAndUnconfiguredMailModes() {
        WorkspaceScope scope = new WorkspaceScope(ORG_ID, List.of(11, 12, 13), "[11,12,13]");
        when(scopeControlAccess.getForOrg(ORG_ID)).thenReturn(scope);
        when(mailConfigResolver.resolveForWorkspace(11)).thenReturn(config(true));
        when(mailConfigResolver.resolveForWorkspace(12)).thenReturn(config(false));
        when(mailConfigResolver.resolveForWorkspace(13)).thenReturn(null);

        TenantDiagnosticsDto result = service.forOrganization(ORG_ID, ACTOR_ID);

        assertEquals(List.of("workspace_override", "instance_default", "unconfigured"),
                result.providers().workspaces().stream()
                        .map(workspace -> workspace.mail().mode())
                        .toList());
        assertTrue(result.findings().stream().anyMatch(finding ->
                "mail_unconfigured".equals(finding.code())
                        && Integer.valueOf(13).equals(finding.workspaceId())));
    }

    @Test
    void managedModeRemainsManagedEvenWhenTransportIsUnconfigured() {
        managedMail = true;
        when(scopeControlAccess.getForWorkspace(WORKSPACE_ID))
                .thenReturn(new WorkspaceScope(ORG_ID, List.of(WORKSPACE_ID), "[11]"));
        when(mailConfigResolver.resolveForWorkspace(WORKSPACE_ID)).thenReturn(null);

        TenantDiagnosticsDto result = service.forWorkspace(WORKSPACE_ID, ACTOR_ID);

        assertEquals("managed", result.providers().workspaces().getFirst().mail().mode());
        assertFalse(result.providers().workspaces().getFirst().mail().configured());
    }

    @Test
    void corruptStoredMailCredentialDegradesToRedactedFindings() {
        when(scopeControlAccess.getForWorkspace(WORKSPACE_ID))
                .thenReturn(new WorkspaceScope(ORG_ID, List.of(WORKSPACE_ID), "[11]"));
        when(mailConfigResolver.resolveForWorkspace(WORKSPACE_ID))
                .thenThrow(new IllegalStateException("ciphertext=credential-sentinel"));
        when(deliveryProviderReadiness.isReady(WORKSPACE_ID, DeliveryChannel.EMAIL))
                .thenThrow(new IllegalStateException("password=credential-sentinel"));

        TenantDiagnosticsDto result = service.forWorkspace(WORKSPACE_ID, ACTOR_ID);

        assertFalse(result.providers().workspaces().getFirst().mail().configured());
        assertFalse(result.providers().workspaces().getFirst().delivery().getFirst().ready());
        assertTrue(result.findings().stream().anyMatch(finding ->
                "mail_unconfigured".equals(finding.code())));
        assertFalse(result.toString().contains("credential-sentinel"));
    }

    @Test
    void emptyWorkspaceOrganizationReturnsNoTenantSections() {
        when(scopeControlAccess.getForOrg(ORG_ID))
                .thenReturn(new WorkspaceScope(ORG_ID, List.of(), "[]"));

        TenantDiagnosticsDto result = service.forOrganization(ORG_ID, ACTOR_ID);

        assertTrue(result.providers().workspaces().isEmpty());
        assertTrue(result.jobs().isEmpty());
        verify(tenantAccess, never()).inOrganization(any(), anyInt(), any());
        verifyNoInteractions(providerCaptureMapper, jobRunMapper, mailConfigResolver);
        assertNull(result.deployment().profile());
    }

    private static ProviderCaptureDiagnosticsRow capture(
            String errorCode,
            long stateCount,
            long stableCursorCount,
            long pageCursorCount,
            long processedItems,
            long estimatedItems,
            String lastSuccessAt) {
        return new ProviderCaptureDiagnosticsRow(
                WORKSPACE_ID,
                "google",
                "mail_inbox",
                "paused",
                errorCode,
                stateCount,
                stableCursorCount,
                pageCursorCount,
                processedItems,
                estimatedItems,
                "2026-07-03 01:00:00",
                lastSuccessAt,
                null);
    }

    private static JobRun run(int id, String status, String detail) {
        JobRun run = new JobRun();
        run.setId(id);
        run.setJobName(JobRunRecorder.REPORT_DELIVERY);
        run.setWorkspaceId(WORKSPACE_ID);
        run.setStatus(status);
        run.setStartedAt(LocalDateTime.of(2026, 7, id, 1, 0));
        run.setFinishedAt(LocalDateTime.of(2026, 7, id, 1, 1));
        run.setDetail(detail);
        return run;
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
}
