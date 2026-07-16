package ooo.klae.connex.backend.storage;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.exceptions.TooManyRequestsException;

class ManagedObjectWriteAdmissionServiceTest {
    @Test
    void rejectsInsteadOfWaitingWhenAllWritePermitsAreInUse() throws Exception {
        ObjectStorageProperties properties = new ObjectStorageProperties();
        properties.setMaxConcurrentWrites(1);
        ManagedObjectWriteAdmissionService admission =
            new ManagedObjectWriteAdmissionService(properties);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread first = Thread.startVirtualThread(() -> {
            try {
                admission.admit(() -> {
                    entered.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                });
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        if (!entered.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Write admission did not start");
        }

        assertThrows(TooManyRequestsException.class,
            () -> admission.admit(() -> null));

        release.countDown();
        first.join();
        assertNull(failure.get());
    }
}
