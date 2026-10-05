package ooo.klae.connex.backend.support;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/** In-memory transaction lifecycle for suspension, routing, and restoration tests. */
public final class TestTransactionManager extends AbstractPlatformTransactionManager {
    private final ThreadLocal<TestTransaction> current = new ThreadLocal<>();
    private int beginCount;
    private int suspendCount;

    /** Returns the active transaction identity for restoration assertions. */
    public TestTransaction currentTransaction() {
        return current.get();
    }

    /** Returns the number of transactions begun by this manager. */
    public int beginCount() {
        return beginCount;
    }

    /** Returns the number of transaction suspensions. */
    public int suspendCount() {
        return suspendCount;
    }

    @Override
    protected Object doGetTransaction() {
        TestTransaction transaction = current.get();
        return transaction == null ? new TestTransaction() : transaction;
    }

    @Override
    protected boolean isExistingTransaction(Object transaction) {
        return ((TestTransaction) transaction).active;
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        TestTransaction active = (TestTransaction) transaction;
        active.active = true;
        beginCount++;
        current.set(active);
    }

    @Override
    protected Object doSuspend(Object transaction) {
        suspendCount++;
        current.remove();
        return transaction;
    }

    @Override
    protected void doResume(Object transaction, Object suspendedResources) {
        current.set((TestTransaction) suspendedResources);
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
    }

    @Override
    protected void doCleanupAfterCompletion(Object transaction) {
        TestTransaction completed = (TestTransaction) transaction;
        completed.active = false;
        current.remove();
    }

    /** Transaction identity retained while its work is suspended. */
    public static final class TestTransaction {
        private boolean active;
    }
}
