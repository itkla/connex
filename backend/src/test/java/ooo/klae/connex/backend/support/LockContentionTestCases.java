package ooo.klae.connex.backend.support;

import java.sql.SQLException;
import java.util.stream.Stream;

import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.CannotSerializeTransactionException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.UnexpectedRollbackException;

/** Shared failure matrix for classifier and HTTP envelope contracts. */
public final class LockContentionTestCases {
    private LockContentionTestCases() {
    }

    /** Covers vendor errors, Spring translations, wrappers and both transaction exception edges. */
    public static Stream<Exception> retryable() {
        Stream<Exception> vendorFailures = Stream.of(1213, 1205, 3572).flatMap(code -> {
            SQLException sql = new SQLException("SQL with private parameters", code == 1213 ? "40001" : "HY000", code);
            RuntimeException translated = switch (code) {
                case 1213 -> new DeadlockLoserDataAccessException("private SQL", sql);
                case 1205 -> new CannotAcquireLockException("private SQL", sql);
                default -> new UncategorizedSQLException("private task", "private SQL", sql);
            };
            TransactionSystemException application = new TransactionSystemException("private savepoint");
            application.initApplicationException(translated);
            return Stream.of(sql, translated, new RuntimeException("wrapper", translated),
                new TransactionSystemException("private commit", translated), application,
                new RuntimeException("wrapper", application));
        });
        return Stream.concat(vendorFailures, Stream.of(
            new PessimisticLockingFailureException("private lock"),
            new CannotAcquireLockException("private lock"),
            new DeadlockLoserDataAccessException("private lock", null),
            new CannotSerializeTransactionException("private lock")));
    }

    /** Keeps unknown transaction outcomes, business failures and other vendor errors unclassified. */
    public static Stream<Exception> nonRetryable() {
        TransactionSystemException application = new TransactionSystemException("private savepoint");
        application.initApplicationException(new DuplicateKeyException("private key"));
        return Stream.of(
            new TransactionSystemException("private commit"),
            new TransactionSystemException("private commit", new SQLException("private SQL", "HY000", 1206)),
            application,
            new UnexpectedRollbackException("private rollback"),
            new RuntimeException("private failure"),
            new DuplicateKeyException("private key"),
            new QueryTimeoutException("private query"),
            new SQLException("private SQL", "40001", 0),
            new SQLException("private SQL", "HY000", 1206),
            new SQLException("private SQL", "HY000", 1064),
            new SQLException("private SQL", "42000", 3572));
    }

    /** Transaction failures whose existing HTTP 500 contract must remain unchanged. */
    public static Stream<Exception> unclassifiedTransactions() {
        return nonRetryable().filter(failure -> failure instanceof TransactionSystemException
            || failure instanceof UnexpectedRollbackException);
    }
}
