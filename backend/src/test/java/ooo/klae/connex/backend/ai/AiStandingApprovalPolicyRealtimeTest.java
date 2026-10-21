package ooo.klae.connex.backend.ai;

import static org.mockito.Mockito.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AiStandingApprovalPolicyRealtimeTest {
    @AfterEach
    void clear() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void outerTransactionsPublishOnlyAfterCommitAndRollbackPublishesNothing() {
        var access = mock(AiStandingApprovalControlAccess.class);
        var publisher = new AiStandingApprovalPolicyRealtime(access, null, null, null);
        TransactionSynchronizationManager.initSynchronization();
        publisher.publishAfterCommit(3);
        verifyNoInteractions(access);
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
        verify(access).execute(any());
        clearInvocations(access);
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.initSynchronization();
        publisher.publishAfterCommit(3);
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCompletion(1));
        verifyNoInteractions(access);
    }

    @Test
    void alreadyCommittedFacadePublishesImmediately() {
        var access = mock(AiStandingApprovalControlAccess.class);
        new AiStandingApprovalPolicyRealtime(access, null, null, null).publishAfterCommit(3);
        verify(access).execute(any());
    }
}
