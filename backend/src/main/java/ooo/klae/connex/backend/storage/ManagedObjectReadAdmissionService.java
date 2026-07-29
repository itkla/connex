package ooo.klae.connex.backend.storage;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log =
        LoggerFactory.getLogger(ManagedObjectReadAdmissionService.class);

    private final ObjectStorageProperties properties;
    private final IntSupplier currentUserId;
    private final Semaphore globalPermits;
    private final Map<Integer, Integer> activeByUser = new HashMap<>();
    private final Set<ProviderOpenTask> providerOpenTasks = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean shuttingDown = new AtomicBoolean();
    private final ScheduledThreadPoolExecutor timeoutExecutor;
    private final ThreadPoolExecutor providerOpenExecutor;

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
        this.timeoutExecutor = timeoutExecutor(properties.getMaxConcurrentReads());
        this.providerOpenExecutor =
            providerOpenExecutor(properties.getMaxConcurrentReads());
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
        if (shuttingDown.get()) {
            throw new ServiceUnavailableException("Private object streaming is unavailable");
        }
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        Lease lease = acquire(userId);
        if (shuttingDown.get()) {
            lease.close();
            throw new ServiceUnavailableException("Private object streaming is unavailable");
        }
        ProviderOpenTask openTask = new ProviderOpenTask(opener, lease);
        providerOpenTasks.add(openTask);
        try {
            providerOpenExecutor.execute(openTask);
        } catch (RejectedExecutionException exception) {
            providerOpenTasks.remove(openTask);
            lease.close();
            throw new ServiceUnavailableException("Private object streaming is unavailable");
        }
        StoredObject stored;
        try {
            stored = openTask.await(deadlineNanos);
        } catch (InterruptedException exception) {
            openTask.abandon();
            Thread.currentThread().interrupt();
            throw new ServiceUnavailableException("Private object streaming is unavailable");
        } catch (ProviderOpenTimeoutException exception) {
            openTask.abandon();
            throw new ServiceUnavailableException("Private object streaming is unavailable");
        }
        LeasedInputStream stream = new LeasedInputStream(stored, lease, deadlineNanos);
        try {
            stream.startTimeout();
            return new StoredObject(stream, stored.contentLength());
        } catch (RuntimeException exception) {
            try {
                stream.close();
            } catch (IOException closeFailure) {
                log.warn("Managed object deadline cleanup failed");
            }
            throw new ServiceUnavailableException("Private object streaming is unavailable");
        }
    }

    @PreDestroy
    void shutdown() {
        shuttingDown.set(true);
        providerOpenExecutor.shutdownNow();
        for (ProviderOpenTask task : new ArrayList<>(providerOpenTasks)) {
            task.abandon();
        }
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

    private static ScheduledThreadPoolExecutor timeoutExecutor(int capacity) {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
            capacity,
            Thread.ofPlatform().daemon().name("managed-object-read-timeout-", 0).factory());
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private static ThreadPoolExecutor providerOpenExecutor(int capacity) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
            capacity,
            capacity,
            0,
            TimeUnit.NANOSECONDS,
            new ArrayBlockingQueue<>(capacity),
            Thread.ofPlatform().daemon().name("managed-object-provider-open-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
        executor.prestartAllCoreThreads();
        return executor;
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
        private static final int TRANSFER_BUFFER_SIZE = 8192;

        private final StoredObject stored;
        private final Lease lease;
        private final long deadlineNanos;
        private final Object stateMonitor = new Object();
        private boolean closed;
        private volatile ScheduledFuture<?> timeout;

        private LeasedInputStream(
                StoredObject stored,
                Lease lease,
                long deadlineNanos) {
            this.stored = stored;
            this.lease = lease;
            this.deadlineNanos = deadlineNanos;
        }

        private void startTimeout() {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                try {
                    close();
                } catch (IOException exception) {
                    log.warn("Managed object deadline cleanup failed");
                }
                throw new ServiceUnavailableException("Private object streaming is unavailable");
            }
            timeout = timeoutExecutor.schedule(
                this::closeAfterTimeout,
                remainingNanos,
                TimeUnit.NANOSECONDS);
        }

        private void closeAfterTimeout() {
            try {
                close();
            } catch (IOException exception) {
                log.warn("Managed object deadline cleanup failed");
            }
        }

        @Override
        public int read() throws IOException {
            requireActive();
            int value = stored.inputStream().read();
            boolean expired;
            synchronized (stateMonitor) {
                if (closed) {
                    throw unavailable();
                }
                expired = System.nanoTime() >= deadlineNanos;
                if (!expired) {
                    return value;
                }
                closed = true;
            }
            closeExpired();
            throw unavailable();
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.requireNonNull(bytes, "bytes");
            Objects.checkFromIndexSize(offset, length, bytes.length);
            requireActive();
            if (length == 0) {
                return 0;
            }
            byte[] scratch = new byte[Math.min(length, TRANSFER_BUFFER_SIZE)];
            int read = stored.inputStream().read(scratch, 0, scratch.length);
            if (read < -1 || read > scratch.length) {
                throw new IOException("Managed object provider returned an invalid read length");
            }
            boolean expired;
            synchronized (stateMonitor) {
                if (closed) {
                    throw unavailable();
                }
                expired = System.nanoTime() >= deadlineNanos;
                if (!expired) {
                    if (read > 0) {
                        System.arraycopy(scratch, 0, bytes, offset, read);
                    }
                    return read;
                }
                closed = true;
            }
            closeExpired();
            throw unavailable();
        }

        @Override
        public long transferTo(java.io.OutputStream output) throws IOException {
            Objects.requireNonNull(output, "output");
            byte[] buffer = new byte[TRANSFER_BUFFER_SIZE];
            long transferred = 0;
            int read;
            while ((read = read(buffer, 0, buffer.length)) != -1) {
                requireActive();
                output.write(buffer, 0, read);
                transferred = Math.addExact(transferred, read);
            }
            return transferred;
        }

        @Override
        public void close() throws IOException {
            ScheduledFuture<?> scheduled;
            synchronized (stateMonitor) {
                if (closed) {
                    return;
                }
                closed = true;
                scheduled = timeout;
            }
            if (scheduled != null) {
                scheduled.cancel(false);
            }
            lease.close();
            stored.close();
        }

        private void requireActive() throws IOException {
            boolean expired;
            synchronized (stateMonitor) {
                if (closed) {
                    throw unavailable();
                }
                expired = System.nanoTime() >= deadlineNanos;
                if (!expired) {
                    return;
                }
                closed = true;
            }
            closeExpired();
            throw unavailable();
        }

        private void closeExpired() {
            ScheduledFuture<?> scheduled = timeout;
            if (scheduled != null) {
                scheduled.cancel(false);
            }
            lease.close();
            try {
                stored.close();
            } catch (IOException exception) {
                log.warn("Managed object deadline cleanup failed");
            }
        }

        private static IOException unavailable() {
            return new IOException("Managed object stream is unavailable");
        }
    }

    private final class ProviderOpenTask implements Runnable {
        private final Object monitor = new Object();
        private final Supplier<StoredObject> opener;
        private final Lease lease;

        private Thread runner;
        private StoredObject opened;
        private Throwable failure;
        private boolean started;
        private boolean completed;
        private boolean claimed;
        private boolean abandoned;

        private ProviderOpenTask(
                Supplier<StoredObject> opener,
                Lease lease) {
            this.opener = opener;
            this.lease = lease;
        }

        @Override
        public void run() {
            synchronized (monitor) {
                started = true;
                runner = Thread.currentThread();
                if (abandoned) {
                    completed = true;
                    monitor.notifyAll();
                }
            }
            if (isAbandoned()) {
                lease.close();
                finish();
                return;
            }
            StoredObject stored;
            try {
                stored = opener.get();
                if (stored == null) {
                    throw new ResourceNotFoundException("Stored file was not found");
                }
            } catch (RuntimeException | Error exception) {
                lease.close();
                synchronized (monitor) {
                    failure = exception;
                    completed = true;
                    monitor.notifyAll();
                }
                finish();
                return;
            }
            boolean closeLate;
            synchronized (monitor) {
                opened = stored;
                completed = true;
                monitor.notifyAll();
                while (!claimed && !abandoned) {
                    try {
                        monitor.wait();
                    } catch (InterruptedException exception) {
                        if (!abandoned) {
                            continue;
                        }
                    }
                }
                closeLate = abandoned;
                if (closeLate) {
                    opened = null;
                }
            }
            if (closeLate) {
                closeLate(stored);
                lease.close();
            }
            finish();
        }

        private StoredObject await(long deadlineNanos)
                throws InterruptedException, ProviderOpenTimeoutException {
            synchronized (monitor) {
                while (!completed) {
                    long remainingNanos = deadlineNanos - System.nanoTime();
                    if (remainingNanos <= 0) {
                        abandoned = true;
                        monitor.notifyAll();
                        throw new ProviderOpenTimeoutException();
                    }
                    TimeUnit.NANOSECONDS.timedWait(monitor, remainingNanos);
                }
                if (failure != null) {
                    throwFailure(failure);
                }
                if (deadlineNanos - System.nanoTime() <= 0) {
                    abandoned = true;
                    monitor.notifyAll();
                    throw new ProviderOpenTimeoutException();
                }
                StoredObject stored = opened;
                if (stored == null) {
                    throw new IllegalStateException("Managed object provider open result is unavailable");
                }
                opened = null;
                claimed = true;
                monitor.notifyAll();
                return stored;
            }
        }

        private void abandon() {
            Thread activeRunner;
            boolean neverStarted;
            synchronized (monitor) {
                if (claimed) {
                    return;
                }
                abandoned = true;
                activeRunner = runner;
                neverStarted = !started;
                monitor.notifyAll();
            }
            if (providerOpenExecutor.remove(this)
                    || (neverStarted && providerOpenExecutor.isShutdown())) {
                lease.close();
                synchronized (monitor) {
                    completed = true;
                    monitor.notifyAll();
                }
                finish();
                return;
            }
            if (activeRunner != null) {
                activeRunner.interrupt();
            }
        }

        private boolean isAbandoned() {
            synchronized (monitor) {
                return abandoned;
            }
        }

        private void finish() {
            synchronized (monitor) {
                runner = null;
            }
            providerOpenTasks.remove(this);
        }

        private void closeLate(StoredObject stored) {
            try {
                stored.close();
            } catch (IOException | RuntimeException | Error exception) {
                log.warn("Managed object late-open cleanup failed");
            }
        }
    }

    private static void throwFailure(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new ServiceUnavailableException("Private object streaming is unavailable");
    }

    private static final class ProviderOpenTimeoutException extends Exception {
    }
}
