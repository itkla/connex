package ooo.klae.connex.backend.storage;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/** Bounds caller wait to five seconds and isolates untrusted image decodes behind fixed capacity. */
@Component
public class BoundedImageValidationExecutor implements AutoCloseable {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_CONCURRENT_VALIDATIONS = 2;
    private static final int MAX_QUEUED_VALIDATIONS = 8;

    private final ExecutorService executor;
    private final Duration timeout;

    public BoundedImageValidationExecutor() {
        this(
            new ThreadPoolExecutor(
                MAX_CONCURRENT_VALIDATIONS,
                MAX_CONCURRENT_VALIDATIONS,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAX_QUEUED_VALIDATIONS),
                Thread.ofPlatform().daemon().name("image-validation-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy()),
            TIMEOUT);
    }

    BoundedImageValidationExecutor(ExecutorService executor, Duration timeout) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    /**
     * Runs one image parser within the shared concurrency and wall-clock bounds.
     *
     * @param operation parser operation
     * @param rejected safe exception factory for timeout or unavailable capacity
     * @return parser result
     * @param <T> parser result type
     */
    public <T> T validate(
            Function<Cancellation, T> operation,
            Supplier<? extends RuntimeException> rejected) {
        Cancellation cancellation = new Cancellation();
        Future<T> future;
        try {
            future = executor.submit(() -> operation.apply(cancellation));
        } catch (RuntimeException exception) {
            throw rejected.get();
        }
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            cancellation.abort();
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw rejected.get();
        } catch (TimeoutException exception) {
            cancellation.abort();
            future.cancel(true);
            throw rejected.get();
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof RuntimeException failure) {
                throw failure;
            }
            throw rejected.get();
        }
    }

    @Override
    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }

    /** Coordinates parser-specific abort methods with the caller-side timeout. */
    public static final class Cancellation {
        private final AtomicBoolean aborted = new AtomicBoolean();
        private final AtomicReference<Runnable> aborter = new AtomicReference<>();

        /**
         * Registers the currently active parser or writer abort operation.
         *
         * @param operation thread-safe abort operation supplied by the parser API
         */
        public void register(Runnable operation) {
            Runnable required = Objects.requireNonNull(operation, "operation");
            aborter.set(required);
            if (aborted.get()) {
                abortSafely(required);
            }
        }

        /** @return whether the caller-side deadline has already elapsed */
        public boolean cancelled() {
            return aborted.get();
        }

        private void abort() {
            aborted.set(true);
            Runnable operation = aborter.get();
            if (operation != null) {
                abortSafely(operation);
            }
        }

        private static boolean abortSafely(Runnable operation) {
            try {
                operation.run();
                return true;
            } catch (RuntimeException exception) {
                return false;
            }
        }
    }
}
