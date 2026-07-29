package ooo.klae.connex.backend.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;

class ManagedObjectReadAdmissionServiceTest {
    @Test
    void holdsPerUserAndGlobalAdmissionUntilTheStreamCloses() throws Exception {
        ObjectStorageProperties properties = properties(1);
        ManagedObjectReadAdmissionService admission =
            new ManagedObjectReadAdmissionService(properties, () -> 9);
        try {
            StoredObject first = admission.admit(() -> stored(new byte[] {1, 2, 3}));

            assertThrows(
                TooManyRequestsException.class,
                () -> admission.admit(() -> stored(new byte[] {4})));

            first.close();
            try (StoredObject second = admission.admit(() -> stored(new byte[] {4}))) {
                assertEquals(4, second.inputStream().read());
            }
        } finally {
            admission.shutdown();
        }
    }

    @Test
    void providerOpenConsumesTheSameDeadlineAsTheOpenedStream() throws Exception {
        ObjectStorageProperties properties = properties(1);
        CountDownLatch providerClosed = new CountDownLatch(1);
        ManagedObjectReadAdmissionService admission =
            new ManagedObjectReadAdmissionService(properties, () -> 9);
        try {
            StoredObject opened = admission.admit(
                9,
                Duration.ofMillis(220),
                () -> {
                    LockSupport.parkNanos(Duration.ofMillis(140).toNanos());
                    return new StoredObject(new ByteArrayInputStream(new byte[] {1}) {
                        @Override
                        public void close() throws IOException {
                            providerClosed.countDown();
                            super.close();
                        }
                    }, 1);
                });

            assertTrue(providerClosed.await(150, TimeUnit.MILLISECONDS));
            opened.close();
        } finally {
            admission.shutdown();
        }
    }

    @Test
    void oneBlockedDeadlineCloseCannotStallAnotherAdmittedDeadline() throws Exception {
        ObjectStorageProperties properties = properties(2);
        CountDownLatch firstCloseEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstClose = new CountDownLatch(1);
        CountDownLatch secondClosed = new CountDownLatch(1);
        ManagedObjectReadAdmissionService admission =
            new ManagedObjectReadAdmissionService(properties, () -> 9);
        StoredObject first = null;
        StoredObject second = null;
        try {
            first = admission.admit(
                9,
                Duration.ofMillis(40),
                () -> new StoredObject(new ByteArrayInputStream(new byte[] {1}) {
                    @Override
                    public void close() {
                        firstCloseEntered.countDown();
                        awaitUninterruptibly(releaseFirstClose);
                    }
                }, 1));
            second = admission.admit(
                10,
                Duration.ofMillis(80),
                () -> new StoredObject(new ByteArrayInputStream(new byte[] {2}) {
                    @Override
                    public void close() {
                        secondClosed.countDown();
                    }
                }, 1));

            assertTrue(firstCloseEntered.await(1, TimeUnit.SECONDS));
            assertTrue(secondClosed.await(1, TimeUnit.SECONDS));
            assertEquals(2, timeoutExecutor(admission).getCorePoolSize());
        } finally {
            releaseFirstClose.countDown();
            if (first != null) {
                first.close();
            }
            if (second != null) {
                second.close();
            }
            admission.shutdown();
        }
    }

