package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Pins which database failures poison the transaction they arrive in (#1947): only those after which
 * MySQL has already rolled the whole transaction back, and only inside a Spring transaction on the
 * guarded data source.
 */
class RolledBackTransactionGuardTest {
    private final DataSource dataSource = mock(DataSource.class);
    private final RolledBackTransactionGuard guard = new RolledBackTransactionGuard(dataSource, false);

    @BeforeEach
    void insideATransactionOnTheGuardedDataSource() {
        ConnectionHolder holder = new ConnectionHolder(mock(Connection.class));
        holder.setSynchronizedWithTransaction(true);
        TransactionSynchronizationManager.bindResource(dataSource, holder);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    @AfterEach
    void clearTransactionState() {
        if (TransactionSynchronizationManager.hasResource(dataSource)) {
            TransactionSynchronizationManager.unbindResource(dataSource);
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void aDeadlockFailsTheCommitAsTheDeadlockItWas() {
        SQLException deadlock = failure(RolledBackTransactionGuard.DEADLOCK);

        guard.observe(deadlock);

        DeadlockLoserDataAccessException refused = assertThrows(
                DeadlockLoserDataAccessException.class, () -> commitCallbacks().getFirst().beforeCommit(false));
        assertSame(deadlock, refused.getCause());
    }

    /** Unswallowed, a full lock table is uncategorized and not retried; swallowed, it stays so. */
    @Test
    void aFullLockTableFailsTheCommitAsTheUncategorizedFailureItIs() {
        SQLException tableFull = failure(RolledBackTransactionGuard.LOCK_TABLE_FULL);

        guard.observe(tableFull);

        UncategorizedSQLException refused = assertThrows(
                UncategorizedSQLException.class, () -> commitCallbacks().getFirst().beforeCommit(false));
        assertSame(tableFull, refused.getCause());
    }

    /** A lock-wait timeout rolls back only its statement unless the server says otherwise. */
    @Test
    void aLockWaitTimeoutPoisonsOnlyWhenTheServerRollsBackOnTimeout() {
        guard.observe(failure(RolledBackTransactionGuard.LOCK_WAIT_TIMEOUT));
        assertTrue(commitCallbacks().isEmpty());

        SQLException timeout = failure(RolledBackTransactionGuard.LOCK_WAIT_TIMEOUT);
        new RolledBackTransactionGuard(dataSource, true).observe(timeout);

        CannotAcquireLockException refused = assertThrows(
                CannotAcquireLockException.class, () -> commitCallbacks().getFirst().beforeCommit(true));
        assertSame(timeout, refused.getCause());
    }

    @Test
    void failuresThatLeaveTheTransactionStandingLeaveItAlone() {
        guard.observe(failure(1062));
        guard.observe(failure(3819));
        guard.observe(new SQLException("no vendor code"));

        assertTrue(commitCallbacks().isEmpty());
    }

    @Test
    void aTransactionIsPoisonedOnceAndAheadOfEveryOtherCommitCallback() {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public int getOrder() {
                return Ordered.HIGHEST_PRECEDENCE;
            }
        });

        guard.observe(failure(RolledBackTransactionGuard.DEADLOCK));
        guard.observe(failure(RolledBackTransactionGuard.DEADLOCK));

        List<TransactionSynchronization> callbacks = commitCallbacks();
        assertEquals(2, callbacks.size());
        assertThrows(DeadlockLoserDataAccessException.class, () -> callbacks.getFirst().beforeCommit(false));
    }

    /** Without synchronization there is nothing to register with, and observing must not fail. */
    @Test
    void withoutSynchronizationNothingIsPoisoned() {
        TransactionSynchronizationManager.clearSynchronization();

        assertDoesNotThrow(() -> guard.observe(failure(RolledBackTransactionGuard.DEADLOCK)));
    }

    /** A connection bound outside a transaction runs in auto-commit, so it has no transaction to poison. */
    @Test
    void aConnectionNotSynchronizedWithTheTransactionIsLeftAlone() {
        ((ConnectionHolder) TransactionSynchronizationManager.getResource(dataSource))
                .setSynchronizedWithTransaction(false);

        guard.observe(failure(RolledBackTransactionGuard.DEADLOCK));

        assertTrue(commitCallbacks().isEmpty());
    }

    @Test
    void withoutATransactionNothingIsPoisoned() {
        TransactionSynchronizationManager.setActualTransactionActive(false);

        guard.observe(failure(RolledBackTransactionGuard.DEADLOCK));

        assertTrue(commitCallbacks().isEmpty());
    }

    /** A deadlock on another data source's connection says nothing about this transaction. */
    @Test
    void aTransactionOnAnotherDataSourceIsLeftAlone() {
        DataSource other = mock(DataSource.class);
        ConnectionHolder otherHolder = new ConnectionHolder(mock(Connection.class));
        otherHolder.setSynchronizedWithTransaction(true);
        TransactionSynchronizationManager.unbindResource(dataSource);
        TransactionSynchronizationManager.bindResource(other, otherHolder);
        try {
            guard.observe(failure(RolledBackTransactionGuard.DEADLOCK));

            assertTrue(commitCallbacks().isEmpty());
        } finally {
            TransactionSynchronizationManager.unbindResource(other);
        }
    }

    private static List<TransactionSynchronization> commitCallbacks() {
        return TransactionSynchronizationManager.getSynchronizations();
    }

    private static SQLException failure(int errorCode) {
        return new SQLException("MySQL error " + errorCode, "40001", errorCode);
    }
}
