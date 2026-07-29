package ooo.klae.connex.backend.services;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;

/** Single execution owner for tenant-export state, deadline, cancellation, and cleanup. */
final class TenantExportExecution {
    private static final Logger log = LoggerFactory.getLogger(TenantExportExecution.class);

    private final Object monitor = new Object();
    private final long deadlineNanos;
    private final ThreadPoolExecutor cancellationExecutor;
    private final Cleanup cleanup;
    private final List<TrackedResource> resources = new ArrayList<>();
    private final AtomicBoolean deadlineArmClaimed = new AtomicBoolean();
    private final AtomicReference<ScheduledFuture<?>> deadlineTask =
        new AtomicReference<>();
    private final AtomicBoolean completionPublished = new AtomicBoolean();
    private final AtomicBoolean cancellationDispatchClaimed = new AtomicBoolean();

    private State state = State.NEW;
    private boolean writerStarted;
    private boolean cancellationDispatchCompleted;
    private Throwable pendingFailure;

    TenantExportExecution(
            Duration timeout,
            ThreadPoolExecutor cancellationExecutor,
            Cleanup cleanup) {
        Objects.requireNonNull(timeout, "timeout");
        this.cancellationExecutor =
            Objects.requireNonNull(cancellationExecutor, "cancellationExecutor");
        this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Tenant export timeout must be positive");
        }
        long timeoutNanos = timeout.toNanos();
        deadlineNanos = System.nanoTime() + timeoutNanos;
    }

    void armDeadline(ScheduledThreadPoolExecutor deadlineExecutor) {
        Objects.requireNonNull(deadlineExecutor, "deadlineExecutor");
        if (!deadlineArmClaimed.compareAndSet(false, true)) {
            throw new IllegalStateException("Tenant export deadline is single-use");
        }
        long remainingNanos = Math.max(deadlineNanos - System.nanoTime(), 0);
        ScheduledFuture<?> scheduled = deadlineExecutor.schedule(
            this::cancel,
            remainingNanos,
            TimeUnit.NANOSECONDS);
        if (!deadlineTask.compareAndSet(null, scheduled)) {
            scheduled.cancel(false);
            throw new IllegalStateException("Tenant export deadline ownership changed");
        }
        if (completionPublished.get()) {
            scheduled.cancel(false);
        }
    }

    void begin() throws IOException {
        synchronized (monitor) {
            if (!deadlineArmClaimed.get()) {
                throw new IllegalStateException("Tenant export deadline is not armed");
            }
            if (state != State.NEW) {
                throw new IllegalStateException("Tenant export download is single-use");
            }
            if (remainingNanosLocked() > 0) {
                state = State.WRITING;
                writerStarted = true;
                return;
            }
        }
        cancel();
        throw cancelled();
    }

    TrackedResource track(AutoCloseable resource) throws IOException {
        Objects.requireNonNull(resource, "resource");
        TrackedResource tracked = new TrackedResource(resource);
        synchronized (monitor) {
            if (state == State.WRITING && remainingNanosLocked() > 0) {
                resources.add(tracked);
                return tracked;
            }
        }
        Throwable closeFailure = tracked.closeForCancellation(null);
        if (closeFailure != null) {
            synchronized (monitor) {
                if (state != State.DONE) {
                    resources.add(tracked);
                    pendingFailure = appendFailure(pendingFailure, closeFailure);
                }
            }
            ExportCancelledException exception = cancelled();
            exception.addSuppressed(closeFailure);
            throw exception;
        }
        throw cancelled();
    }

    void checkActive() throws IOException {
        boolean deadlineReached;
        synchronized (monitor) {
            deadlineReached = state == State.WRITING && remainingNanosLocked() <= 0;
            if (!deadlineReached && state == State.WRITING) {
                return;
            }
        }
        if (deadlineReached) {
            cancel();
        }
        throw cancelled();
    }

    long boundedDeadlineNanos(Duration cap) throws IOException {
        Objects.requireNonNull(cap, "cap");
        if (cap.isZero() || cap.isNegative()) {
            throw new IllegalArgumentException("Tenant export operation timeout must be positive");
        }
        checkActive();
        long now = System.nanoTime();
        long capNanos = cap.toNanos();
        long exportRemaining;
        synchronized (monitor) {
            exportRemaining = deadlineNanos - now;
        }
        if (exportRemaining <= 0) {
            cancel();
            throw cancelled();
        }
        return exportRemaining <= capNanos
            ? deadlineNanos
            : now + capNanos;
    }

    long remainingTimeoutMillis() {
        synchronized (monitor) {
            long remaining = Math.max(remainingNanosLocked(), 1);
            long millis = TimeUnit.NANOSECONDS.toMillis(remaining);
            return millis == 0 ? 1 : millis;
        }
    }

    int remainingQueryTimeoutSeconds() throws IOException {
        checkActive();
        long remaining;
        synchronized (monitor) {
            remaining = remainingNanosLocked();
        }
        long seconds = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(remaining - 1) + 1);
        return Math.toIntExact(Math.min(seconds, Integer.MAX_VALUE));
    }

    void cancel() {
        synchronized (monitor) {
            if (state == State.DONE) {
                return;
            }
            state = State.CANCELLING;
        }
        if (!cancellationDispatchClaimed.compareAndSet(false, true)) {
            return;
        }
        try {
            cancellationExecutor.execute(this::cancelOwnedResources);
        } catch (RejectedExecutionException exception) {
            synchronized (monitor) {
                pendingFailure = appendFailure(
                    pendingFailure,
                    new IllegalStateException(
                        "Tenant export cancellation dispatch was rejected"));
                cancellationDispatchClaimed.set(false);
                monitor.notifyAll();
            }
            log.error("Tenant export cancellation cleanup dispatch failed");
        }
    }

    void writerFinished(Throwable primary) throws IOException {
        List<TrackedResource> toClose;
        boolean interrupted = false;
        synchronized (monitor) {
            if (state != State.WRITING && state != State.CANCELLING) {
                throw new IllegalStateException("Tenant export writer does not own the execution");
            }
            state = State.CANCELLING;
            while (!cancellationDispatchCompleted
                    && !cancellationDispatchClaimed.compareAndSet(false, true)) {
                try {
                    monitor.wait();
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            toClose = snapshotResourcesLocked();
        }
        try {
            Throwable failure = closeForCancellation(toClose, null);
            synchronized (monitor) {
                failure = appendFailure(pendingFailure, failure);
                pendingFailure = null;
            }
            failure = cleanup.cleanup(failure);
            complete();
            if (primary != null) {
                if (failure != null && failure != primary) {
                    primary.addSuppressed(failure);
                }
                return;
            }
            throwFailure(failure);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void finishWithoutWriter() {
        Throwable failure;
        synchronized (monitor) {
            failure = pendingFailure;
            pendingFailure = null;
        }
        failure = cleanup.cleanup(failure);
        complete();
        if (failure != null) {
            log.error("Tenant export cleanup failed");
        }
    }

    private void complete() {
        synchronized (monitor) {
            state = State.DONE;
            resources.clear();
            monitor.notifyAll();
        }
        completionPublished.set(true);
        ScheduledFuture<?> scheduled = deadlineTask.get();
        if (scheduled != null) {
            scheduled.cancel(false);
        }
    }

    private void cancelOwnedResources() {
        List<TrackedResource> toClose;
        synchronized (monitor) {
            if (state == State.DONE) {
                cancellationDispatchCompleted = true;
                monitor.notifyAll();
                return;
            }
            toClose = snapshotResourcesLocked();
        }
        Throwable failure = closeForCancellation(toClose, null);
        synchronized (monitor) {
            pendingFailure = appendFailure(pendingFailure, failure);
            cancellationDispatchCompleted = true;
            monitor.notifyAll();
        }
        if (!writerStarted()) {
            finishWithoutWriter();
        }
    }

    private List<TrackedResource> snapshotResourcesLocked() {
        List<TrackedResource> snapshot = new ArrayList<>(resources.size());
        for (int index = resources.size() - 1; index >= 0; index--) {
            snapshot.add(resources.get(index));
        }
        return snapshot;
    }

    private boolean writerStarted() {
        synchronized (monitor) {
            return writerStarted;
        }
    }

    private static Throwable closeForCancellation(
            List<TrackedResource> resources,
            Throwable priorFailure) {
        Throwable failure = priorFailure;
        for (TrackedResource resource : resources) {
            failure = resource.closeForCancellation(failure);
        }
        return failure;
    }

    private long remainingNanosLocked() {
        return deadlineNanos - System.nanoTime();
    }

    private void unregister(TrackedResource resource) {
        synchronized (monitor) {
            resources.remove(resource);
        }
    }

    private static Throwable appendFailure(Throwable priorFailure, Throwable failure) {
        if (failure == null) {
            return priorFailure;
        }
        if (priorFailure == null) {
            return failure;
        }
        if (priorFailure == failure) {
            return priorFailure;
        }
        priorFailure.addSuppressed(failure);
        return priorFailure;
    }

    private static void throwFailure(Throwable failure) throws IOException {
        if (failure == null) {
            return;
        }
        if (failure instanceof IOException ioException) {
            throw ioException;
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new ServiceUnavailableException("Tenant export cleanup failed", failure);
    }

    private static ExportCancelledException cancelled() {
        return new ExportCancelledException("Tenant export was cancelled");
    }

    /** Idempotent tracked resource that supports normal close or cancellation-only release. */
    final class TrackedResource implements AutoCloseable {
        private final AutoCloseable resource;
        private final AtomicBoolean closeClaimed = new AtomicBoolean();
        private final AtomicBoolean released = new AtomicBoolean();

        private TrackedResource(AutoCloseable resource) {
            this.resource = resource;
        }

        @Override
        public void close() throws IOException {
            if (!closeClaimed.compareAndSet(false, true)) {
                return;
            }
            try {
                resource.close();
                release();
            } catch (IOException exception) {
                closeClaimed.set(false);
                throw exception;
            } catch (Exception exception) {
                closeClaimed.set(false);
                throw new IOException("Tenant export resource close failed", exception);
            } catch (Error error) {
                closeClaimed.set(false);
                throw error;
            }
        }

        void release() {
            if (released.compareAndSet(false, true)) {
                unregister(this);
            }
        }

        private Throwable closeForCancellation(Throwable priorFailure) {
            if (!closeClaimed.compareAndSet(false, true)) {
                return priorFailure;
            }
            try {
                resource.close();
                release();
                return priorFailure;
            } catch (Exception | Error exception) {
                closeClaimed.set(false);
                return appendFailure(priorFailure, exception);
            }
        }

    }

    @FunctionalInterface
    interface Cleanup {
        Throwable cleanup(Throwable priorFailure);
    }

    private enum State {
        NEW,
        WRITING,
        CANCELLING,
        DONE
    }

    private static final class ExportCancelledException extends IOException {
        private ExportCancelledException(String message) {
            super(message);
        }
    }
}
