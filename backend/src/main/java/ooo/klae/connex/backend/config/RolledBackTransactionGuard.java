package ooo.klae.connex.backend.config;

import java.sql.SQLException;

import javax.sql.DataSource;

import org.springframework.core.Ordered;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Fails the commit of a transaction the database has already rolled back (#1947).
 *
 * <p>MySQL answers a deadlock (1213), a full lock table (1206) and, on a server running with
 * {@code innodb_rollback_on_timeout}, a lock-wait timeout (1205) by rolling back the whole
 * transaction, savepoints included, without poisoning the connection: the next statement silently
 * opens a new implicit transaction. Code that catches such a failure and carries on would therefore
 * commit only the work done after it. Both translation chains report every translated
 * {@link SQLException} here first; when one of these errors arrives inside a Spring transaction on
 * the application data source, the transaction gets a synchronization whose {@code beforeCommit}
 * throws the typed failure, so its commit rolls back and the failure reaches the caller as the
 * transient lock failure it is, which both workflow engines retry.
 *
 * <p>A deadlock swallowed inside another synchronization's {@code beforeCommit} or
 * {@code beforeCompletion} registers too late to be seen; nothing runs database work there that
 * swallows its failures.
 */
public final class RolledBackTransactionGuard {
    static final int LOCK_WAIT_TIMEOUT = 1205;
    static final int LOCK_TABLE_FULL = 1206;
    static final int DEADLOCK = 1213;

    private final DataSource dataSource;
    private final boolean rollbackOnTimeout;

    /**
     * Creates the guard for one data source.
     *
     * @param dataSource the data source whose transactions the guard poisons
     * @param rollbackOnTimeout whether the server rolls back the whole transaction on a lock-wait
     *        timeout
     */
    public RolledBackTransactionGuard(DataSource dataSource, boolean rollbackOnTimeout) {
        this.dataSource = dataSource;
        this.rollbackOnTimeout = rollbackOnTimeout;
    }

    /**
     * Poisons the current transaction when the database has already rolled it back.
     *
     * @param failure the SQL failure being translated
     */
    public void observe(SQLException failure) {
        if (!rolledBackWholeTransaction(failure.getErrorCode())
                || !TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()
                || !(TransactionSynchronizationManager.getResource(dataSource) instanceof ConnectionHolder holder)
                || !holder.isSynchronizedWithTransaction()
                || alreadyPoisoned()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new RolledBackTransaction(failure.getErrorCode(), failure));
    }

    private boolean rolledBackWholeTransaction(int errorCode) {
        return errorCode == DEADLOCK
                || errorCode == LOCK_TABLE_FULL
                || errorCode == LOCK_WAIT_TIMEOUT && rollbackOnTimeout;
    }

    private static boolean alreadyPoisoned() {
        return TransactionSynchronizationManager.getSynchronizations().stream()
                .anyMatch(RolledBackTransaction.class::isInstance);
    }

    /**
     * Refuses the commit of a transaction the database rolled back, ahead of every other commit
     * callback, so none of them writes into the implicit transaction that replaced it.
     */
    private record RolledBackTransaction(int errorCode, SQLException failure)
            implements TransactionSynchronization {
        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }

        @Override
        public void beforeCommit(boolean readOnly) {
            String message = "The database rolled this transaction back (MySQL error " + errorCode
                    + "), so it cannot commit";
            if (errorCode == DEADLOCK) {
                throw new DeadlockLoserDataAccessException(message, failure);
            }
            throw new CannotAcquireLockException(message, failure);
        }
    }
}
