package ooo.klae.connex.backend.businesscard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServiceUnavailable;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import tools.jackson.databind.ObjectMapper;

class BusinessCardOcrClientTest {
    private static final String TOKEN = "test-service-token-0000000000000000";
    private static final URI BASE = URI.create("http://ocr.example.test:8090");

    @Test
    void disablesJvmProxyRoutingForPrivateOcrTraffic() {
        BusinessCardProperties properties = new BusinessCardProperties();

        HttpClient client = BusinessCardOcrClient.newHttpClient(properties);

        assertSame(HttpClient.Builder.NO_PROXY, client.proxy().orElseThrow());
    }

    @Test
    void maintenanceModeDisablesBackgroundReadiness() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BusinessCardProperties properties = new BusinessCardProperties();
        properties.setEnabled(true);
        properties.setOcrBaseUrl(BASE);
        properties.setOcrServiceToken(TOKEN);
        BusinessCardOcrClient client = new BusinessCardOcrClient(
            builder.build(), new ObjectMapper(), properties, BASE, false);

        assertFalse(client.isReady());
        assertFalse(client.isReadyForScan());
        server.verify();
    }

    @Test
    void cachedReadinessRemainsLastKnownAfterItsRefreshDeadline() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BusinessCardProperties properties = properties(Duration.ofMillis(1));
        BusinessCardOcrClient client = new BusinessCardOcrClient(
                builder.build(), new ObjectMapper(), properties, BASE, false);
        ReflectionTestUtils.setField(client, "cachedReady", true);
        ReflectionTestUtils.setField(client, "readinessExpiresAtNanos", 0L);

        assertTrue(client.isReadyCached());

        server.verify();
    }

    @Test
    void authenticatesReadinessAndFailsClosedUntilTheProbeCompletes() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE + "/ready"))
                .andExpect(method(GET))
                .andExpect(header("Authorization", "Bearer " + TOKEN))
                .andRespond(withSuccess("{\"ready\":true}", MediaType.APPLICATION_JSON));

        BusinessCardOcrClient client = client(builder);

        assertTrue(awaitReady(client));
        server.verify();
    }

    @Test
    void scanReadinessAwaitsTheBoundedInitialProbeBeforeExternalFallback() throws Exception {
        CountDownLatch probeStarted = new CountDownLatch(1);
        CountDownLatch releaseProbe = new CountDownLatch(1);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE + "/ready"))
                .andRespond(request -> {
                    probeStarted.countDown();
                    try {
                        if (!releaseProbe.await(5, TimeUnit.SECONDS)) {
                            throw new IOException("Readiness probe test timed out");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Readiness probe test was interrupted", exception);
                    }
                    return withSuccess("{\"ready\":true}", MediaType.APPLICATION_JSON)
                            .createResponse(request);
                });
        BusinessCardOcrClient client = client(builder);
        assertTrue(probeStarted.await(5, TimeUnit.SECONDS));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(client::isReadyForScan);
            Future<Boolean> second = executor.submit(client::isReadyForScan);

            assertFalse(first.isDone());
            assertFalse(second.isDone());
            releaseProbe.countDown();
            assertTrue(first.get(5, TimeUnit.SECONDS));
            assertTrue(second.get(5, TimeUnit.SECONDS));
        } finally {
            releaseProbe.countDown();
            executor.shutdownNow();
        }
        server.verify(Duration.ofSeconds(2));
    }

    /**
     * A probe publishes a ready result before it releases its in-flight slot, so a scan-readiness
     * check that read the state just before then can win the empty slot just after. That is the
     * second {@code /ready} request that
     * {@code scanReadinessAwaitsTheBoundedInitialProbeBeforeExternalFallback} occasionally saw on a
     * busy runner (#1834). The window is reproduced directly: the first probe has completed and
     * released the slot, and a caller then asks for a probe. It must reuse the fresh result instead
     * of probing again.
     */
    @Test
    void probeRequestedJustAfterAReadyResultReusesItInsteadOfProbingAgain() throws Exception {
        CountDownLatch releaseProbe = new CountDownLatch(1);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE + "/ready"))
                .andRespond(request -> {
                    try {
                        if (!releaseProbe.await(5, TimeUnit.SECONDS)) {
                            throw new IOException("Readiness probe test timed out");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Readiness probe test was interrupted", exception);
                    }
                    return withSuccess("{\"ready\":true}", MediaType.APPLICATION_JSON)
                            .createResponse(request);
                });
        BusinessCardOcrClient client = client(builder);
        CompletableFuture<?> initialProbe = inFlightProbe(client);
        releaseProbe.countDown();
        assertEquals(Boolean.TRUE, initialProbe.get(5, TimeUnit.SECONDS));

        CompletableFuture<?> requested = ReflectionTestUtils.invokeMethod(client, "refreshReadiness");

        assertNotNull(requested);
        assertEquals(Boolean.TRUE, requested.get(5, TimeUnit.SECONDS));
        server.verify(Duration.ofSeconds(2));
    }

    @Test
    void oneScanReadinessDecisionUsesTheRemainingWindowToObserveRecovery() throws Exception {
        CountDownLatch firstProbeStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstProbe = new CountDownLatch(1);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE + "/ready"))
                .andRespond(request -> {
                    firstProbeStarted.countDown();
                    try {
                        if (!releaseFirstProbe.await(5, TimeUnit.SECONDS)) {
                            throw new IOException("Readiness probe test timed out");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Readiness probe test was interrupted", exception);
                    }
                    return withServiceUnavailable().createResponse(request);
                });
        server.expect(requestTo(BASE + "/ready"))
                .andRespond(withSuccess("{\"ready\":true}", MediaType.APPLICATION_JSON));
        BusinessCardOcrClient client = client(builder, Duration.ofMinutes(1));
        assertTrue(firstProbeStarted.await(5, TimeUnit.SECONDS));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> resolved = executor.submit(client::isReadyForScan);

            assertFalse(resolved.isDone());
            releaseFirstProbe.countDown();
            assertTrue(resolved.get(5, TimeUnit.SECONDS));
        } finally {
            releaseFirstProbe.countDown();
            executor.shutdownNow();
        }
        server.verify(Duration.ofSeconds(2));
    }

    @Test
    void scanReadinessStopsWaitingAtTheConfiguredLocalFirstDeadline() throws Exception {
        CountDownLatch probeStarted = new CountDownLatch(1);
        CountDownLatch releaseProbe = new CountDownLatch(1);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE + "/ready"))
                .andRespond(request -> {
                    probeStarted.countDown();
                    try {
                        releaseProbe.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Readiness probe test was interrupted", exception);
                    }
                    return withServiceUnavailable().createResponse(request);
                });
        BusinessCardProperties properties = properties(Duration.ofMinutes(1));
        properties.setLocalFirstWait(Duration.ofMillis(50));
        BusinessCardOcrClient client = new BusinessCardOcrClient(
                builder.build(), new ObjectMapper(), properties, BASE);
        assertTrue(probeStarted.await(5, TimeUnit.SECONDS));
        long started = System.nanoTime();
        try {
            assertFalse(client.isReadyForScan());
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 1000);
        } finally {
            releaseProbe.countDown();
        }
        server.verify(Duration.ofSeconds(2));
    }

    @Test
    void workerFailureTripsReadinessBeforeAnotherScanIsAdmitted() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE + "/ready"))
                .andExpect(method(GET))
                .andExpect(header("Authorization", "Bearer " + TOKEN))
                .andRespond(withSuccess("{\"ready\":true}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/v1/ocr"))
                .andExpect(method(POST))
                .andExpect(header("Authorization", "Bearer " + TOKEN))
                .andRespond(withServiceUnavailable());
        server.expect(requestTo(BASE + "/ready"))
                .andExpect(method(GET))
                .andExpect(header("Authorization", "Bearer " + TOKEN))
                .andRespond(withServiceUnavailable());
        BusinessCardOcrClient client = client(builder);
        assertTrue(awaitReady(client));

        assertThrows(ServiceUnavailableException.class,
                () -> client.recognize(new ValidatedBusinessCardImage(
                        new byte[] {1, 2, 3}, "image/jpeg", "jpg", 1, 1)));
        assertThrows(ServiceUnavailableException.class,
                () -> client.recognize(new ValidatedBusinessCardImage(
                        new byte[] {1, 2, 3}, "image/jpeg", "jpg", 1, 1)));
        server.verify(Duration.ofSeconds(2));
    }

    /**
     * With a 1 ms readiness cache every {@code isReady()} call after the cache expires starts a new
     * probe, so the test's own polling after the recovery probe, and its final {@code isReady()}, send
     * further {@code /ready} requests. Every one is answered the same way instead of exactly once:
     * an exact count raced that polling and failed the final verify on a busy runner (#1834).
     */
    @Test
    void staleProbeCannotReopenReadinessAfterWorkerFailure() throws Exception {
        CountDownLatch staleProbeStarted = new CountDownLatch(1);
        CountDownLatch releaseStaleProbe = new CountDownLatch(1);
        CountDownLatch staleProbeCompleted = new CountDownLatch(1);
        CountDownLatch recoveryProbeStarted = new CountDownLatch(1);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE + "/ready"))
                .andRespond(withSuccess("{\"ready\":true}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/ready"))
                .andRespond(request -> {
                    staleProbeStarted.countDown();
                    try {
                        if (!releaseStaleProbe.await(5, TimeUnit.SECONDS)) {
                            throw new IOException("Readiness probe test timed out");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Readiness probe test was interrupted", exception);
                    }
                    staleProbeCompleted.countDown();
                    return withSuccess("{\"ready\":true}", MediaType.APPLICATION_JSON)
                            .createResponse(request);
                });
        server.expect(requestTo(BASE + "/v1/ocr"))
                .andRespond(withServiceUnavailable());
        server.expect(manyTimes(), requestTo(BASE + "/ready"))
                .andRespond(request -> {
                    recoveryProbeStarted.countDown();
                    return withServiceUnavailable().createResponse(request);
                });
        BusinessCardOcrClient client = client(builder, Duration.ofMillis(1));
        assertTrue(awaitReady(client));
        Thread.sleep(5);

        assertTrue(client.isReady());
        assertTrue(staleProbeStarted.await(5, TimeUnit.SECONDS));
        assertThrows(ServiceUnavailableException.class,
                () -> client.recognize(new ValidatedBusinessCardImage(
                        new byte[] {1, 2, 3}, "image/jpeg", "jpg", 1, 1)));
        releaseStaleProbe.countDown();
        assertTrue(staleProbeCompleted.await(5, TimeUnit.SECONDS));

        assertThrows(ServiceUnavailableException.class,
                () -> client.recognize(new ValidatedBusinessCardImage(
                        new byte[] {1, 2, 3}, "image/jpeg", "jpg", 1, 1)));
        long recoveryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (recoveryProbeStarted.getCount() != 0 && System.nanoTime() < recoveryDeadline) {
            assertFalse(client.isReady());
            Thread.sleep(5);
        }
        assertTrue(recoveryProbeStarted.await(5, TimeUnit.SECONDS));
        assertFalse(client.isReady());
        server.verify(Duration.ofSeconds(2));
    }

    /**
     * A worker failure must not interleave with a probe's publication. The generation check and the
     * writes share one monitor with {@code markUnavailable}, so an invalidation waits for a
     * publication in progress and then overwrites it, rather than landing between its check and its
     * writes and being overwritten by a stale ready result (#1987). Once it has run, a publication
     * from the earlier generation is refused.
     */
    @Test
    void anInvalidationWaitsForAPublicationInProgressAndThenWins() throws Exception {
        BusinessCardOcrClient client = new BusinessCardOcrClient(RestClient.builder().build(),
                new ObjectMapper(), properties(Duration.ofMinutes(1)), BASE, false);
        Object monitor = Objects.requireNonNull(ReflectionTestUtils.getField(client, "readinessPublication"));
        AtomicLong generation = assertInstanceOf(AtomicLong.class,
                ReflectionTestUtils.getField(client, "readinessGeneration"));
        long started = generation.get();
        long expiresAt = System.nanoTime() + Duration.ofMinutes(1).toNanos();
        CountDownLatch invalidated = new CountDownLatch(1);
        Thread invalidation;
        synchronized (monitor) {
            assertEquals(Boolean.TRUE,
                    ReflectionTestUtils.invokeMethod(client, "publishReadiness", started, true, expiresAt));
            invalidation = Thread.ofPlatform().start(() -> {
                ReflectionTestUtils.invokeMethod(client, "markUnavailable");
                invalidated.countDown();
            });
            awaitBlocked(invalidation);
            assertTrue(client.isReadyCached());
        }

        assertTrue(invalidated.await(5, TimeUnit.SECONDS));
        invalidation.join(5_000);
        assertFalse(client.isReadyCached());
        assertEquals(Boolean.FALSE,
                ReflectionTestUtils.invokeMethod(client, "publishReadiness", started, true, expiresAt));
        assertFalse(client.isReadyCached());
    }

    /**
     * The reciprocal case: a probe's publication takes the same monitor, so one that arrives while an
     * invalidation holds it waits, then sees the new generation and publishes nothing (#1987).
     */
    @Test
    void aPublicationWaitsForAnInvalidationInProgressAndIsThenRefused() throws Exception {
        BusinessCardOcrClient client = new BusinessCardOcrClient(RestClient.builder().build(),
                new ObjectMapper(), properties(Duration.ofMinutes(1)), BASE, false);
        Object monitor = Objects.requireNonNull(ReflectionTestUtils.getField(client, "readinessPublication"));
        AtomicLong generation = assertInstanceOf(AtomicLong.class,
                ReflectionTestUtils.getField(client, "readinessGeneration"));
        long started = generation.get();
        long expiresAt = System.nanoTime() + Duration.ofMinutes(1).toNanos();
        AtomicReference<Object> published = new AtomicReference<>();
        Thread publication;
        synchronized (monitor) {
            publication = Thread.ofPlatform().start(() -> published.set(
                    ReflectionTestUtils.invokeMethod(client, "publishReadiness", started, true, expiresAt)));
            awaitBlocked(publication);
            ReflectionTestUtils.invokeMethod(client, "markUnavailable");
        }

        publication.join(5_000);
        assertFalse(publication.isAlive());
        assertEquals(Boolean.FALSE, published.get());
        assertFalse(client.isReadyCached());
    }

    private static void awaitBlocked(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.getState() != Thread.State.BLOCKED) {
            if (System.nanoTime() > deadline || !thread.isAlive()) {
                throw new AssertionError("The thread never waited for the readiness monitor");
            }
            Thread.sleep(5);
        }
    }

    private static CompletableFuture<?> inFlightProbe(BusinessCardOcrClient client) {
        AtomicReference<?> slot = assertInstanceOf(AtomicReference.class,
                ReflectionTestUtils.getField(client, "readinessProbe"));
        return assertInstanceOf(CompletableFuture.class, slot.get());
    }

    private static BusinessCardOcrClient client(RestClient.Builder builder) {
        return client(builder, Duration.ofMinutes(1));
    }

    private static BusinessCardOcrClient client(RestClient.Builder builder, Duration readinessCache) {
        BusinessCardProperties properties = properties(readinessCache);
        return new BusinessCardOcrClient(
                builder.build(), new ObjectMapper(), properties, BASE);
    }

    private static BusinessCardProperties properties(Duration readinessCache) {
        BusinessCardProperties properties = new BusinessCardProperties();
        properties.setEnabled(true);
        properties.setOcrBaseUrl(BASE);
        properties.setOcrServiceToken(TOKEN);
        properties.setReadinessCache(readinessCache);
        return properties;
    }

    private static boolean awaitReady(BusinessCardOcrClient client) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (client.isReady()) {
                return true;
            }
            Thread.sleep(5);
        }
        return false;
    }
}
