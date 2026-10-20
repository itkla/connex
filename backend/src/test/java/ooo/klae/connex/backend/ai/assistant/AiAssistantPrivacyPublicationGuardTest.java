package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

class AiAssistantPrivacyPublicationGuardTest {
    private final AiAssistantPrivacyService authority = mock(AiAssistantPrivacyService.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final AiAssistantPrivacyPublicationGuard guard =
            new AiAssistantPrivacyPublicationGuard(authority, transactions);

    @Test
    void staleOriginalGenerationCannotWriteAfterFreshControlRead() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(authority.current(7)).thenReturn(2L);
        AtomicBoolean written = new AtomicBoolean();

        assertThrows(IOException.class, () -> guard.withCurrent(7, 1, () -> written.set(true)));

        assertFalse(written.get());
        verify(authority).current(7);
    }

    @Test
    void everyWriteRevalidatesOutsideTheCallersSnapshot() throws IOException {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(authority.current(7)).thenReturn(1L, 2L);
        AtomicBoolean written = new AtomicBoolean();
        guard.withCurrent(7, 1, () -> written.set(true));
        assertTrue(written.get());
        written.set(false);
        assertThrows(IOException.class, () -> guard.withCurrent(7, 1, () -> written.set(true)));
        assertFalse(written.get());
    }

    @Test
    void authorityReadUsesSeparateReadCommittedReadOnlyTransaction() throws IOException {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(authority.current(7)).thenReturn(1L);
        guard.withCurrent(7, 1, () -> {});

        ArgumentCaptor<TransactionDefinition> captured = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactions).getTransaction(captured.capture());
        assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW, captured.getValue().getPropagationBehavior());
        assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED, captured.getValue().getIsolationLevel());
        assertTrue(captured.getValue().isReadOnly());
    }

    @Test
    void failedOutputDoesNotRetainPublicationAuthority() throws IOException {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(authority.current(7)).thenReturn(1L);
        assertThrows(IOException.class, () -> guard.withCurrent(7, 1, () -> {
            throw new IOException("output stopped");
        }));
        AtomicBoolean written = new AtomicBoolean();
        guard.withCurrent(7, 1, () -> written.set(true));
        assertTrue(written.get());
    }
}
