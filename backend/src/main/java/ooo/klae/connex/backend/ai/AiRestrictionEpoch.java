package ooo.klae.connex.backend.ai;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Fences cache writes assembled before a workspace processing restriction. This closes the
 * in-flight write window only within one application JVM; multi-instance deployments still need
 * persisted report-to-person provenance or a persisted epoch, tracked in issue #941.
 */
@Component
public class AiRestrictionEpoch {
    static final int MAX_TRACKED_WORKSPACES = 4096;

    private final int maxTrackedWorkspaces;
    private final ReentrantReadWriteLock stateLock = new ReentrantReadWriteLock(true);
    private final Map<Integer, AtomicLong> workspaceEpochs = new LinkedHashMap<>();
    private final AtomicLong baselineEpoch = new AtomicLong();
    private final AtomicLong epochSequence = new AtomicLong();

    /** Creates the production restriction-epoch fence with bounded workspace state. */
    public AiRestrictionEpoch() {
        this(MAX_TRACKED_WORKSPACES);
    }

    AiRestrictionEpoch(int maxTrackedWorkspaces) {
        if (maxTrackedWorkspaces <= 0) {
            throw new IllegalArgumentException("AI restriction epoch capacity must be positive");
        }
        this.maxTrackedWorkspaces = maxTrackedWorkspaces;
    }

    /**
     * Returns the current epoch for one workspace.
     * @param workspaceId workspace whose restriction epoch is read
     * @return current workspace epoch
     */
    public long current(int workspaceId) {
        requireWorkspace(workspaceId);
        stateLock.readLock().lock();
        try {
            AtomicLong epoch = workspaceEpochs.get(workspaceId);
            return epoch == null ? baselineEpoch.get() : epoch.get();
        } finally {
            stateLock.readLock().unlock();
        }
    }

    /**
     * Advances the restriction epoch for one workspace. When invoked inside a transaction, the
     * write fence remains held until transaction completion so no newly assembled or persisted
     * cache output can enter between the bump and the restriction purge commit.
     * @param workspaceId workspace whose in-flight cache writes must be fenced
     */
    public void bump(int workspaceId) {
        requireWorkspace(workspaceId);
        stateLock.writeLock().lock();
        boolean retainedForTransaction = false;
        try {
            advance(workspaceId);
            retainedForTransaction = retainWriteFenceUntilTransactionCompletion();
        } finally {
            if (!retainedForTransaction) {
                stateLock.writeLock().unlock();
            }
        }
    }

    boolean runIfCurrent(int workspaceId, long expectedEpoch, Runnable action) {
        requireWorkspace(workspaceId);
        Objects.requireNonNull(action, "action");
        stateLock.readLock().lock();
        try {
            AtomicLong epoch = workspaceEpochs.get(workspaceId);
            long currentEpoch = epoch == null ? baselineEpoch.get() : epoch.get();
            if (currentEpoch != expectedEpoch) {
                return false;
            }
            action.run();
            return true;
        } finally {
            stateLock.readLock().unlock();
        }
    }

    int trackedWorkspaceCount() {
        stateLock.readLock().lock();
        try {
            return workspaceEpochs.size();
        } finally {
            stateLock.readLock().unlock();
        }
    }

    private void advance(int workspaceId) {
        AtomicLong current = workspaceEpochs.get(workspaceId);
        if (current != null) {
            current.set(nextEpoch());
            return;
        }
        if (workspaceEpochs.size() >= maxTrackedWorkspaces) {
            Integer evictedWorkspaceId = workspaceEpochs.keySet().iterator().next();
            workspaceEpochs.remove(evictedWorkspaceId);
            baselineEpoch.set(nextEpoch());
        }
        workspaceEpochs.put(workspaceId, new AtomicLong(nextEpoch()));
    }

    private boolean retainWriteFenceUntilTransactionCompletion() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return false;
        }
        TransactionSynchronizationManager.registerSynchronization(new WriteFenceRelease());
        return true;
    }

    private long nextEpoch() {
        if (epochSequence.get() == Long.MAX_VALUE) {
            throw new IllegalStateException("AI restriction epoch space is exhausted");
        }
        return epochSequence.incrementAndGet();
    }

    private static void requireWorkspace(int workspaceId) {
        if (workspaceId <= 0) {
            throw new IllegalArgumentException("AI restriction epoch workspace must be positive");
        }
    }

    private final class WriteFenceRelease implements TransactionSynchronization {
        @Override
        public void afterCompletion(int status) {
            stateLock.writeLock().unlock();
        }
    }
}
