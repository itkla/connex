package ooo.klae.connex.backend.ai.egress;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.AiProperties;

class AiEndpointAddressValidatorTest {
    private final AiEndpointAddressValidator validator = new AiEndpointAddressValidator(new AiProperties());

    @AfterEach
    void tearDown() {
        validator.shutdown();
    }

    @Test
    void isFetchableAppliesTheRuntimeAddressClassPolicy() {
        assertTrue(validator.isFetchable("8.8.8.8", false));
        assertFalse(validator.isFetchable("10.0.0.12", false));
        assertTrue(validator.isFetchable("10.0.0.12", true));
        assertFalse(validator.isFetchable("8.8.8.8", true));
    }

    @Test
    void constructorRejectsInvalidNat64Configuration() {
        AiProperties properties = new AiProperties();
        properties.setNat64Prefixes("2001:db8::/33");

        assertThrows(IllegalStateException.class, () -> new AiEndpointAddressValidator(properties));
    }

    @Test
    void isFetchableBoundsResolutionThatIgnoresInterruption() throws Exception {
        AiProperties properties = new AiProperties();
        properties.setConnectTimeoutMs(50);
        CountDownLatch release = new CountDownLatch(1);
        AiEndpointAddressValidator slowValidator = new AiEndpointAddressValidator(properties) {
            @Override
            public InetAddress resolveFetchable(String host, boolean allowPrivate) {
                boolean interrupted = false;
                while (true) {
                    try {
                        release.await();
                        break;
                    } catch (InterruptedException exception) {
                        interrupted = true;
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                return InetAddress.getLoopbackAddress();
            }
        };
        long started = System.nanoTime();
        try {
            assertFalse(slowValidator.isFetchable("provider.example.test", false));
            assertTrue(java.time.Duration.ofNanos(System.nanoTime() - started).toMillis() < 5000);
        } finally {
            release.countDown();
            slowValidator.shutdown();
        }
    }

    @Test
    void isFetchableFailsClosedWhenResolutionWorkersAreSaturated() throws Exception {
        AiProperties properties = new AiProperties();
        properties.setConnectTimeoutMs(50);
        CountDownLatch resolversStarted = new CountDownLatch(2);
        CountDownLatch releaseResolvers = new CountDownLatch(1);
        AiEndpointAddressValidator slowValidator = new AiEndpointAddressValidator(properties) {
            @Override
            public InetAddress resolveFetchable(String host, boolean allowPrivate) {
                resolversStarted.countDown();
                boolean interrupted = false;
                while (true) {
                    try {
                        releaseResolvers.await();
                        break;
                    } catch (InterruptedException exception) {
                        interrupted = true;
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                return InetAddress.getLoopbackAddress();
            }
        };
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = callers.submit(
                    () -> slowValidator.isFetchable("first.provider.example.test", false));
            Future<Boolean> second = callers.submit(
                    () -> slowValidator.isFetchable("second.provider.example.test", false));
            assertTrue(resolversStarted.await(1, TimeUnit.SECONDS));

            long started = System.nanoTime();
            assertFalse(slowValidator.isFetchable("third.provider.example.test", false));
            assertTrue(java.time.Duration.ofNanos(System.nanoTime() - started).toMillis() < 5000);
            assertFalse(first.get(1, TimeUnit.SECONDS));
            assertFalse(second.get(1, TimeUnit.SECONDS));
        } finally {
            releaseResolvers.countDown();
            callers.shutdownNow();
            slowValidator.shutdown();
        }
    }
}
