package ooo.klae.connex.backend.exceptions;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.Predicate;

import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.CannotSerializeTransactionException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.TransactionSystemException;

/** Classifies database failures without relying on exception messages or SQL text. */
public final class DatabaseFailureClassifier {
    private DatabaseFailureClassifier() {
    }

    /**
     * Recognizes Spring pessimistic lock failures and MySQL deadlock (1213), lock-wait timeout
     * (1205), and NOWAIT (3572/HY000) failures. Causes and transaction application exceptions are
     * inspected by identity to terminate cycles; an unclassified rollback is not retryable.
     */
    public static boolean isLockContention(Throwable failure) {
        return anyFailure(failure, DatabaseFailureClassifier::lockContention);
    }

    /** Preserves the workflow Spring allowlist for a later attempt in a new transaction. */
    public static boolean isTransientDatabaseFailure(Throwable failure) {
        return anyFailure(failure, current -> current instanceof CannotAcquireLockException
            || current instanceof CannotSerializeTransactionException
            || current instanceof DeadlockLoserDataAccessException
            || current instanceof QueryTimeoutException);
    }

    private static boolean lockContention(Throwable failure) {
        if (failure instanceof PessimisticLockingFailureException) {
            return true;
        }
        if (failure instanceof SQLException sql) {
            return switch (sql.getErrorCode()) {
                case 1213, 1205 -> true;
                case 3572 -> "HY000".equals(sql.getSQLState());
                default -> false;
            };
        }
        return false;
    }

    private static boolean anyFailure(Throwable failure, Predicate<Throwable> predicate) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Throwable> pending = new ArrayDeque<>();
        if (failure != null) {
            pending.push(failure);
        }
        while (!pending.isEmpty()) {
            Throwable current = pending.pop();
            if (!seen.add(current)) {
                continue;
            }
            if (predicate.test(current)) {
                return true;
            }
            if (current instanceof TransactionSystemException transaction
                    && transaction.getApplicationException() != null) {
                pending.push(transaction.getApplicationException());
            }
            if (current.getCause() != null) {
                pending.push(current.getCause());
            }
        }
        return false;
    }
}
