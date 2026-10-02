package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTransactionRollbackException;
import java.sql.Statement;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.support.SQLExceptionTranslator;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class SqlExceptionTranslationConfigTest {
    private final DataSource dataSource = mock(DataSource.class);
    private final SQLExceptionTranslator sqlExceptionTranslator = new SqlExceptionTranslationConfig()
        .sqlExceptionTranslator(new RolledBackTransactionGuard(dataSource, false));

    @Test
    void unrelatedMySqlGeneralErrorIsNotClassifiedAsIntegrityViolation() {
        SQLException unrelated = new SQLException("Unrelated MySQL general error", "HY000", 3024);

        assertFalse(sqlExceptionTranslator.translate("query", null, unrelated)
            instanceof DataIntegrityViolationException);
    }

    @Test
    void unrecognizedCheckConstraintIsNotClassifiedAsIntegrityViolation() {
        SQLException unrecognized = new SQLException(
            "Check constraint 'chk_future_server_invariant' is violated.",
            "HY000",
            3819);

        assertFalse(sqlExceptionTranslator.translate("query", null, unrecognized)
            instanceof DataIntegrityViolationException);
    }

    /**
     * Every translated failure reaches the guard before the translator classifies it, so the
     * deadlock Connector/J reports poisons the transaction it arrived in (#1947).
     */
    @Test
    void aTranslatedDeadlockPoisonsTheTransactionItArrivedIn() {
        SQLException deadlock = new SQLTransactionRollbackException(
            "Deadlock found when trying to get lock", "40001", RolledBackTransactionGuard.DEADLOCK);

        List<TransactionSynchronization> callbacks = commitCallbacksAfter(dataSource,
            () -> sqlExceptionTranslator.translate("lock", null, deadlock));

        assertEquals(1, callbacks.size());
        DeadlockLoserDataAccessException refused = assertThrows(
            DeadlockLoserDataAccessException.class, () -> callbacks.getFirst().beforeCommit(false));
        assertEquals(deadlock, refused.getCause());
    }

    /** A server that keeps the transaction on a lock-wait timeout rolls back only the statement. */
    @Test
    void aServerThatKeepsTransactionsOnTimeoutLeavesALockWaitTimeoutAlone() throws SQLException {
        DataSource server = serverAnswering(false);
        RolledBackTransactionGuard guard = new SqlExceptionTranslationConfig().rolledBackTransactionGuard(server);

        assertTrue(commitCallbacksAfter(server, () -> guard.observe(lockWaitTimeout())).isEmpty());
    }

    /**
     * A server that rolls back on timeout, or one whose setting cannot be read, makes a lock-wait
     * timeout poison its transaction: assuming the worse can only fail a commit, never let one through.
     */
    @Test
    void aServerThatRollsBackOnTimeoutOrCannotSayPoisonsALockWaitTimeout() throws SQLException {
        DataSource unreachable = mock(DataSource.class);
        when(unreachable.getConnection()).thenThrow(new SQLException("Communications link failure"));

        for (DataSource server : List.of(serverAnswering(true), serverAnswering(null), unreachable)) {
            RolledBackTransactionGuard guard = new SqlExceptionTranslationConfig().rolledBackTransactionGuard(server);

            List<TransactionSynchronization> callbacks =
                commitCallbacksAfter(server, () -> guard.observe(lockWaitTimeout()));

            assertEquals(1, callbacks.size());
            assertThrows(CannotAcquireLockException.class, () -> callbacks.getFirst().beforeCommit(false));
        }
    }

    private static DataSource serverAnswering(Boolean rollbackOnTimeout) throws SQLException {
        DataSource server = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet result = mock(ResultSet.class);
        when(server.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT @@GLOBAL.innodb_rollback_on_timeout")).thenReturn(result);
        when(result.next()).thenReturn(rollbackOnTimeout != null);
        when(result.getBoolean(1)).thenReturn(Boolean.TRUE.equals(rollbackOnTimeout));
        return server;
    }

    private static SQLException lockWaitTimeout() {
        return new SQLTransactionRollbackException(
            "Lock wait timeout exceeded", "40001", RolledBackTransactionGuard.LOCK_WAIT_TIMEOUT);
    }

    /** Runs a translation inside a fake transaction on the data source and returns its commit callbacks. */
    private static List<TransactionSynchronization> commitCallbacksAfter(DataSource server, Runnable translation) {
        ConnectionHolder holder = new ConnectionHolder(mock(Connection.class));
        holder.setSynchronizedWithTransaction(true);
        try {
            TransactionSynchronizationManager.bindResource(server, holder);
            TransactionSynchronizationManager.initSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(true);
            translation.run();
            return TransactionSynchronizationManager.getSynchronizations();
        } finally {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.clearSynchronization();
            }
            TransactionSynchronizationManager.setActualTransactionActive(false);
            if (TransactionSynchronizationManager.hasResource(server)) {
                TransactionSynchronizationManager.unbindResource(server);
            }
        }
    }
}
