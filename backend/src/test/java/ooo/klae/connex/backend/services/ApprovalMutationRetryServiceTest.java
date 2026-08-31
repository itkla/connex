package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import ooo.klae.connex.backend.exceptions.ConflictException;

class ApprovalMutationRetryServiceTest {
    @Test
    void retriesChangedRecipientSnapshotsInFreshTransactionBoundaries() {
        PlatformTransactionManager transactionManager = transactionManager();
        ApprovalMutationRetryService service =
            new ApprovalMutationRetryService(transactionManager);
        AtomicInteger attempts = new AtomicInteger();

        String result = service.execute(() -> {
            if (attempts.incrementAndGet() < ApprovalMutationRetryService.MAX_ATTEMPTS) {
                throw new ApprovalRecipientSetChangedException();
            }
            return "done";
        });

        assertEquals("done", result);
        assertEquals(ApprovalMutationRetryService.MAX_ATTEMPTS, attempts.get());
        verify(transactionManager, times(2)).rollback(any());
        verify(transactionManager).commit(any());
        ArgumentCaptor<TransactionDefinition> definitions =
            ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager, times(ApprovalMutationRetryService.MAX_ATTEMPTS))
            .getTransaction(definitions.capture());
        assertEquals(List.of(
            TransactionDefinition.PROPAGATION_REQUIRES_NEW,
            TransactionDefinition.PROPAGATION_REQUIRES_NEW,
            TransactionDefinition.PROPAGATION_REQUIRES_NEW),
            definitions.getAllValues().stream()
                .map(TransactionDefinition::getPropagationBehavior)
                .toList());
    }

    @Test
    void changedRecipientSnapshotExhaustionSurfacesStandardConflict() {
        PlatformTransactionManager transactionManager = transactionManager();
        ApprovalMutationRetryService service =
            new ApprovalMutationRetryService(transactionManager);
        AtomicInteger attempts = new AtomicInteger();

        assertThrows(ConflictException.class, () -> service.execute(() -> {
            attempts.incrementAndGet();
            throw new ApprovalRecipientSetChangedException();
        }));

        assertEquals(ApprovalMutationRetryService.MAX_ATTEMPTS, attempts.get());
        verify(transactionManager, times(ApprovalMutationRetryService.MAX_ATTEMPTS))
            .rollback(any());
    }

    private static PlatformTransactionManager transactionManager() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
            .thenAnswer(invocation -> mock(TransactionStatus.class));
        return transactionManager;
    }
}
