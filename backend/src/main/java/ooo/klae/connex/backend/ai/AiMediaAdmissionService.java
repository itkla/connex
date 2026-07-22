package ooo.klae.connex.backend.ai;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Component;

import ooo.klae.connex.backend.ai.provider.AiInputImage;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;

/**
 * Holds global, organization, and estimated-memory admission for embedded AI media until provider
 * response parsing completes.
 */
@Component
public class AiMediaAdmissionService {
    private static final long IMAGE_EXPANSION_FACTOR = 8;
    private static final long RESPONSE_EXPANSION_FACTOR = 2;

    private final Semaphore globalPermits;
    private final int maxConcurrentPerOrg;
    private final int maxResponseBytes;
    private final long maxMediaWorkingBytes;
    private final Map<Integer, Integer> activeByOrg = new HashMap<>();
    private long activeWorkingBytes;

    public AiMediaAdmissionService(AiProperties properties) {
        globalPermits = new Semaphore(
                positive(properties.getMaxConcurrentMediaRequests(), "maximum concurrent media requests"),
                true);
        maxConcurrentPerOrg = positive(
                properties.getMaxConcurrentMediaRequestsPerOrg(),
                "maximum concurrent media requests per organization");
        if (maxConcurrentPerOrg > properties.getMaxConcurrentMediaRequests()) {
            throw new IllegalStateException(
                    "AI maximum concurrent media requests per organization exceeds the global limit");
        }
        maxMediaWorkingBytes = positiveLong(
                properties.getMaxMediaWorkingBytes(), "maximum media working bytes");
        maxResponseBytes = positive(properties.getMaxResponseBytes(), "maximum response bytes");
    }

    public Lease acquire(int orgId, List<AiInputImage> images) {
        if (orgId <= 0 || images == null || images.isEmpty()) {
            throw new IllegalArgumentException("AI media admission requires an organization and image");
        }
        long imageBytes = images.stream().mapToLong(AiInputImage::size).sum();
        long estimatedWorkingBytes = estimatedWorkingBytes(imageBytes);
        if (estimatedWorkingBytes > maxMediaWorkingBytes) {
            throw busy();
        }
        if (!globalPermits.tryAcquire()) {
            throw busy();
        }
        synchronized (activeByOrg) {
            int active = activeByOrg.getOrDefault(orgId, 0);
            if (active >= maxConcurrentPerOrg
                    || estimatedWorkingBytes > maxMediaWorkingBytes - activeWorkingBytes) {
                globalPermits.release();
                throw busy();
            }
            activeByOrg.put(orgId, active + 1);
            activeWorkingBytes += estimatedWorkingBytes;
        }
        return new Lease(orgId, estimatedWorkingBytes);
    }

    private long estimatedWorkingBytes(long imageBytes) {
        try {
            return Math.addExact(
                    Math.multiplyExact(imageBytes, IMAGE_EXPANSION_FACTOR),
                    Math.multiplyExact((long) maxResponseBytes, RESPONSE_EXPANSION_FACTOR));
        } catch (ArithmeticException exception) {
            throw busy();
        }
    }

    private static int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalStateException("AI " + name + " must be positive");
        }
        return value;
    }

    private static long positiveLong(long value, String name) {
        if (value <= 0) {
            throw new IllegalStateException("AI " + name + " must be positive");
        }
        return value;
    }

    private static TooManyRequestsException busy() {
        return new TooManyRequestsException("AI image processing is busy; retry shortly");
    }

    /** One admitted media invocation. */
    public final class Lease implements AutoCloseable {
        private final int orgId;
        private final long reservedWorkingBytes;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(int orgId, long reservedWorkingBytes) {
            this.orgId = orgId;
            this.reservedWorkingBytes = reservedWorkingBytes;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            synchronized (activeByOrg) {
                int remaining = activeByOrg.getOrDefault(orgId, 1) - 1;
                if (remaining == 0) {
                    activeByOrg.remove(orgId);
                } else {
                    activeByOrg.put(orgId, remaining);
                }
                activeWorkingBytes -= reservedWorkingBytes;
            }
            globalPermits.release();
        }
    }
}
