package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import ooo.klae.connex.backend.beans.AuditLog;
import ooo.klae.connex.backend.beans.AiAssistantPrivacyOperation;
import ooo.klae.connex.backend.beans.AiAgentPrivacyWork;
import ooo.klae.connex.backend.ai.assistant.AiAssistantPrivacyService;
import ooo.klae.connex.backend.support.TestTransactionManager;
import ooo.klae.connex.backend.dto.AiAgentHoldingsPurgeRequest;
import ooo.klae.connex.backend.mappers.AiAgentDsrControlMapper;
import ooo.klae.connex.backend.mappers.AiAgentPrivacyMapper;
import ooo.klae.connex.backend.mappers.TenantLifecycleControlMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.observability.JobRunRecorder;
import ooo.klae.connex.backend.observability.JobRunRecorder.JobRunStatus;
import ooo.klae.connex.backend.services.DataSubjectRequestControlOperations;
import ooo.klae.connex.backend.services.DataSubjectRequestService;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;
import tools.jackson.databind.ObjectMapper;

/** Recovery failures remain observable without logging exception payloads or accepted metadata. */
class AiAgentDsrPurgeRecoveryTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deletedWorkspaceAllowsC2OnlyWithPositiveDurableControlReceipt(boolean verified) {
        var fixture = new RecoveryFixture();
        if (verified) {
            when(fixture.authority.deletedWorkspaceVerifiedAt(11, fixture.accepted.operationId(), 8))
                .thenReturn(LocalDateTime.of(2026, 10, 19, 1, 0));
        }

        fixture.service.recoverAccepted();

