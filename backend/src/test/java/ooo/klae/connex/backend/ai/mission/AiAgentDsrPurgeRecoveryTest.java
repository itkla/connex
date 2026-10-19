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
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import ooo.klae.connex.backend.beans.AuditLog;
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
        when(system.user()).thenReturn(principal);
        var scope = new TenantWorkScope(context, mock(TenantCatalogResolver.class), workspaces);
        var recorder = mock(JobRunRecorder.class);
        var service = new AiAgentDsrPurgeService(requests, mock(DataSubjectRequestControlOperations.class),
            receipts, mock(AiAgentPrivacyMapper.class), mock(AiAgentPrivacyService.class),
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
