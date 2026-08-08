package ooo.klae.connex.backend.ai;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Fences provider egress and cache writes whose complete demasked inputs were assembled before a
 * workspace processing restriction. Workspaces use bounded lock stripes, so a transaction-held
 * purge fence blocks only affected workspace stripes rather than every tenant in the JVM. Provider
 * egress releases its read fence before person-row-backed cache persistence to preserve the
 * restriction path's row-before-fence lock order. These guarantees apply within one application
 * JVM; multi-instance deployments still need persisted report-to-person provenance or a persisted
 * epoch, tracked in issue #941.
 *
 * <p>The JVM-lock and InnoDB-row-lock order is load-bearing: a restriction transaction locks and
 * updates the person, acquires every affected workspace fence in ascending workspace-stripe order,
 * bumps those epochs, and only then executes the organization-wide cache delete. Epoch-fenced cache
 * saves acquire the workspace read fence before their cache-row write. The epoch bump must
 * therefore remain before the organization-wide delete, and each write fence remains held until
 * transaction completion.
 */
@Component
public class AiRestrictionEpoch {
    static final int DEFAULT_WORKSPACE_STRIPES = 4096;

    private final ReentrantReadWriteLock[] workspaceLocks;
    private final AtomicLong[] workspaceEpochs;
    private final AtomicLong epochSequence = new AtomicLong();
    private final ThreadLocal<RestrictionExpectation> expectedEgressEpoch = new ThreadLocal<>();

    /** Creates the production restriction-epoch fence with bounded workspace stripes. */
    public AiRestrictionEpoch() {
        this(DEFAULT_WORKSPACE_STRIPES);
    }

    AiRestrictionEpoch(int workspaceStripeCount) {
        if (workspaceStripeCount <= 0) {
            throw new IllegalArgumentException("AI restriction epoch capacity must be positive");
        }
        workspaceLocks = new ReentrantReadWriteLock[workspaceStripeCount];
        workspaceEpochs = new AtomicLong[workspaceStripeCount];
        for (int index = 0; index < workspaceStripeCount; index++) {
            workspaceLocks[index] = new ReentrantReadWriteLock(true);
            workspaceEpochs[index] = new AtomicLong();
        }
    }

    /**
     * Returns the current epoch for one workspace.
     * @param workspaceId workspace whose restriction epoch is read
     * @return current workspace epoch
     */
    public long current(int workspaceId) {
        int stripe = stripe(workspaceId);
        ReentrantReadWriteLock lock = workspaceLocks[stripe];
        lock.readLock().lock();
        try {
            return workspaceEpochs[stripe].get();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Advances affected workspace epochs in a globally consistent stripe order. The ordering
     * prevents cross-organization restriction transactions from acquiring colliding stripes in
     * opposite orders.
     * @param workspaceIds affected workspace ids
     */
    public void bumpAll(List<Integer> workspaceIds) {
        List<Integer> orderedWorkspaceIds = Objects.requireNonNull(workspaceIds, "workspaceIds").stream()
                .map(workspaceId -> Objects.requireNonNull(workspaceId, "workspaceId"))
                .distinct()
                .sorted(Comparator.comparingInt(this::stripe).thenComparingInt(Integer::intValue))
                .toList();
        orderedWorkspaceIds.forEach(this::bump);
    }

    /**
     * Advances the restriction epoch for one workspace. When invoked inside a transaction, the
     * workspace-stripe write fence remains held until transaction completion so no newly assembled
     * or persisted cache output can enter between the bump and the restriction purge commit.
     * @param workspaceId workspace whose in-flight cache writes must be fenced
     */
    public void bump(int workspaceId) {
        int stripe = stripe(workspaceId);
        ReentrantReadWriteLock lock = workspaceLocks[stripe];
        lock.writeLock().lock();
        boolean retainedForTransaction = false;
        try {
            workspaceEpochs[stripe].set(nextEpoch());
            retainedForTransaction = retainWriteFenceUntilTransactionCompletion(lock);
        } finally {
            if (!retainedForTransaction) {
                lock.writeLock().unlock();
            }
        }
    }

    boolean runIfCurrent(int workspaceId, long expectedEpoch, Runnable action) {
        Objects.requireNonNull(action, "action");
        int stripe = stripe(workspaceId);
        ReentrantReadWriteLock lock = workspaceLocks[stripe];
        lock.readLock().lock();
        try {
            if (workspaceEpochs[stripe].get() != expectedEpoch) {
                return false;
            }
            action.run();
            return true;
        } finally {
            lock.readLock().unlock();
        }
    }

    void runWithExpectedEgressEpoch(
            int workspaceId, long expectedEpoch, Runnable action) {
        Objects.requireNonNull(action, "action");
        requireWorkspace(workspaceId);
        RestrictionExpectation previous = expectedEgressEpoch.get();
        RestrictionExpectation expected = new RestrictionExpectation(workspaceId, expectedEpoch);
        if (previous != null && !previous.equals(expected)) {
            throw new IllegalStateException("Nested AI egress restriction contexts do not match");
        }
        expectedEgressEpoch.set(expected);
        try {
            action.run();
        } finally {
            if (previous == null) {
                expectedEgressEpoch.remove();
            } else {
                expectedEgressEpoch.set(previous);
            }
        }
    }

    <T> T invokeAtEgress(int workspaceId, Supplier<T> invocation) {
        Objects.requireNonNull(invocation, "invocation");
        RestrictionExpectation expected = expectedEgressEpoch.get();
        if (expected == null) {
            return invocation.get();
        }
        if (expected.workspaceId() != workspaceId) {
            throw new IllegalStateException("AI egress workspace does not match its restriction context");
        }
        AtomicReference<T> result = new AtomicReference<>();
        boolean invoked = runIfCurrent(
                workspaceId, expected.epoch(), () -> result.set(invocation.get()));
        if (!invoked) {
            throw new EgressRejectedException("AI restrictions changed before provider egress");
        }
        return result.get();
    }

    int usedWorkspaceStripeCount() {
        int tracked = 0;
        for (AtomicLong epoch : workspaceEpochs) {
            if (epoch.get() != 0) {
                tracked++;
            }
        }
        return tracked;
    }

    private boolean retainWriteFenceUntilTransactionCompletion(ReentrantReadWriteLock lock) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return false;
        }
        TransactionSynchronizationManager.registerSynchronization(new WriteFenceRelease(lock));
        return true;
    }

    private long nextEpoch() {
        while (true) {
            long current = epochSequence.get();
            if (current == Long.MAX_VALUE) {
                throw new IllegalStateException("AI restriction epoch space is exhausted");
            }
            if (epochSequence.compareAndSet(current, current + 1)) {
                return current + 1;
            }
        }
    }

    private int stripe(int workspaceId) {
        requireWorkspace(workspaceId);
        return Math.floorMod(workspaceId, workspaceLocks.length);
    }

    private static void requireWorkspace(int workspaceId) {
        if (workspaceId <= 0) {
            throw new IllegalArgumentException("AI restriction epoch workspace must be positive");
        }
    }

    private static final class WriteFenceRelease implements TransactionSynchronization {
        private final ReentrantReadWriteLock lock;

        private WriteFenceRelease(ReentrantReadWriteLock lock) {
            this.lock = lock;
        }

        @Override
        public void afterCompletion(int status) {
            lock.writeLock().unlock();
        }
    }

    private record RestrictionExpectation(int workspaceId, long epoch) {
    }

    static final class EgressRejectedException extends IllegalStateException {
        private EgressRejectedException(String message) {
            super(message);
        }
    }
}