    @Test
    void timedOutInterruptIgnoringOpenRetainsPermitAndClosesLateStreamBeforeRecovery()
            throws Exception {
        ObjectStorageProperties properties = properties(1);
        CountDownLatch openEntered = new CountDownLatch(1);
        CountDownLatch releaseOpen = new CountDownLatch(1);
        CountDownLatch lateStreamClosed = new CountDownLatch(1);
        ManagedObjectReadAdmissionService admission =
            new ManagedObjectReadAdmissionService(properties, () -> 9);
        try {
            assertThrows(
                ServiceUnavailableException.class,
                () -> admission.admit(
                    9,
                    Duration.ofMillis(30),
                    () -> {
                        openEntered.countDown();
                        awaitUninterruptibly(releaseOpen);
                        return new StoredObject(new ByteArrayInputStream(new byte[] {1}) {
                            @Override
                            public void close() throws IOException {
                                lateStreamClosed.countDown();
                                super.close();
                            }
                        }, 1);
                    }));
            assertTrue(openEntered.await(1, TimeUnit.SECONDS));

            assertThrows(
                TooManyRequestsException.class,
                () -> admission.admit(
                    9,
                    Duration.ofSeconds(1),
                    () -> stored(new byte[] {2})));

            releaseOpen.countDown();
            assertTrue(lateStreamClosed.await(1, TimeUnit.SECONDS));
            try (StoredObject recovered = awaitAdmissionRecovery(admission)) {
                assertEquals(3, recovered.inputStream().read());
            }
            assertNoRetainedOpenTasks(admission);
        } finally {
            releaseOpen.countDown();
            admission.shutdown();
        }
    }

    @Test
    void interruptedCallerRestoresInterruptAndRetainsPermitUntilOpenExits()
            throws Exception {
        ObjectStorageProperties properties = properties(1);
        CountDownLatch openEntered = new CountDownLatch(1);
        CountDownLatch releaseOpen = new CountDownLatch(1);
        AtomicBoolean interruptRestored = new AtomicBoolean();
        ManagedObjectReadAdmissionService admission =
            new ManagedObjectReadAdmissionService(properties, () -> 9);
        try {
            Thread caller = Thread.ofPlatform().start(() -> {
                try {
                    admission.admit(
                        9,
                        Duration.ofSeconds(5),
                        () -> {
                            openEntered.countDown();
                            awaitUninterruptibly(releaseOpen);
                            return stored(new byte[] {1});
                        });
                } catch (ServiceUnavailableException exception) {
                    interruptRestored.set(Thread.currentThread().isInterrupted());
                }
            });
            assertTrue(openEntered.await(1, TimeUnit.SECONDS));

            caller.interrupt();
            caller.join(1_000);

            assertFalse(caller.isAlive());
            assertTrue(interruptRestored.get());
            assertThrows(
                TooManyRequestsException.class,
                () -> admission.admit(
                    9,
                    Duration.ofSeconds(1),
                    () -> stored(new byte[] {2})));
            releaseOpen.countDown();
            try (StoredObject recovered = awaitAdmissionRecovery(admission)) {
                assertEquals(3, recovered.inputStream().read());
            }
        } finally {
            releaseOpen.countDown();
            admission.shutdown();
        }
    }

    @Test
    void lateCloseFailureLogsNoExceptionOrProviderMetadata() throws Exception {
        ObjectStorageProperties properties = properties(1);
        CountDownLatch releaseOpen = new CountDownLatch(1);
        CountDownLatch closeAttempted = new CountDownLatch(1);
        ManagedObjectReadAdmissionService admission =
            new ManagedObjectReadAdmissionService(properties, () -> 9);
        Logger logger =
            (Logger) LoggerFactory.getLogger(ManagedObjectReadAdmissionService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThrows(
                ServiceUnavailableException.class,
                () -> admission.admit(
                    9,
                    Duration.ofMillis(30),
                    () -> {
                        awaitUninterruptibly(releaseOpen);
                        return new StoredObject(new ByteArrayInputStream(new byte[] {1}) {
                            @Override
                            public void close() throws IOException {
                                closeAttempted.countDown();
                                throw new IOException(
                                    "pii@example.com workspaces/9/private-object");
                            }
                        }, 1);
                    }));
            releaseOpen.countDown();
            assertTrue(closeAttempted.await(1, TimeUnit.SECONDS));
            try (StoredObject recovered = awaitAdmissionRecovery(admission)) {
                assertEquals(3, recovered.inputStream().read());
            }
            List<ILoggingEvent> events = List.copyOf(appender.list);

            assertEquals(1, events.size());
            assertEquals(
                "Managed object late-open cleanup failed",
                events.getFirst().getFormattedMessage());
            assertNull(events.getFirst().getThrowableProxy());
            assertFalse(events.getFirst().getFormattedMessage().contains("pii@example.com"));
            assertFalse(events.getFirst().getFormattedMessage().contains("private-object"));
        } finally {
            releaseOpen.countDown();
            logger.detachAppender(appender);
            appender.stop();
            admission.shutdown();
        }
    }

