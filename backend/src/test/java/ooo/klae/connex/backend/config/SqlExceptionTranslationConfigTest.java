package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.support.SQLExceptionTranslator;
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

    /** Every translated failure reaches the guard first, so a deadlock poisons its transaction (#1947). */
    @Test
    void aTranslatedDeadlockPoisonsTheTransactionItArrivedIn() {
        ConnectionHolder holder = new ConnectionHolder(mock(Connection.class));
        holder.setSynchronizedWithTransaction(true);
        TransactionSynchronizationManager.bindResource(dataSource, holder);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            sqlExceptionTranslator.translate(
                "lock", null, new SQLException("Deadlock found", "40001", RolledBackTransactionGuard.DEADLOCK));

            assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());
            assertThrows(DeadlockLoserDataAccessException.class,
                () -> TransactionSynchronizationManager.getSynchronizations().getFirst().beforeCommit(false));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
            TransactionSynchronizationManager.unbindResource(dataSource);
        }
    }
}
