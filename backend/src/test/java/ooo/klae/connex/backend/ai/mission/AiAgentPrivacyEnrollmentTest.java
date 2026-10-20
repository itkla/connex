package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.ai.assistant.AiAssistantPrivacyService;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseService;
import ooo.klae.connex.backend.beans.AiAgentPrivacyBarrier;
import ooo.klae.connex.backend.beans.AiAgentPrivacyWatermarks;
import ooo.klae.connex.backend.beans.AiAgentPrivacyWork;
import ooo.klae.connex.backend.beans.AiAssistantPrivacyFence;
import ooo.klae.connex.backend.beans.AiAssistantPrivacyOperation;
import ooo.klae.connex.backend.mappers.AiAgentPrivacyMapper;
import ooo.klae.connex.backend.mappers.AiAgentPrivacyObjectMapper;
import ooo.klae.connex.backend.mappers.AiAgentRunMapper;
import ooo.klae.connex.backend.mappers.AiAssistantPrivacyControlMapper;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
import ooo.klae.connex.backend.mappers.ObjectDeletionQueueMapper;
import ooo.klae.connex.backend.mappers.TenantLifecycleControlMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.storage.ManagedObjectService;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Restored tenant work cannot borrow completion evidence from newer control coordination. */
class AiAgentPrivacyEnrollmentTest {
    private final AiAgentPrivacyMapper work = mock(AiAgentPrivacyMapper.class);
    private final AiAssistantPrivacyControlMapper control = mock(AiAssistantPrivacyControlMapper.class);
    private final AiAssistantPrivacyFence fence = new AiAssistantPrivacyFence();
    private final AiAssistantPrivacyOperation operation = new AiAssistantPrivacyOperation();
    private final TenantContext context = new TenantContext();
    private AiAgentPrivacyService service;
    private AiAgentPrivacyWork local;
    private boolean verified;

    @BeforeEach
    void prepareCompletedPlacement() {
        String key = UUID.randomUUID().toString();
        context.set(7, 11, 3, "system", "restored_catalog");
        var catalogs = mock(TenantCatalogResolver.class);
        when(catalogs.resolveCatalog(11)).thenReturn("restored_catalog");
        var lifecycle = mock(TenantLifecycleControlMapper.class);
        when(lifecycle.findWorkspaceOrgIdForLifecycle(7)).thenReturn(11);
        var transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        var authority = new AiAssistantPrivacyService(control, mock(WorkspaceMapper.class),
                mock(ApplicationEventPublisher.class));
        service = new AiAgentPrivacyService(work, mock(AiAgentRunMapper.class), mock(AiChatMapper.class),
                mock(AiRestrictionEpoch.class), mock(AiRunLeaseService.class), transactions,
                mock(WorkspaceMapper.class), lifecycle, mock(UserMapper.class), mock(TenantWorkScope.class),
                catalogs, context, Clock.fixed(Instant.parse("2026-10-20T00:00:00Z"), ZoneOffset.UTC),
                authority, control, mock(AttachmentMapper.class), mock(AiAgentPrivacyObjectMapper.class),
                mock(ObjectDeletionQueueMapper.class), mock(ManagedObjectService.class));
        fence.setOrganizationId(11);
        fence.setGeneration(1);
        fence.setBootstrapOperationId(key);
        operation.setOrganizationId(11);
        operation.setOperationKey(key);
        operation.setContentScope(AiAssistantPrivacyService.CONTENT_SCOPE);
        operation.setReason("bootstrap");
        operation.setCutoffGeneration(0);
        operation.setInvalidatedGeneration(1);
        when(control.getFence(11)).thenReturn(fence);
        when(control.lockFence(11)).thenReturn(fence);
        when(control.lockOperation(11, key)).thenReturn(operation);
        when(control.lockWorkspaceIntent(11, key, 7)).thenReturn(7);
        when(control.listOperations(11)).thenReturn(List.of(operation));
        when(control.countUnverified(11, key)).thenAnswer(call -> verified ? 0L : 1L);
        doAnswer(call -> { verified = false; return 1; }).when(control).reopenWorkspace(11, key, 7);
        doAnswer(call -> { operation.setCompletedAt(null); return 1; }).when(control).reopenOperation(11, key);
        doAnswer(call -> { fence.setBootstrapCompletedAt(null); return 1; }).when(control).reopenBootstrap(11, key);
        var barrier = new AiAgentPrivacyBarrier();
        barrier.setWorkspaceId(7);
        barrier.setGeneration(1);
        when(work.lockBarrier(7)).thenReturn(barrier);
        when(work.lockBarrierForShare(7)).thenReturn(barrier);
        when(work.lockWork(7, key)).thenAnswer(call -> local);
        when(work.watermarks(7)).thenReturn(new AiAgentPrivacyWatermarks());
        doAnswer(call -> { local = call.getArgument(0); return 1; }).when(work).insertWork(any());
        when(work.updateWork(any(), anyLong())).thenReturn(1);
        service.enrollOperation(7, operation);
        local.setPhase("complete");
        local.setCompletedAt(LocalDateTime.of(2026, 10, 19, 0, 0));
        operation.setCompletedAt(local.getCompletedAt());
        fence.setBootstrapCompletedAt(local.getCompletedAt());
        verified = true;
        org.mockito.Mockito.clearInvocations(control);
    }

    @AfterEach
    void clearScope() {
        context.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "runs", "sessions", "verify"})
    void initializationReopensStaleReceiptForRestoredPreCleanupWork(String phase) {
        if ("missing".equals(phase)) {
            local = null;
        } else {
            local.setPhase(phase);
            local.setCompletedAt(null);
            local.setRevision(9);
            local.setLastSessionId(123);
        }

        service.initialize(7);

        assertFalse(verified);
        assertNull(operation.getCompletedAt());
        assertNull(fence.getBootstrapCompletedAt());
        assertNotNull(local);
        assertEquals("missing".equals(phase) ? "runs" : phase, local.getPhase());
        assertEquals("missing".equals(phase) ? 1 : 9, local.getRevision());
        assertEquals("missing".equals(phase) ? 0 : 123, local.getLastSessionId());
        assertEquals(0, local.getCutoffGeneration());
        assertEquals(1, local.getInvalidatedGeneration());
        assertEquals(1, fence.getGeneration());
        verify(control, never()).completeOperation(11, operation.getOperationKey());
        verify(control, never()).completeBootstrap(11, operation.getOperationKey());
        verify(control, never()).advanceFence(eq(11), anyLong());
    }

    @Test
    void completedLocalWorkWithRestoredResidualsAlsoReopensCompletion() {
        when(work.allChatResidualCount(7, 0, operation.getOperationKey())).thenReturn(1L);

        service.initialize(7);

        assertFalse(verified);
        assertNull(operation.getCompletedAt());
        assertNull(fence.getBootstrapCompletedAt());
        assertEquals("runs", local.getPhase());
        assertNull(local.getCompletedAt());
    }

    @Test
    void verifiedCompletedLocalWorkWithoutResidualsKeepsItsEvidence() {
        service.initialize(7);

        org.junit.jupiter.api.Assertions.assertTrue(verified);
        assertNotNull(operation.getCompletedAt());
        assertNotNull(fence.getBootstrapCompletedAt());
        assertEquals("complete", local.getPhase());
        verify(control, never()).reopenWorkspace(11, operation.getOperationKey(), 7);
        verify(control, never()).reopenOperation(11, operation.getOperationKey());
        verify(control, never()).reopenBootstrap(11, operation.getOperationKey());
    }
}