    @Test
    void nullAndOpenFailureReleaseAdmissionForTheNextRequest() throws Exception {
        ObjectStorageProperties properties = properties(1);
        ManagedObjectReadAdmissionService admission =
            new ManagedObjectReadAdmissionService(properties, () -> 9);
        try {
            assertThrows(ResourceNotFoundException.class, () -> admission.admit(() -> null));
            assertThrows(
                ServiceUnavailableException.class,
                () -> admission.admit(() -> {
                    throw new ServiceUnavailableException("provider failed");
                }));

            try (StoredObject next = admission.admit(() -> stored(new byte[] {7}))) {
                assertEquals(7, next.inputStream().read());
            }
        } finally {
            admission.shutdown();
        }
    }

    @Test
    void explicitCloseMakesEveryReadSurfaceFailClosed() throws Exception {
        ManagedObjectReadAdmissionService admission =
            new ManagedObjectReadAdmissionService(properties(1), () -> 9);
        try {
            StoredObject opened = admission.admit(() -> stored(new byte[] {1, 2, 3}));
            opened.close();

            assertThrows(IOException.class, () -> opened.inputStream().read());
            assertThrows(
                IOException.class,
                () -> opened.inputStream().read(new byte[3], 0, 3));
            assertThrows(
                IOException.class,
                () -> opened.inputStream().transferTo(new ByteArrayOutputStream()));
        } finally {
            admission.shutdown();
        }
    }

    @Test
    void deadlineDiscardsBytesReturnedByABlockedBulkReadAfterCapacityIsReused()
            throws Exception {
        CountDownLatch readEntered = new CountDownLatch(1);
        CountDownLatch releaseRead = new CountDownLatch(1);
        CountDownLatch providerClosed = new CountDownLatch(1);
        AtomicReference<Throwable> readFailure = new AtomicReference<>();
        byte[] callerBuffer = new byte[1];
        ManagedObjectReadAdmissionService admission =
            new ManagedObjectReadAdmissionService(properties(1), () -> 9);
        StoredObject opened = null;
        try {
            opened = admission.admit(
                9,
                Duration.ofMillis(60),
                () -> new StoredObject(new ByteArrayInputStream(new byte[0]) {
                    @Override
                    public int read(byte[] bytes, int offset, int length) {
                        readEntered.countDown();
                        awaitUninterruptibly(releaseRead);
                        bytes[offset] = 91;
                        return 1;
                    }

                    @Override
                    public void close() {
                        providerClosed.countDown();
                    }
                }, 1));
            StoredObject blockedObject = opened;
            Thread reader = Thread.ofPlatform().start(() -> {
                try {
                    blockedObject.inputStream().read(callerBuffer, 0, 1);
                } catch (Throwable exception) {
                    readFailure.set(exception);
                }
            });
            assertTrue(readEntered.await(1, TimeUnit.SECONDS));
            assertTrue(providerClosed.await(1, TimeUnit.SECONDS));

            try (StoredObject reused = admission.admit(
                    10,
                    Duration.ofSeconds(1),
                    () -> stored(new byte[] {7}))) {
                releaseRead.countDown();
                reader.join(1_000);
                assertFalse(reader.isAlive());
                assertTrue(readFailure.get() instanceof IOException);
                assertEquals(0, callerBuffer[0]);
                assertEquals(7, reused.inputStream().read());
            }
        } finally {
            releaseRead.countDown();
            if (opened != null) {
                opened.close();
            }
            admission.shutdown();
        }
    }

