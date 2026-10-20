package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import ooo.klae.connex.backend.beans.AiAssistantPrivacyFence;
import ooo.klae.connex.backend.beans.AiAssistantPrivacyOperation;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.mappers.AiAssistantPrivacyControlMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

class AiAssistantPrivacyServiceTest {
    private final AiAssistantPrivacyControlMapper control = mock(AiAssistantPrivacyControlMapper.class);
    private final WorkspaceMapper workspaces = mock(WorkspaceMapper.class);
    private final org.springframework.context.ApplicationEventPublisher events = mock(
            org.springframework.context.ApplicationEventPublisher.class);
    private final AiAssistantPrivacyService authority = new AiAssistantPrivacyService(control, workspaces, events);

    @Test
    void newOrganizationProvisioningDoesNotLockTheEmptyWorkspaceRange() {
        authority.provision(7);
        var accepted = org.mockito.ArgumentCaptor.forClass(AiAssistantPrivacyOperation.class);
        verify(control).insertOperation(accepted.capture());
        var operation = accepted.getValue();
        verify(control).insertFence(7, operation.getOperationKey());
        assertEquals(7, operation.getOrganizationId());
        assertEquals("bootstrap", operation.getReason());
        assertEquals(0, operation.getCutoffGeneration());
        assertEquals(1, operation.getInvalidatedGeneration());
        org.junit.jupiter.api.Assertions.assertArrayEquals(AiAssistantPrivacyService.fingerprint(
                "assistant-bootstrap-v1|7|" + operation.getOperationKey()), operation.getOperationFingerprint());
        verify(control, never()).reconcileWorkspaceIntents(org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test
    void missingAuthorityCannotBeCreatedByReadAdmission() {
        assertThrows(ServiceUnavailableException.class, () -> authority.current(7));
        verify(control, never()).insertFence(eq(7), any());
    }

    @Test
    void historicalOrganizationGenerationIsNeverRuntimeAuthority() {
        when(control.lockFenceForShare(7)).thenReturn(fence(1));
        assertThrows(ConflictException.class, () -> authority.retain(7, 0));
        assertEquals(1, authority.retain(7, 1));
    }

    @Test
    void organizationAdvanceAndDurableIntentsFollowExclusiveAuthority() {
        UUID key = UUID.randomUUID();
        when(control.lockFence(7)).thenReturn(fence(4));
        when(control.advanceFence(7, 4)).thenReturn(1);
        begin();
        try {
            AiAssistantPrivacyOperation operation = authority.invalidateOrganization(7, key, "restriction", null, null);
            assertEquals(4, operation.getCutoffGeneration());
            assertEquals(5, operation.getInvalidatedGeneration());
            assertEquals(AiAssistantPrivacyService.CONTENT_SCOPE, operation.getContentScope());
            var ordered = inOrder(control);
            ordered.verify(control).lockFence(7);
            ordered.verify(control).getOperation(7, key.toString());
            ordered.verify(control).insertOperation(operation);
            ordered.verify(control).advanceFence(7, 4);
            ordered.verify(control).reconcileWorkspaceIntents(7, key.toString());
            verify(events).publishEvent(new AiAssistantPrivacyService.Invalidated(7, key.toString(), 4));
        } finally {
            finish();
        }
    }

    @Test
    void sameKeyReplayPreservesCutoffAndCannotChangeScope() {
        UUID key = UUID.randomUUID();
        AiAssistantPrivacyOperation existing = new AiAssistantPrivacyOperation();
        existing.setReason("dsr");
        existing.setDsrRequestId(19L);
        existing.setCutoffGeneration(4);
        when(control.lockFence(7)).thenReturn(fence(12));
        when(control.getOperation(7, key.toString())).thenReturn(existing);
        begin();
        try {
            assertEquals(existing, authority.invalidateOrganization(7, key, "dsr", 19L, 21));
            assertThrows(ConflictException.class, () -> authority.invalidateOrganization(7, key, "restriction", null, null));
            verify(control, never()).advanceFence(eq(7), org.mockito.ArgumentMatchers.anyLong());
            org.mockito.Mockito.verifyNoInteractions(events);
        } finally {
            finish();
        }
    }

    @Test
    void missingOperationCannotReceiveCompletionEvidence() {
        String key = UUID.randomUUID().toString();
        when(control.lockFence(7)).thenReturn(fence(4));
        assertThrows(ConflictException.class, () -> authority.reconcileCompletion(7, key));
        verify(control, never()).completeOperation(7, key);
        verify(control, never()).completeBootstrap(7, key);
    }

    @Test
    void restoredOlderFenceCannotCompleteUnreconciledFutureOperation() {
        String key = UUID.randomUUID().toString();
        AiAssistantPrivacyOperation operation = new AiAssistantPrivacyOperation();
        operation.setInvalidatedGeneration(5);
        when(control.lockFence(7)).thenReturn(fence(4));
        when(control.lockOperation(7, key)).thenReturn(operation);
        assertThrows(ServiceUnavailableException.class, () -> authority.reconcileCompletion(7, key));
        verify(control, never()).completeOperation(7, key);
        verify(control, never()).completeBootstrap(7, key);
    }

    private static AiAssistantPrivacyFence fence(long generation) {
        AiAssistantPrivacyFence fence = new AiAssistantPrivacyFence();
        fence.setOrganizationId(7);
        fence.setGeneration(generation);
        return fence;
    }

    private static void begin() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    private static void finish() {
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }
        TransactionSynchronizationManager.clear();
    }
}
