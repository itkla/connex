package ooo.klae.connex.backend.config;

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

    @Test
    void aFullLockTableFailsTheCommitAsALockFailure() {
        SQLException tableFull = failure(RolledBackTransactionGuard.LOCK_TABLE_FULL);

        guard.observe(tableFull);

        CannotAcquireLockException refused = assertThrows(
                CannotAcquireLockException.class, () -> commitCallbacks().getFirst().beforeCommit(false));
        assertSame(tableFull, refused.getCause());
    }

    /** A lock-wait timeout rolls back only its statement unless the server says otherwise. */
    @Test
    void aLockWaitTimeoutPoisonsOnlyWhenTheServerRollsBackOnTimeout() {
        guard.observe(failure(RolledBackTransactionGuard.LOCK_WAIT_TIMEOUT));
        assertTrue(commitCallbacks().isEmpty());

        new RolledBackTransactionGuard(dataSource, true)
                .observe(failure(RolledBackTransactionGuard.LOCK_WAIT_TIMEOUT));

        assertThrows(CannotAcquireLockException.class, () -> commitCallbacks().getFirst().beforeCommit(true));
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
                return Ordered.HIGHEST_PRECEDENCE + 1;
            }
        });

        guard.observe(failure(RolledBackTransactionGuard.DEADLOCK));
        guard.observe(failure(RolledBackTransactionGuard.DEADLOCK));

        List<TransactionSynchronization> callbacks = commitCallbacks();
        assertEquals(2, callbacks.size());
        assertThrows(DeadlockLoserDataAccessException.class, () -> callbacks.getFirst().beforeCommit(false));
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
        TransactionSynchronizationManager.unbindResource(dataSource);
        TransactionSynchronizationManager.bindResource(other, new ConnectionHolder(mock(Connection.class)));
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