    @Test
    void transferToUsesTheGuardedWrapperReadPath() throws Exception {
        ManagedObjectReadAdmissionService admission =
            new ManagedObjectReadAdmissionService(properties(1), () -> 9);
        try (StoredObject opened = admission.admit(
                9,
                Duration.ofSeconds(1),
                () -> new StoredObject(new ByteArrayInputStream(new byte[] {4, 5}) {
                    @Override
                    public long transferTo(OutputStream output) {
                        throw new AssertionError("Provider transferTo must not be used");
                    }
                }, 2))) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();

            assertEquals(2, opened.inputStream().transferTo(output));
            assertEquals(List.of((byte) 4, (byte) 5), bytes(output.toByteArray()));
        } finally {
            admission.shutdown();
        }
    }

    private static ObjectStorageProperties properties(int capacity) {
        ObjectStorageProperties properties = new ObjectStorageProperties();
        properties.setMaxConcurrentReads(capacity);
        properties.setMaxConcurrentReadsPerUser(capacity);
        properties.setReadTimeoutMs(1_000);
        return properties;
    }

    private static StoredObject awaitAdmissionRecovery(
            ManagedObjectReadAdmissionService admission) {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        TooManyRequestsException lastFailure = null;
        while (System.nanoTime() < deadlineNanos) {
            try {
                return admission.admit(
                    9,
                    Duration.ofSeconds(1),
                    () -> stored(new byte[] {3}));
            } catch (TooManyRequestsException exception) {
                lastFailure = exception;
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
            }
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        throw new AssertionError("Admission did not recover");
    }

    private static void assertNoRetainedOpenTasks(
            ManagedObjectReadAdmissionService admission) throws Exception {
        Field tasksField =
            ManagedObjectReadAdmissionService.class.getDeclaredField("providerOpenTasks");
        tasksField.setAccessible(true);
        Object tasksValue = tasksField.get(admission);
        if (!(tasksValue instanceof Set<?> tasks)) {
            throw new AssertionError("Provider task registry is unavailable");
        }
        Field executorField =
            ManagedObjectReadAdmissionService.class.getDeclaredField("providerOpenExecutor");
        executorField.setAccessible(true);
        Object executorValue = executorField.get(admission);
        if (!(executorValue instanceof ThreadPoolExecutor executor)) {
            throw new AssertionError("Provider executor is unavailable");
        }
        Field timeoutField =
            ManagedObjectReadAdmissionService.class.getDeclaredField("timeoutExecutor");
        timeoutField.setAccessible(true);
        Object timeoutValue = timeoutField.get(admission);
        if (!(timeoutValue instanceof ScheduledThreadPoolExecutor timeoutExecutor)) {
            throw new AssertionError("Read timeout executor is unavailable");
        }

        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < deadlineNanos
                && (!tasks.isEmpty()
                    || !executor.getQueue().isEmpty()
                    || !timeoutExecutor.getQueue().isEmpty())) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        }
        assertTrue(tasks.isEmpty());
        assertTrue(executor.getQueue().isEmpty());
        assertTrue(timeoutExecutor.getQueue().isEmpty());
    }

    private static ScheduledThreadPoolExecutor timeoutExecutor(
            ManagedObjectReadAdmissionService admission) throws Exception {
        Field timeoutField =
            ManagedObjectReadAdmissionService.class.getDeclaredField("timeoutExecutor");
        timeoutField.setAccessible(true);
        Object timeoutValue = timeoutField.get(admission);
        if (!(timeoutValue instanceof ScheduledThreadPoolExecutor timeoutExecutor)) {
            throw new AssertionError("Read timeout executor is unavailable");
        }
        return timeoutExecutor;
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static StoredObject stored(byte[] bytes) {
        return new StoredObject(new ByteArrayInputStream(bytes), bytes.length);
    }

    private static List<Byte> bytes(byte[] values) {
        List<Byte> result = new java.util.ArrayList<>(values.length);
        for (byte value : values) {
            result.add(value);
        }
        return result;
    }
}
