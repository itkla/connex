package ooo.klae.connex.backend.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.exceptions.TooManyRequestsException;

class ManagedObjectReadAdmissionServiceTest {
    @Test
    void holdsPerUserAndGlobalAdmissionUntilTheStreamCloses() throws Exception {
        ObjectStorageProperties properties = new ObjectStorageProperties();
        properties.setMaxConcurrentReads(1);
        properties.setMaxConcurrentReadsPerUser(1);
        ManagedObjectReadAdmissionService admission =
            new ManagedObjectReadAdmissionService(properties, () -> 9);
        StoredObject first = admission.admit(() -> stored(new byte[] {1, 2, 3}));

        assertThrows(TooManyRequestsException.class,
            () -> admission.admit(() -> stored(new byte[] {4})));

        first.close();
        try (StoredObject second = admission.admit(() -> stored(new byte[] {4}))) {
            assertEquals(4, second.inputStream().read());
        }
        admission.shutdown();
    }

    @Test
    void hardDeadlineClosesAnAbandonedProviderStream() throws Exception {
        ObjectStorageProperties properties = new ObjectStorageProperties();
        properties.setReadTimeoutMs(1_000);
        AtomicBoolean closed = new AtomicBoolean();
        CountDownLatch providerClosed = new CountDownLatch(1);
        InputStream input = new ByteArrayInputStream(new byte[] {1}) {
            @Override
            public void close() throws IOException {
                closed.set(true);
                providerClosed.countDown();
                super.close();
            }
        };
        ManagedObjectReadAdmissionService admission =
            new ManagedObjectReadAdmissionService(properties, () -> 9);

        admission.admit(() -> new StoredObject(input, 1));

        assertTrue(providerClosed.await(2, TimeUnit.SECONDS));
        assertTrue(closed.get());
        admission.shutdown();
    }

    private static StoredObject stored(byte[] bytes) {
        return new StoredObject(new ByteArrayInputStream(bytes), bytes.length);
    }
}
