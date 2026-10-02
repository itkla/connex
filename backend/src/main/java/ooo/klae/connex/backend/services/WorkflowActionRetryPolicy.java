package ooo.klae.connex.backend.services;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;

import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.CannotSerializeTransactionException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionSystemException;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.dto.RuleAction;

/** Closed retry-safety table and transient database allowlist for workflow actions. */
@Component
@RequiredArgsConstructor
public class WorkflowActionRetryPolicy {

    private final WorkflowRuntimeProperties properties;

    public RetrySafety safety(RuleAction action) {
        String type = action == null || action.getType() == null
            ? ""
            : action.getType().trim().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "create_task", "log_activity", "add_tag", "remove_tag", "create_note",
                 "assign_owner", "set_response_due", "change_stage" -> RetrySafety.TRANSACTIONAL;
            case "notify", "send_message" -> RetrySafety.DEDUPLICATED;
            default -> RetrySafety.NONE;
        };
    }

    /**
     * Whether a later attempt, in a new transaction, can clear a failure: a lock wait, a
     * serialization failure, a deadlock or a timeout anywhere among its causes.
     *
     * <p>A transaction the database rolled back under code that swallowed the failure fails its
     * commit with that failure itself. A savepoint the rollback destroyed fails with a
     * {@link TransactionSystemException} that carries the original failure as its application
     * exception rather than as a cause, so that exception is walked too (#1947). A transaction
     * failure with no such exception, such as a commit the translator could not classify, is not
     * retried: whether it took effect is unknown.
     *
     * @param failure the failure, walked through its causes
     * @return whether the action should be retried
     */
    public boolean transientDatabaseFailure(Throwable failure) {
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
            if (current instanceof CannotAcquireLockException
                    || current instanceof CannotSerializeTransactionException
                    || current instanceof DeadlockLoserDataAccessException
                    || current instanceof QueryTimeoutException) {
                return true;
            }
            if (current instanceof TransactionSystemException lost && lost.getApplicationException() != null) {
                pending.push(lost.getApplicationException());
            }
            if (current.getCause() != null) {
                pending.push(current.getCause());
            }
        }
        return false;
    }

    public Duration retryDelay(long runId, String nodeId, int attemptNumber) {
        int exponent = Math.max(0, Math.min(attemptNumber - 1, 8));
        long base = properties.retryBase().toSeconds();
        long exponential = Math.min(
            properties.retryMaximum().toSeconds(), base * (1L << exponent));
        long jitterWindow = Math.max(1L, base / 4L);
        long jitter = Math.floorMod(
            java.util.Objects.hash(runId, nodeId, attemptNumber), jitterWindow);
        return Duration.ofSeconds(Math.min(
            properties.retryMaximum().toSeconds(), exponential + jitter));
    }

    /** Persisted replay contract for one action kind. */
    public enum RetrySafety {
        NONE("none"),
        TRANSACTIONAL("transactional"),
        DEDUPLICATED("deduplicated");

        private final String value;

        RetrySafety(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }
}
