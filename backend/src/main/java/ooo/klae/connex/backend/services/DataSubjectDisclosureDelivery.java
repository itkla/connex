package ooo.klae.connex.backend.services;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Bounds pass-two delivery without moving serialization, tenant context, or database work off-thread. */
@Component
public class DataSubjectDisclosureDelivery implements AutoCloseable {
    private static final ThreadLocal<Runnable> TRANSPORT_ABORT = new ThreadLocal<>();
    private final long timeoutMillis;
    private final long timeoutNanos;
    private final DataSource dataSource;
    private final ScheduledThreadPoolExecutor timer;
    private volatile boolean transportRequired;

    public DataSubjectDisclosureDelivery(
            @Value("${connex.data-subject-disclosure.delivery-timeout-ms:120000}") long timeoutMillis,
            DataSource dataSource) {
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("Disclosure delivery timeout must be positive");
        }
        this.timeoutMillis = timeoutMillis;
        this.timeoutNanos = Math.multiplyExact(timeoutMillis, 1_000_000L);
        this.dataSource = dataSource;
        timer = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "disclosure-delivery-deadline");
            thread.setDaemon(true);
            return thread;
        });
        timer.setRemoveOnCancelPolicy(true);
    }

    /** Installs only the current synchronous transport's cancellation action, never a reusable request facade. */
    public static TransportScope withTransport(Runnable abort) {
        Runnable previous = TRANSPORT_ABORT.get();
        TRANSPORT_ABORT.set(abort);
        return () -> {
            if (previous == null) {
                TRANSPORT_ABORT.remove();
            } else {
                TRANSPORT_ABORT.set(previous);
            }
        };
    }

    /** Starts after the durable audit; remaining transaction time also bounds subsequent MyBatis statements. */
    public Deadline start() {
        Runnable abort = TRANSPORT_ABORT.get();
        if (transportRequired && abort == null) {
            throw new IllegalStateException("Disclosure requires a cancellable synchronous transport");
        }
        Object resource = TransactionSynchronizationManager.getResource(dataSource);
        if (resource instanceof ConnectionHolder connection) {
            long remainingMillis = connection.hasTimeout()
                ? Math.min(timeoutMillis, connection.getTimeToLiveInMillis()) : timeoutMillis;
            connection.setTimeoutInMillis(remainingMillis);
        }
        return new Deadline(abort);
    }

    /** Enabled by the connector customizer before a live server can accept its first request. */
    public void requireTransport() {
        transportRequired = true;
    }

    @PreDestroy
    @Override
    public void close() {
        timer.shutdownNow();
    }

    /** Removes the transport binding before Tomcat can reuse the request thread. */
    @FunctionalInterface
    public interface TransportScope extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * Cancellation and disarming share a monitor: a late task cannot close a reused connection.
     * Socket closure stops progress; a blocked native write unwinds within one connector write timeout.
     */
    public final class Deadline implements AutoCloseable {
        private final long startedAt = System.nanoTime();
        private final ScheduledFuture<?> expiration;
        private Runnable abort;
        private boolean active = true;
        private boolean expired;

        private Deadline(Runnable abort) {
            this.abort = abort;
            expiration = timer.schedule(this::expire, timeoutNanos, TimeUnit.NANOSECONDS);
        }

        /** Checked before page reads/writes, at bounded byte intervals, and after every blocking output call. */
        public synchronized void check() {
            if (System.nanoTime() - startedAt >= timeoutNanos) {
                expire();
            }
            if (expired) {
                throw new IllegalStateException("Disclosure delivery deadline exceeded");
            }
        }

        private synchronized void expire() {
            if (active && !expired) {
                expired = true;
                if (abort != null) {
                    abort.run();
                }
            }
        }

        @Override
        public synchronized void close() {
            active = false;
            abort = null;
            expiration.cancel(false);
        }
    }
}
