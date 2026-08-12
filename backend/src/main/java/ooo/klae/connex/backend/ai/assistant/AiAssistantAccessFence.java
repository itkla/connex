package ooo.klae.connex.backend.ai.assistant;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Holds assistant authorization stable from final revalidation through provider egress within one
 * application JVM. Multi-replica deployments require persisted authorization fencing before this
 * class can provide the same cross-replica guarantee.
 */
@Component
public class AiAssistantAccessFence {
    private static final int WORKSPACE_STRIPES = 4_096;
    private static final int USER_STRIPES = 4_096;

    private final ReentrantReadWriteLock[] workspaceLocks;
    private final ReentrantReadWriteLock[] userLocks;

    /** Creates the bounded workspace authorization fence. */
    public AiAssistantAccessFence() {
        workspaceLocks = new ReentrantReadWriteLock[WORKSPACE_STRIPES];
        userLocks = new ReentrantReadWriteLock[USER_STRIPES];
        for (int index = 0; index < workspaceLocks.length; index++) {
            workspaceLocks[index] = new ReentrantReadWriteLock(true);
        }
        for (int index = 0; index < userLocks.length; index++) {
            userLocks[index] = new ReentrantReadWriteLock(true);
        }
    }

    /** Runs final authorization and provider send under user and workspace read fences. */
    public <T> T invokeAtEgress(int workspaceId, int userId, Supplier<T> invocation) {
        Objects.requireNonNull(invocation, "invocation");
        ReentrantReadWriteLock userLock = userLock(userId);
        ReentrantReadWriteLock workspaceLock = workspaceLock(workspaceId);
        userLock.readLock().lock();
        workspaceLock.readLock().lock();
        try {
            return invocation.get();
        } finally {
            workspaceLock.readLock().unlock();
            userLock.readLock().unlock();
        }
    }

    /** Retains a workspace write fence through the active authorization mutation transaction. */
    public void retainMutationFenceUntilTransactionCompletion(int workspaceId) {
        retainMutationFencesUntilTransactionCompletion(List.of(workspaceId));
    }

    /** Retains one workspace read fence through the active persistence transaction. */
    public void retainReadFenceUntilTransactionCompletion(int workspaceId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException(
                    "Assistant authorization commit fence requires an active transaction");
        }
        ReentrantReadWriteLock lock = workspaceLock(workspaceId);
        lock.readLock().lock();
        TransactionSynchronizationManager.registerSynchronization(
                new ReadFenceRelease(lock));
    }

    /** Retains canonically ordered workspace write fences through the active transaction. */
    public void retainMutationFencesUntilTransactionCompletion(
            Collection<Integer> workspaceIds) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException(
                    "Assistant authorization mutation fence requires an active transaction");
        }
        List<ReentrantReadWriteLock> locks = mutationLocks(workspaceIds);
        locks.forEach(lock -> lock.writeLock().lock());
        TransactionSynchronizationManager.registerSynchronization(
                new WriteFenceRelease(locks));
    }

    /** Runs a cross-transaction mutation while holding every workspace write fence. */
    public void runWithMutationFences(Collection<Integer> workspaceIds, Runnable mutation) {
        Objects.requireNonNull(workspaceIds, "workspaceIds");
        Objects.requireNonNull(mutation, "mutation");
        List<ReentrantReadWriteLock> locks = mutationLocks(workspaceIds);
        locks.forEach(lock -> lock.writeLock().lock());
        try {
            mutation.run();
        } finally {
            unlockReverse(locks);
        }
    }

    private List<ReentrantReadWriteLock> mutationLocks(Collection<Integer> workspaceIds) {
        return workspaceIds.stream()
                .map(workspaceId -> Objects.requireNonNull(workspaceId, "workspaceId"))
                .distinct()
                .sorted(Comparator.comparingInt(this::workspaceStripe)
                        .thenComparingInt(Integer::intValue))
                .map(this::workspaceLock)
                .distinct()
                .toList();
    }

    private static void unlockReverse(List<ReentrantReadWriteLock> locks) {
        List<ReentrantReadWriteLock> reverse = new ArrayList<>(locks);
        java.util.Collections.reverse(reverse);
        reverse.forEach(lock -> lock.writeLock().unlock());
    }

    /** Runs account erasure under a user write fence that excludes every assistant egress. */
    public void runWithUserMutationFence(int userId, Runnable mutation) {
        Objects.requireNonNull(mutation, "mutation");
        ReentrantReadWriteLock lock = userLock(userId);
        lock.writeLock().lock();
        try {
            mutation.run();
        } finally {
            lock.writeLock().unlock();
        }
    }

    private ReentrantReadWriteLock workspaceLock(int workspaceId) {
        if (workspaceId <= 0) {
            throw new IllegalArgumentException("Assistant workspace must be positive");
        }
        return workspaceLocks[workspaceStripe(workspaceId)];
    }

    private ReentrantReadWriteLock userLock(int userId) {
        if (userId <= 0) {
            throw new IllegalArgumentException("Assistant user must be positive");
        }
        return userLocks[Math.floorMod(userId, userLocks.length)];
    }

    private int workspaceStripe(int workspaceId) {
        if (workspaceId <= 0) {
            throw new IllegalArgumentException("Assistant workspace must be positive");
        }
        return Math.floorMod(workspaceId, workspaceLocks.length);
    }

    private static final class WriteFenceRelease implements TransactionSynchronization {
        private final List<ReentrantReadWriteLock> locks;

        private WriteFenceRelease(List<ReentrantReadWriteLock> locks) {
            this.locks = locks;
        }

        @Override
        public void afterCompletion(int status) {
            unlockReverse(locks);
        }
    }

    private static final class ReadFenceRelease implements TransactionSynchronization {
        private final ReentrantReadWriteLock lock;

        private ReadFenceRelease(ReentrantReadWriteLock lock) {
            this.lock = lock;
        }

        @Override
        public void afterCompletion(int status) {
            lock.readLock().unlock();
        }
    }
}
