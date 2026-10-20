package ooo.klae.connex.backend.exceptions;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.TransactionSystemException;

class DatabaseFailureClassifierTest {
    @ParameterizedTest
    @MethodSource("ooo.klae.connex.backend.support.LockContentionTestCases#retryable")
    void exactLockFailuresAreRetryable(Exception failure) {
        assertTrue(DatabaseFailureClassifier.isLockContention(failure));
    }

    @ParameterizedTest
    @MethodSource("ooo.klae.connex.backend.support.LockContentionTestCases#nonRetryable")
    void otherFailuresAreNotHttpConflicts(Exception failure) {
        assertFalse(DatabaseFailureClassifier.isLockContention(failure));
    }

    @Test
    void queryTimeoutsRemainWorkflowOnly() {
        TransactionSystemException transaction = new TransactionSystemException("savepoint");
        transaction.initApplicationException(new QueryTimeoutException("query"));

        assertTrue(DatabaseFailureClassifier.isTransientDatabaseFailure(transaction));
        assertFalse(DatabaseFailureClassifier.isLockContention(transaction));
        assertFalse(DatabaseFailureClassifier.isLockContention(null));
        assertFalse(DatabaseFailureClassifier.isTransientDatabaseFailure(null));
    }

    @Test
    void cyclicCausesTerminateAndStillInspectApplicationExceptions() {
        RuntimeException first = new RuntimeException("first");
        TransactionSystemException second = new TransactionSystemException("second", first);
        first.initCause(second);

        assertFalse(assertTimeoutPreemptively(Duration.ofSeconds(1),
            () -> DatabaseFailureClassifier.isLockContention(first)));
        second.initApplicationException(new CannotAcquireLockException("lock"));
        assertTrue(DatabaseFailureClassifier.isLockContention(first));
    }

    @Test
    void aMatchBeyond64CausesIsStillFound() {
        Throwable failure = new CannotAcquireLockException("lock");
        for (int depth = 0; depth < 100; depth++) {
            failure = new RuntimeException("wrapper", failure);
        }

        assertTrue(DatabaseFailureClassifier.isLockContention(failure));
        assertTrue(DatabaseFailureClassifier.isTransientDatabaseFailure(failure));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bothTransactionExceptionBranchOrdersFindDeepMatches(boolean matchInApplicationException) {
        Throwable match = new CannotAcquireLockException("lock");
        Throwable unrelated = new RuntimeException("unrelated");
        for (int depth = 0; depth < 100; depth++) {
            match = new RuntimeException("wrapper", match);
            unrelated = new RuntimeException("wrapper", unrelated);
        }
        TransactionSystemException failure = new TransactionSystemException("commit",
            matchInApplicationException ? unrelated : match);
        failure.initApplicationException(matchInApplicationException ? match : unrelated);

        assertTrue(DatabaseFailureClassifier.isLockContention(failure));
        assertTrue(DatabaseFailureClassifier.isTransientDatabaseFailure(failure));
    }
}
