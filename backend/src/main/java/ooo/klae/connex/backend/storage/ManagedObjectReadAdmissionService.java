package ooo.klae.connex.backend.storage;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;

/**
 * Bounds private-object streams globally and per user until close or the hard read deadline.
 */
@Component
public class ManagedObjectReadAdmissionService {
    private final ObjectStorageProperties properties;
    private final IntSupplier currentUserId;
    private final Semaphore globalPermits;
    private final Map<Integer, Integer> activeByUser = new HashMap<>();
    private final ScheduledExecutorService timeoutExecutor = Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().daemon().name("managed-object-read-timeout-", 0).factory());

    @Autowired
    public ManagedObjectReadAdmissionService(ObjectStorageProperties properties) {
        this(properties, ManagedObjectReadAdmissionService::authenticatedUserId);
    }

    ManagedObjectReadAdmissionService(
            ObjectStorageProperties properties,
            IntSupplier currentUserId) {
        this.properties = properties;
        this.currentUserId = currentUserId;
        this.globalPermits = new Semaphore(properties.getMaxConcurrentReads(), true);
    }

    public StoredObject admit(Supplier<StoredObject> opener) {
        return admit(
            currentUserId.getAsInt(),
            Duration.ofMillis(properties.getReadTimeoutMs()),
            opener);
    }

    /**
     * Admits an explicitly authorized actor's stream with an operation-specific
     * hard deadline.
     */
    public StoredObject admit(
            int userId,
            Duration timeout,
            Supplier<StoredObject> opener) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Managed object read timeout must be positive");
        }
        Lease lease = acquire(userId);
        StoredObject stored;
        try {
            stored = opener.get();
        } catch (RuntimeException exception) {
            lease.close();
            throw exception;
        }
        LeasedInputStream stream = new LeasedInputStream(stored, lease, timeout);
        try {
            stream.startTimeout();
            return new StoredObject(stream, stored.contentLength());
        } catch (RuntimeException exception) {
            try {
                stream.close();
            } catch (IOException ignored) {
            }
            throw new ServiceUnavailableException("Private object streaming is unavailable");
        }
    }

    @PreDestroy
    void shutdown() {
        timeoutExecutor.shutdownNow();
    }

    private Lease acquire(int userId) {
        if (userId <= 0 || !globalPermits.tryAcquire()) {
            throw new TooManyRequestsException("Private object downloads are busy; retry shortly");
        }
        synchronized (activeByUser) {
            int active = activeByUser.getOrDefault(userId, 0);
            if (active >= properties.getMaxConcurrentReadsPerUser()) {
                globalPermits.release();
                throw new TooManyRequestsException(
                    "Private object download limit reached; retry shortly");
            }
            activeByUser.put(userId, active + 1);
        }
        return new Lease(userId);
    }

    private static int authenticatedUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof User user)
                || user.getId() <= 0) {
            throw new ResourceNotFoundException("Not authenticated");
        }
        return user.getId();
    }

    private final class Lease implements AutoCloseable {
        private final int userId;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(int userId) {
            this.userId = userId;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            synchronized (activeByUser) {
                int remaining = activeByUser.getOrDefault(userId, 1) - 1;
                if (remaining == 0) {
                    activeByUser.remove(userId);
                } else {
                    activeByUser.put(userId, remaining);
                }
            }
            globalPermits.release();
        }
    }

    private final class LeasedInputStream extends InputStream {
        private final StoredObject stored;
        private final Lease lease;
        private final Duration timeoutDuration;
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile ScheduledFuture<?> timeout;

        private LeasedInputStream(
                StoredObject stored,
                Lease lease,
                Duration timeoutDuration) {
            this.stored = stored;
            this.lease = lease;
            this.timeoutDuration = timeoutDuration;
        }

        private void startTimeout() {
            timeout = timeoutExecutor.schedule(
                this::closeAfterTimeout,
                timeoutDuration.toNanos(),
                TimeUnit.NANOSECONDS);
        }

        private void closeAfterTimeout() {
            try {
                close();
            } catch (IOException ignored) {
            }
        }

        @Override
        public int read() throws IOException {
            return stored.inputStream().read();
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            return stored.inputStream().read(bytes, offset, length);
        }

        @Override
        public long transferTo(java.io.OutputStream output) throws IOException {
            return stored.inputStream().transferTo(output);
        }

        @Override
        public void close() throws IOException {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            ScheduledFuture<?> scheduled = timeout;
            if (scheduled != null) {
                scheduled.cancel(false);
            }
            try {
                stored.close();
            } finally {
                lease.close();
            }
        }
    }
}