        if (verified) {
            verify(fixture.operations).completeMissionPurge(fixture.accepted);
            var current = fixture.service.get(11, 19, 5, fixture.accepted.operationId());
            assertEquals("complete", current.state());
            assertEquals(2, current.completedWorkspaceCount());
            assertEquals("2026-10-19T01:00:00Z", current.completedAt());
        } else {
            verify(fixture.operations, never()).completeMissionPurge(any());
            verify(fixture.recorder).record(eq(JobRunRecorder.AI_AGENT_DSR_RECOVERY), isNull(),
                eq(JobRunStatus.FAILED), any());
        }
        verify(fixture.privacy).enrollOperation(7, fixture.operation);
        verify(fixture.privacy, never()).enrollOperation(eq(8), any());
        fixture.context.clear();
    }

    @Test
    void liveWorkspaceWithMissingTenantWorkIsReenrolledAndCannotCompleteFromHistoricalVerification() {
        var fixture = new RecoveryFixture();
        when(fixture.authority.deletedWorkspaceVerifiedAt(11, fixture.accepted.operationId(), 8))
            .thenReturn(LocalDateTime.of(2026, 10, 19, 1, 0));
        when(fixture.work.getWork(7, fixture.accepted.operationId())).thenReturn(null);

        fixture.service.recoverAccepted();

        verify(fixture.privacy).enrollOperation(7, fixture.operation);
        verify(fixture.operations, never()).completeMissionPurge(any());
        assertEquals("invalidation_pending", fixture.service.get(11, 19, 5, fixture.accepted.operationId()).state());
        fixture.context.clear();
    }

    private static final class RecoveryFixture {
        final AiAgentPurgeAcceptance accepted = new AiAgentPurgeAcceptance(42, 11, 19, UUID.randomUUID().toString(),
            UUID.randomUUID().toString(), List.of(7, 8), "2026-10-18T00:00:00Z", 2,
            AiAgentPurgeAcceptance.ASSISTANT_SCOPE, AiAgentPurgeAcceptance.ASSISTANT_CUTOFF_POLICY, 1L, 2L);
        final AiAssistantPrivacyService authority = mock(AiAssistantPrivacyService.class);
        final AiAgentPrivacyService privacy = mock(AiAgentPrivacyService.class);
        final AiAgentPrivacyMapper work = mock(AiAgentPrivacyMapper.class);
        final DataSubjectRequestControlOperations operations = mock(DataSubjectRequestControlOperations.class);
        final JobRunRecorder recorder = mock(JobRunRecorder.class);
        final AiAssistantPrivacyOperation operation = new AiAssistantPrivacyOperation();
        final TenantContext context = new TenantContext();
        final AiAgentDsrPurgeService service;

        RecoveryFixture() {
            var json = new ObjectMapper();
            var receipts = mock(AiAgentDsrControlMapper.class);
            var receipt = new AuditLog();
            receipt.setId(accepted.auditId());
            receipt.setOrgId(accepted.orgId());
            receipt.setAction(AiAgentPurgeAcceptance.ACCEPTED);
            receipt.setChanges(json.writeValueAsString(accepted.metadata()));
            when(receipts.pendingAcceptances(0, 20)).thenReturn(List.of(receipt));
            AtomicReference<AuditLog> completion = new AtomicReference<>();
            when(receipts.findCompletion(11, 19, accepted.operationId())).thenAnswer(call -> completion.get());
            org.mockito.Mockito.doAnswer(call -> {
                completion.set(new AuditLog());
                return null;
            }).when(operations).completeMissionPurge(accepted);
            when(operations.missionPurgeOperation(11, 19, 5, accepted.operationId())).thenReturn(accepted);
            operation.setOrganizationId(11);
            operation.setOperationKey(accepted.operationId());
            operation.setCutoffGeneration(1);
            operation.setInvalidatedGeneration(2);
            operation.setDsrRequestId(19L);
            operation.setControlAcceptanceAuditId(42);
            when(authority.findOperation(11, accepted.operationId())).thenReturn(operation);
            when(authority.operationWorkspaceIds(11, accepted.operationId())).thenReturn(List.of(7, 8));
            var complete = new AiAgentPrivacyWork();
            complete.setContentScope(AiAgentPurgeAcceptance.ASSISTANT_SCOPE);
            complete.setPhase("complete");
            complete.setLastResidualCount(0L);
            complete.setVerifiedAt(LocalDateTime.of(2026, 10, 19, 0, 0));
            complete.setCompletedAt(complete.getVerifiedAt());
            when(work.getWork(7, accepted.operationId())).thenReturn(complete);
            var workspaces = mock(WorkspaceMapper.class);
            when(workspaces.getOrgId(7)).thenReturn(11);
            var lifecycle = mock(TenantLifecycleControlMapper.class);
            when(lifecycle.lockWorkspaceForShare(7)).thenReturn(
                new ooo.klae.connex.backend.dto.WorkspaceLifecycleRef(7, 11, "Live", "live", "active"));
            var system = mock(ooo.klae.connex.backend.services.SystemActor.class);
            var principal = new ooo.klae.connex.backend.beans.User();
            principal.setId(1);
            when(system.readUser()).thenReturn(principal);
            service = new AiAgentDsrPurgeService(mock(DataSubjectRequestService.class), operations,
                receipts, work, privacy, authority, lifecycle,
                new TenantWorkScope(context, mock(TenantCatalogResolver.class), workspaces), context, system,
                new TestTransactionManager(), json, recorder);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void immediateAndScheduledRecoveryLogOnlySafeFailureMetadata(boolean scheduled) {
        var json = new ObjectMapper();
        var accepted = new AiAgentPurgeAcceptance(42, 11, 19, UUID.randomUUID().toString(),
            UUID.randomUUID().toString(), List.of(7), "2026-10-18T00:00:00Z");
        var request = new AiAgentHoldingsPurgeRequest(accepted.idempotencyKey(), "snapshot",
            AiAgentPurgeAcceptance.SCOPE, AiAgentPurgeAcceptance.CUTOFF_POLICY);
        var requests = mock(DataSubjectRequestService.class);
        when(requests.acceptMissionPurge(11, 19, 5, request)).thenReturn(accepted);
        var receipts = mock(AiAgentDsrControlMapper.class);
        var receipt = new AuditLog();
        receipt.setId(accepted.auditId());
        receipt.setOrgId(accepted.orgId());
        receipt.setAction(AiAgentPurgeAcceptance.ACCEPTED);
        receipt.setChanges(json.writeValueAsString(accepted.metadata()));
        when(receipts.pendingAcceptances(0, 20)).thenReturn(List.of(receipt));
        when(receipts.findCompletion(11, 19, accepted.operationId()))
            .thenThrow(new IllegalStateException("sensitive-exception-payload")).thenReturn(null);
        var workspaces = mock(WorkspaceMapper.class);
        when(workspaces.getOrgId(7)).thenReturn(11);
        var context = new TenantContext();
        var system = mock(ooo.klae.connex.backend.services.SystemActor.class);
        var principal = new ooo.klae.connex.backend.beans.User();
        principal.setId(1);
        when(system.readUser()).thenReturn(principal);
        var scope = new TenantWorkScope(context, mock(TenantCatalogResolver.class), workspaces);
        var recorder = mock(JobRunRecorder.class);
        var service = new AiAgentDsrPurgeService(requests, mock(DataSubjectRequestControlOperations.class),
            receipts, mock(AiAgentPrivacyMapper.class), mock(AiAgentPrivacyService.class),
            mock(ooo.klae.connex.backend.ai.assistant.AiAssistantPrivacyService.class),
            mock(TenantLifecycleControlMapper.class), scope, context, system, mock(PlatformTransactionManager.class), json, recorder);
        var logger = (Logger) LoggerFactory.getLogger(AiAgentDsrPurgeService.class);
        var events = new ListAppender<ILoggingEvent>();
        events.start();
        logger.addAppender(events);
        try {
            if (scheduled) {
                service.recoverAccepted();
                verify(recorder).record(eq(JobRunRecorder.AI_AGENT_DSR_RECOVERY), isNull(),
                    eq(JobRunStatus.FAILED), any());
            } else {
                assertEquals("invalidation_pending", service.initiate(11, 19, 5, request).state());
            }
            assertEquals(1, events.list.size());
            var event = events.list.getFirst();
            assertTrue(event.getFormattedMessage().contains("jobName=ai_agent_dsr_recovery"));
            assertTrue(event.getFormattedMessage().contains("exceptionClass=IllegalStateException"));
            assertTrue(event.getFormattedMessage().contains("phase=" + (scheduled ? "recovery" : "initiation")));
            assertFalse(event.getFormattedMessage().contains("sensitive-exception-payload"));
            assertFalse(event.getFormattedMessage().contains(accepted.operationId()));
            assertNull(event.getThrowableProxy());
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }
}
