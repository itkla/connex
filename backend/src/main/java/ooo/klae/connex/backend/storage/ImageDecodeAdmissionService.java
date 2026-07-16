package ooo.klae.connex.backend.storage;

import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Component;

/**
 * Shares bounded decode concurrency and estimated image-working memory across upload paths.
 */
@Component
public class ImageDecodeAdmissionService {
    private static final long MEMORY_UNIT_BYTES = 1024L * 1024L;

    private final Semaphore decodeSlots;
    private final Semaphore memoryUnits;
    private final long maxWorkingBytes;

    public ImageDecodeAdmissionService(ObjectStorageProperties properties) {
        maxWorkingBytes = properties.getMaxImageWorkingBytes();
        decodeSlots = new Semaphore(properties.getMaxConcurrentImageDecodes(), true);
        memoryUnits = new Semaphore(units(maxWorkingBytes), true);
    }

    public Optional<Lease> tryAcquire() {
        if (!decodeSlots.tryAcquire()) {
            return Optional.empty();
        }
        return Optional.of(new Lease(decodeSlots, memoryUnits));
    }

    public boolean supports(long estimatedWorkingBytes) {
        return estimatedWorkingBytes > 0 && estimatedWorkingBytes <= maxWorkingBytes;
    }

    private static int units(long bytes) {
        return Math.toIntExact(Math.floorDiv(
            Math.addExact(bytes, MEMORY_UNIT_BYTES - 1), MEMORY_UNIT_BYTES));
    }

    /**
     * One admitted decode whose estimated working memory can be reserved exactly once.
     */
    public static final class Lease implements AutoCloseable {
        private final Semaphore decodeSlots;
        private final Semaphore memoryUnits;
        private final AtomicBoolean closed = new AtomicBoolean();
        private int reservedUnits;

        private Lease(Semaphore decodeSlots, Semaphore memoryUnits) {
            this.decodeSlots = decodeSlots;
            this.memoryUnits = memoryUnits;
        }

        public boolean tryReserve(long estimatedWorkingBytes) {
            if (reservedUnits != 0) {
                throw new IllegalStateException("Image decode working memory is already reserved");
            }
            if (estimatedWorkingBytes <= 0) {
                throw new IllegalArgumentException("Image decode working memory must be positive");
            }
            int requestedUnits = units(estimatedWorkingBytes);
            if (!memoryUnits.tryAcquire(requestedUnits)) {
                return false;
            }
            reservedUnits = requestedUnits;
            return true;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            if (reservedUnits > 0) {
                memoryUnits.release(reservedUnits);
            }
            decodeSlots.release();
        }
    }
}
