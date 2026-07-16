package ooo.klae.connex.backend.storage;

import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import ooo.klae.connex.backend.exceptions.TooManyRequestsException;

/**
 * Bounds provider writes without making metadata transactions wait while holding database resources.
 */
@Component
public class ManagedObjectWriteAdmissionService {
    private final Semaphore permits;

    public ManagedObjectWriteAdmissionService(ObjectStorageProperties properties) {
        permits = new Semaphore(properties.getMaxConcurrentWrites(), true);
    }

    public <T> T admit(Supplier<T> write) {
        if (!permits.tryAcquire()) {
            throw new TooManyRequestsException("Private object storage is busy; retry shortly");
        }
        try {
            return write.get();
        } finally {
            permits.release();
        }
    }
}
