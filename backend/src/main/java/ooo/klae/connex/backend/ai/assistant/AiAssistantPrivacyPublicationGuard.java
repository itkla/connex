package ooo.klae.connex.backend.ai.assistant;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Linearizes actual content delivery with organization-wide invalidation in the serving JVM. */
@Component
public final class AiAssistantPrivacyPublicationGuard {
    private static final ReentrantReadWriteLock[] LOCKS = locks();
    private final AiAssistantPrivacyService authority;
    private final TransactionTemplate freshRead;

    /** Uses a separate READ COMMITTED transaction for every outbound authority check. */
    public AiAssistantPrivacyPublicationGuard(
            AiAssistantPrivacyService authority, PlatformTransactionManager transactionManager) {
        this.authority = authority;
        freshRead = new TransactionTemplate(transactionManager);
        freshRead.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        freshRead.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        freshRead.setReadOnly(true);
    }

    /** Checked action performed while publication authority remains held. */
    @FunctionalInterface
    public interface IoAction {
        void run() throws IOException;
    }

    /** Delivery-executor authority held until its message handler finishes. */
    @FunctionalInterface
    public interface Publication extends AutoCloseable {
        @Override
        void close();
    }

    /** Retains the local invalidation fence before durable organization X through completion. */
    public static void retainInvalidation(int organizationId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Assistant invalidation requires a transaction");
        }
        var lock = lock(organizationId).writeLock();
        lock.lock();
        try {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    lock.unlock();
                }
            });
        } catch (RuntimeException | Error failure) {
            lock.unlock();
            throw failure;
        }
    }

    /** Runs provider admission under the same organization fence as content publication. */
    public static <T> T underPublicationRead(int organizationId, Supplier<T> action) {
        Objects.requireNonNull(action);
        var lock = lock(organizationId).readLock();
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    /** Checks fresh authority immediately around an actual output write, including ZIP completion. */
    public void withCurrent(int organizationId, long generation, IoAction action) throws IOException {
        Objects.requireNonNull(action);
        try (var publication = retainCurrent(organizationId, generation)) {
            action.run();
        }
    }

    /** Checks original authority and keeps invalidation excluded through actual delivery. */
    public Publication retainCurrent(int organizationId, long generation) throws IOException {
        var lock = lock(organizationId).readLock();
        lock.lock();
        try {
            Long current = freshRead.execute(status -> authority.current(organizationId));
            if (generation <= 0 || current == null || current != generation) {
                throw new IOException("Assistant content is no longer available");
            }
            return lock::unlock;
        } catch (IOException | RuntimeException | Error failure) {
            lock.unlock();
            throw failure;
        }
    }

    /** Publishes an immutable source only when its original authority still matches. */
    public void publish(int organizationId, long generation, Runnable action) {
        try {
            withCurrent(organizationId, generation, action::run);
        } catch (IOException exception) {
            throw new AiAssistantLoopException("restrictions_changed", "restrictions_changed");
        }
    }

    private static ReentrantReadWriteLock lock(int organizationId) {
        if (organizationId <= 0) {
            throw new IllegalArgumentException("Organization identity must be positive");
        }
        return LOCKS[Math.floorMod(organizationId, LOCKS.length)];
    }

    private static ReentrantReadWriteLock[] locks() {
        var locks = new ReentrantReadWriteLock[4096];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new ReentrantReadWriteLock(true);
        }
        return locks;
    }
}
