package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Pins how one rule action is isolated inside a durable delivery (#1928): only the failed action's
 * work and side effects are undone, transient failures retry the whole delivery, and outside a
 * transaction nothing changes.
 */
class RuleActionIsolationTest {
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final SqlSessionTemplate sqlSessionTemplate = mock(SqlSessionTemplate.class);
    private final RuleActionIsolation isolation = new RuleActionIsolation(transactionManager, sqlSessionTemplate);

    @BeforeEach
    void insideADeliveryTransaction() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    @AfterEach
    void clearTransactionState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    /**
     * A failed action's mention emails, pushes and after-commit listeners must not fire for work its
     * savepoint undid, yet every callback still completes, so deferred audits and lock bookkeeping run.
     */
    @Test
    void aFailedActionsSynchronizationsNeverSeeACommitWhileEarlierOnesDo() {
        RecordingSynchronization earlier = new RecordingSynchronization();
        TransactionSynchronizationManager.registerSynchronization(earlier);
        RecordingSynchronization fromFailedAction = new RecordingSynchronization();

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> isolation.run(() -> {
            TransactionSynchronizationManager.registerSynchronization(fromFailedAction);
            throw new IllegalStateException("action failed");
        }));

        assertEquals("action failed", failure.getMessage());
        verify(transactionManager).rollback(any());
        verify(sqlSessionTemplate).clearCache();
        commitDelivery();
        assertEquals(List.of("beforeCommit", "beforeCompletion", "afterCommit", "afterCompletion:0"),
                earlier.events);
        assertEquals(List.of("beforeCommit", "beforeCompletion", "afterCompletion:1"),
                fromFailedAction.events);
    }

    @Test
    void aSuccessfulActionKeepsItsSynchronizationsAndReleasesItsSavepoint() {
        RecordingSynchronization fromAction = new RecordingSynchronization();

        isolation.run(() -> TransactionSynchronizationManager.registerSynchronization(fromAction));

        verify(transactionManager).commit(any());
        verify(sqlSessionTemplate, never()).clearCache();
        assertSame(fromAction, TransactionSynchronizationManager.getSynchronizations().getFirst());
    }

    /**
     * A lock wait or deadlock is not the action's fault, and a deadlock takes the delivery's transaction
     * with it, so the whole delivery must roll back and be retried instead of finishing "partial".
     */
    @Test
    void transientFailuresRetryTheWholeDelivery() {
        for (RuntimeException transientFailure : List.of(
                new CannotAcquireLockException("lock wait timeout"),
                new QueryTimeoutException("query timeout"),
                new IllegalStateException("wrapped", new CannotAcquireLockException("deadlock")))) {
            RuleActionRetryRequiredException retry = assertThrows(RuleActionRetryRequiredException.class,
                    () -> isolation.run(() -> {
                        throw transientFailure;
                    }));
            assertSame(transientFailure, retry.getCause());
        }
    }

    /** A savepoint the database already discarded means the delivery's transaction is gone. */
    @Test
    void aLostSavepointRetriesTheWholeDelivery() {
        doThrow(new TransactionSystemException("Could not roll back to savepoint"))
                .when(transactionManager).rollback(any());

        assertThrows(RuleActionRetryRequiredException.class, () -> isolation.run(() -> {
            throw new IllegalStateException("action failed");
        }));
    }

    /**
     * An action that swallows a participating failure leaves the transaction rollback-only, so releasing
     * its savepoint rolls it back instead: that is an ordinary action failure, not a reason to retry.
     */
    @Test
    void aSwallowedParticipatingFailureIsAnOrdinaryActionFailure() {
        RecordingSynchronization fromAction = new RecordingSynchronization();
        doThrow(new UnexpectedRollbackException("rolled back to savepoint"))
                .when(transactionManager).commit(any());

        assertThrows(UnexpectedRollbackException.class,
                () -> isolation.run(() -> TransactionSynchronizationManager.registerSynchronization(fromAction)));

        commitDelivery();
        assertEquals(List.of("beforeCommit", "beforeCompletion", "afterCompletion:1"), fromAction.events);
    }

    @Test
    void withoutATransactionTheActionRunsDirectly() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        AtomicBoolean ran = new AtomicBoolean();

        isolation.run(() -> ran.set(true));
        CannotAcquireLockException direct = new CannotAcquireLockException("no delivery to retry");
        CannotAcquireLockException thrown = assertThrows(CannotAcquireLockException.class,
                () -> isolation.run(() -> {
                    throw direct;
                }));

        assertTrue(ran.get());
        assertSame(direct, thrown);
        verifyNoInteractions(transactionManager, sqlSessionTemplate);
    }

    private static void commitDelivery() {
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        synchronizations.forEach(synchronization -> synchronization.beforeCommit(false));
        synchronizations.forEach(TransactionSynchronization::beforeCompletion);
        synchronizations.forEach(TransactionSynchronization::afterCommit);
        synchronizations.forEach(synchronization ->
                synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
    }

    private static final class RecordingSynchronization implements TransactionSynchronization {
        private final List<String> events = new ArrayList<>();

        @Override
        public void beforeCommit(boolean readOnly) {
            events.add("beforeCommit");
        }

        @Override
        public void beforeCompletion() {
            events.add("beforeCompletion");
        }

        @Override
        public void afterCommit() {
            events.add("afterCommit");
        }

        @Override
        public void afterCompletion(int status) {
            events.add("afterCompletion:" + status);
        }
    }
}
