package ooo.klae.connex.backend.services;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs one rule action so that, inside a durable delivery, a failure undoes only that action (#1928).
 *
 * <p>A durable legacy delivery runs a whole rule in one transaction. An action whose transactional
 * service throws through its proxy marks that shared transaction rollback-only, so the delivery could
 * never commit {@code "partial"}: the successful actions were rolled back with it and the delivery was
 * retried until it dead-lettered. Each action now runs in a nested savepoint. Rolling back to it undoes
 * the action and clears the rollback-only mark, so the rule can finish {@code "partial"} with its
 * successful actions intact.
 *
 * <p>Undoing only part of a transaction has three consequences, all handled here:
 * <ul>
 *   <li>Synchronizations the failed action registered stay on the delivery's list, so its mention
 *       emails, realtime pushes and after-commit listeners would otherwise fire for undone work. They
 *       are re-registered so that they never see a commit and complete as rolled back; nothing is
 *       dropped, so deferred audits and lock bookkeeping still run.</li>
 *   <li>A transient failure is not the action's fault and may have taken the whole transaction with
 *       it, so it fails the delivery with {@link RuleActionRetryRequiredException} and the worker
 *       retries it. Transient means exactly what the canonical engine retries
 *       ({@link WorkflowActionRetryPolicy#transientDatabaseFailure}), which includes a savepoint
 *       that could not be created or was already discarded.</li>
 *   <li>MyBatis has no savepoint hook, so the session cache is cleared before a later action, or the
 *       run's own bookkeeping, can read state the savepoint undid.</li>
 * </ul>
 *
 * <p>The callbacks that are forwarded still run for undone work. An undone notification can bump its
 * recipient's state version and send a content-free refresh, which costs a client refetch and reveals
 * nothing. A transactional event listener in a phase other than {@code AFTER_COMMIT} would likewise run,
 * so a listener added in another phase must tolerate undone work. Everything a failed action registered
 * is treated as its own: a once-per-transaction accumulator first registered by a failed action keeps
 * every callback except {@code afterCommit}, so such an accumulator must do its work in
 * {@code beforeCommit} or a status-agnostic {@code afterCompletion}, as the current ones do.
 *
 * <p>Without an active transaction, on the non-durable dispatch path, the action runs exactly as before.
 */
@Component
public class RuleActionIsolation {
    private final TransactionTemplate nested;
    private final SqlSessionTemplate sqlSessionTemplate;
    private final WorkflowActionRetryPolicy retryPolicy;

    public RuleActionIsolation(PlatformTransactionManager transactionManager, SqlSessionTemplate sqlSessionTemplate,
            WorkflowActionRetryPolicy retryPolicy) {
        this.nested = new TransactionTemplate(transactionManager);
        this.nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        this.sqlSessionTemplate = sqlSessionTemplate;
        this.retryPolicy = retryPolicy;
    }

    /**
     * Runs the action, undoing only its own work if it fails inside a transaction.
     *
     * @param action the rule action to run
     * @throws RuleActionRetryRequiredException when the action failed transiently inside a transaction
     */
    public void run(Runnable action) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        Set<TransactionSynchronization> registeredBefore = identitySet(
                TransactionSynchronizationManager.getSynchronizations());
        try {
            nested.executeWithoutResult(status -> action.run());
        } catch (RuntimeException failure) {
            sqlSessionTemplate.clearCache();
            neutraliseRegisteredSince(registeredBefore);
            if (requiresRetry(failure)) {
                throw new RuleActionRetryRequiredException(failure);
            }
            throw failure;
        }
    }

    private boolean requiresRetry(Throwable failure) {
        return retryPolicy.transientDatabaseFailure(failure);
    }

    private static void neutraliseRegisteredSince(Set<TransactionSynchronization> registeredBefore) {
        List<TransactionSynchronization> current = TransactionSynchronizationManager.getSynchronizations();
        if (registeredBefore.containsAll(current)) {
            return;
        }
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.initSynchronization();
        for (TransactionSynchronization synchronization : current) {
            TransactionSynchronizationManager.registerSynchronization(registeredBefore.contains(synchronization)
                    ? synchronization
                    : new UndoneActionSynchronization(synchronization));
        }
    }

    private static Set<TransactionSynchronization> identitySet(List<TransactionSynchronization> synchronizations) {
        Set<TransactionSynchronization> set = Collections.newSetFromMap(new IdentityHashMap<>());
        set.addAll(synchronizations);
        return set;
    }

    /**
     * A synchronization registered by an action whose savepoint was rolled back: it keeps its order and
     * every lifecycle callback, but never sees a commit and completes as rolled back.
     */
    private record UndoneActionSynchronization(TransactionSynchronization delegate)
            implements TransactionSynchronization {
        @Override
        public int getOrder() {
            return delegate.getOrder();
        }

        @Override
        public void suspend() {
            delegate.suspend();
        }

        @Override
        public void resume() {
            delegate.resume();
        }

        @Override
        public void flush() {
            delegate.flush();
        }

        @Override
        public void savepoint(Object savepoint) {
            delegate.savepoint(savepoint);
        }

        @Override
        public void savepointRollback(Object savepoint) {
            delegate.savepointRollback(savepoint);
        }

        @Override
        public void beforeCommit(boolean readOnly) {
            delegate.beforeCommit(readOnly);
        }

        @Override
        public void beforeCompletion() {
            delegate.beforeCompletion();
        }

        @Override
        public void afterCommit() {
        }

        @Override
        public void afterCompletion(int status) {
            delegate.afterCompletion(STATUS_ROLLED_BACK);
        }
    }
}
