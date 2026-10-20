package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.TransactionExecution;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class RequestTransactionCommitListenerTest {
    private final RequestTransactionCommitListener listener = new RequestTransactionCommitListener();

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void successfulPhysicalCommitsMarkOnlyTheirOwnRequest() {
        bindRequest();
        assertFalse(RequestTransactionCommitListener.committedDuringRequest());

        listener.afterCommit(transaction(true), null);

        assertTrue(RequestTransactionCommitListener.committedDuringRequest());
        bindRequest();
        assertFalse(RequestTransactionCommitListener.committedDuringRequest());
    }

    @Test
    void failedCommitsRollbacksAndSavepointReleasesDoNotMarkTheRequest() {
        bindRequest();

        listener.afterCommit(transaction(true), new IllegalStateException("commit failed"));
        listener.afterRollback(transaction(true), null);
        listener.afterCommit(transaction(false), null);

        assertFalse(RequestTransactionCommitListener.committedDuringRequest());
    }

    @Test
    void executionWithoutABoundRequestIsANoOp() {
        RequestContextHolder.resetRequestAttributes();

        listener.afterCommit(transaction(true), null);

        assertFalse(RequestTransactionCommitListener.committedDuringRequest());
        bindRequest();
        assertFalse(RequestTransactionCommitListener.committedDuringRequest());
    }

    private static TransactionExecution transaction(boolean physical) {
        TransactionExecution transaction = mock(TransactionExecution.class);
        when(transaction.isNewTransaction()).thenReturn(physical);
        return transaction;
    }

    private static void bindRequest() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }
}
