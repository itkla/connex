package ooo.klae.connex.backend.ai.egress;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.sun.net.httpserver.HttpServer;
import org.apache.hc.core5.http.ContentType;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.ai.provider.AiProviderException;
import ooo.klae.connex.backend.ai.provider.AiProviderIdleTimeoutException;
import ooo.klae.connex.backend.ai.provider.AiProviderStreamObserver;

class FixedAiProviderClientTest {
    private static final String HOST = "fixed-provider.example.test";
    private static final byte[] REQUEST_BODY = "{}".getBytes(StandardCharsets.UTF_8);

    @Test
    void bufferedAndStreamingPreSendFailuresPropagateUnchangedWithoutTransport() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/complete", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        FixedAiProviderClient client = new FixedAiProviderClient(
                properties(5_000, 1024), host -> InetAddress.getLoopbackAddress());
        AtomicInteger closed = new AtomicInteger();
        AiProviderStreamObserver observer = new AiProviderStreamObserver() {
            @Override
            public void onContentDelta(String text) {
                throw new AssertionError("Refused transport emitted content");
            }

            @Override
            public void onTransportClosed() {
                closed.incrementAndGet();
            }
        };
        try {
            URI endpoint = URI.create("http://" + HOST + ":" + server.getAddress().getPort() + "/complete");
            for (RuntimeException refusal : List.of(
                    new ForbiddenException("Permission revoked"),
                    restrictionEpochRefusal())) {
                Runnable beforeSend = () -> { throw refusal; };
                assertSame(refusal, assertThrows(RuntimeException.class, () -> client.post(
                        endpoint, Set.of(HOST), Map.of(), ContentType.APPLICATION_JSON,
                        REQUEST_BODY, AiRequestDeadline.afterMillis(5_000), "Gate test", beforeSend)));
                assertSame(refusal, assertThrows(RuntimeException.class, () -> client.postStream(
                        endpoint, Set.of(HOST), Map.of(), ContentType.APPLICATION_JSON,
                        REQUEST_BODY, AiRequestDeadline.afterMillis(5_000), "Gate test",
                        observer, input -> input.readAllBytes(), beforeSend)));
            }
            assertEquals(0, requests.get());
            assertEquals(2, closed.get());
        } finally {
            client.shutdown();
            server.stop(0);
        }
    }

    private RuntimeException restrictionEpochRefusal() {
        AiRestrictionEpoch epoch = new AiRestrictionEpoch();
        long expected = epoch.current(7);
        epoch.bump(7);
        Supplier<Boolean> provider = () -> Boolean.TRUE;
        Runnable checkpoint = () -> ReflectionTestUtils.invokeMethod(epoch, "invokeAtEgress", 7, provider);
        RuntimeException refusal = assertThrows(RuntimeException.class,
                () -> ReflectionTestUtils.invokeMethod(epoch, "runWithExpectedEgressEpoch", 7, expected, checkpoint));
        assertEquals("EgressRejectedException", refusal.getClass().getSimpleName());
        return refusal;
    }

    @Test
    void springSelectsTheProductionConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(AiProperties.class, AiProperties::new);
            context.register(FixedAiProviderClient.class);
            context.refresh();

            assertNotNull(context.getBean(FixedAiProviderClient.class));
        }
    }

    @Test
    void postPinsTheValidatedAddressAndRefusesRedirects() throws Exception {
        AtomicInteger redirectedRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/complete", exchange -> {
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            } finally {
                exchange.close();
            }
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "/redirected");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/redirected", exchange -> {
            redirectedRequests.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        FixedAiProviderClient client = new FixedAiProviderClient(
                properties(1000, 1024), host -> InetAddress.getLoopbackAddress());
        try {
            URI base = URI.create("http://" + HOST + ":" + server.getAddress().getPort());

            FixedAiProviderClient.Response response = post(client, base.resolve("/complete"), 1000);
            FixedAiProviderClient.Response redirect = post(client, base.resolve("/redirect"), 1000);

            assertEquals(200, response.statusCode());
            assertArrayEquals("{\"ok\":true}".getBytes(StandardCharsets.UTF_8), response.body());
            assertEquals(302, redirect.statusCode());
            assertEquals(0, redirectedRequests.get());
        } finally {
            client.shutdown();
            server.stop(0);
        }
    }

    @Test
    void resolverDeadlinesRetainTheTwoBoundedSlotsUntilNativeCallsReturn() throws Exception {
        CountDownLatch resolverStarted = new CountDownLatch(2);
        CountDownLatch releaseResolver = new CountDownLatch(1);
        FixedAiProviderClient client = new FixedAiProviderClient(properties(100, 1024), host -> {
            resolverStarted.countDown();
            while (releaseResolver.getCount() != 0) {
                try {
                    releaseResolver.await();
                } catch (InterruptedException exception) {
                    Thread.interrupted();
                }
            }
            return InetAddress.getLoopbackAddress();
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        URI endpoint = URI.create("http://" + HOST + ":9/complete");
        try {
            Future<AiProviderException> first = executor.submit(() -> failedPost(client, endpoint, 100));
            Future<AiProviderException> second = executor.submit(() -> failedPost(client, endpoint, 100));
            assertTrue(resolverStarted.await(5, TimeUnit.SECONDS));

            assertEquals("Fixed provider test exceeded its deadline",
                    first.get(5, TimeUnit.SECONDS).getMessage());
            assertEquals("Fixed provider test exceeded its deadline",
                    second.get(5, TimeUnit.SECONDS).getMessage());
            long started = System.nanoTime();
            AiProviderException saturated = assertThrows(AiProviderException.class,
                    () -> post(client, endpoint, 1000));

            assertEquals("Fixed provider test failed during transport", saturated.getMessage());
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 250);
        } finally {
            releaseResolver.countDown();
            executor.shutdownNow();
            client.shutdown();
        }
    }

    @Test
    void expiredDeadlineDoesNotLaunchNativeDnsWork() throws Exception {
        AtomicInteger resolutions = new AtomicInteger();
        FixedAiProviderClient client = new FixedAiProviderClient(properties(100, 1024), host -> {
            resolutions.incrementAndGet();
            return InetAddress.getLoopbackAddress();
        });
        AiRequestDeadline deadline = AiRequestDeadline.afterMillis(1);
        Thread.sleep(10);
        URI endpoint = URI.create("http://" + HOST + ":9/complete");
        try {
            AiProviderException exception = assertThrows(AiProviderException.class,
                    () -> client.post(
                            endpoint,
                            Set.of(HOST),
                            Map.of("Content-Type", ContentType.APPLICATION_JSON.getMimeType()),
                            ContentType.APPLICATION_JSON,
                            REQUEST_BODY,
                            deadline,
                            "Fixed provider test"));

            assertEquals("Fixed provider test exceeded its deadline", exception.getMessage());
            assertEquals(0, resolutions.get());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void hardDeadlineCancelsTwoSimultaneousSlowDripResponses() throws Exception {
        Duration deadline = Duration.ofSeconds(10);
        CountDownLatch callersReady = new CountDownLatch(2);
        CountDownLatch startRequests = new CountDownLatch(1);
        CountDownLatch requestsStarted = new CountDownLatch(2);
        CountDownLatch responsesCancelled = new CountDownLatch(2);
        CountDownLatch stopDripping = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService serverExecutor = Executors.newFixedThreadPool(2);
        server.setExecutor(serverExecutor);
        server.createContext("/slow", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(' ');
                    output.flush();
                    requestsStarted.countDown();
                    while (!stopDripping.await(100, TimeUnit.MILLISECONDS)) {
                        output.write(' ');
                        output.flush();
                    }
                }
            } catch (IOException ignored) {
                responsesCancelled.countDown();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        FixedAiProviderClient client = new FixedAiProviderClient(
                properties(30_000, 1024), host -> InetAddress.getLoopbackAddress());
        ExecutorService callers = Executors.newFixedThreadPool(2);
        URI endpoint = URI.create("http://" + HOST + ":" + server.getAddress().getPort() + "/slow");
        try {
            Callable<AiProviderException> call = () -> {
                callersReady.countDown();
                assertTrue(startRequests.await(15, TimeUnit.SECONDS));
                return failedPost(client, endpoint, deadline.toMillis());
            };
            Future<AiProviderException> first = callers.submit(call);
            Future<AiProviderException> second = callers.submit(call);
            assertTrue(callersReady.await(15, TimeUnit.SECONDS));
            long started = System.nanoTime();
            startRequests.countDown();
            assertTrue(requestsStarted.await(15, TimeUnit.SECONDS));
            assertFalse(first.isDone());
            assertFalse(second.isDone());

            assertEquals("Fixed provider test exceeded its deadline",
                    first.get(15, TimeUnit.SECONDS).getMessage());
            assertEquals("Fixed provider test exceeded its deadline",
                    second.get(15, TimeUnit.SECONDS).getMessage());
            assertTrue(responsesCancelled.await(5, TimeUnit.SECONDS));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
            assertTrue(elapsed.compareTo(deadline) >= 0);
            assertTrue(elapsed.compareTo(deadline.plusSeconds(5)) < 0);
        } finally {
            startRequests.countDown();
            stopDripping.countDown();
            callers.shutdownNow();
            client.shutdown();
            server.stop(0);
            serverExecutor.shutdownNow();
        }
    }

    @Test
    void oversizedResponseIsRejectedWithoutReturningProviderBytes() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/complete", exchange -> {
            byte[] body = "SENSITIVE_RESPONSE".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            } finally {
                exchange.close();
            }
        });
        server.start();
        FixedAiProviderClient client = new FixedAiProviderClient(
                properties(1000, 4), host -> InetAddress.getLoopbackAddress());
        URI endpoint = URI.create(
                "http://" + HOST + ":" + server.getAddress().getPort() + "/complete");
        try {
            AiProviderException exception = assertThrows(AiProviderException.class,
                    () -> post(client, endpoint, 1000));

            assertEquals("Fixed provider test response exceeded the configured size limit",
                    exception.getMessage());
            assertFalse(String.valueOf(exception).contains("SENSITIVE_RESPONSE"));
        } finally {
            client.shutdown();
            server.stop(0);
        }
    }

    @Test
    void streamingResponseUsesDistinctIdleTimeout() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/idle", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream output = exchange.getResponseBody()) {
                output.flush();
                Thread.sleep(500);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        AiProperties properties = properties(1000, 1024);
        properties.setStreamIdleTimeout(Duration.ofMillis(50));
        FixedAiProviderClient client = new FixedAiProviderClient(
                properties, host -> InetAddress.getLoopbackAddress());
        URI endpoint = URI.create(
                "http://" + HOST + ":" + server.getAddress().getPort() + "/idle");
        try {
            assertThrows(AiProviderIdleTimeoutException.class, () -> client.postStream(
                    endpoint,
                    Set.of(HOST),
                    Map.of("Accept", "text/event-stream"),
                    ContentType.APPLICATION_JSON,
                    REQUEST_BODY,
                    AiRequestDeadline.afterMillis(1000),
                    "Fixed provider test",
                    new AiProviderStreamObserver() {
                        @Override
                        public void onContentDelta(String text) {
                        }
                    },
                    input -> {
                        AiSseEventReader.read(input, ignored -> {
                        });
                        return "done";
                    }));
        } finally {
            client.shutdown();
            server.stop(0);
        }
    }

    private static FixedAiProviderClient.Response post(
            FixedAiProviderClient client,
            URI endpoint,
            long timeoutMillis) {
        return client.post(
                endpoint,
                Set.of(HOST),
                Map.of(
                        "Content-Type", ContentType.APPLICATION_JSON.getMimeType(),
                        "Accept", ContentType.APPLICATION_JSON.getMimeType()),
                ContentType.APPLICATION_JSON,
                REQUEST_BODY,
                AiRequestDeadline.afterMillis(timeoutMillis),
                "Fixed provider test");
    }

    private static AiProviderException failedPost(
            FixedAiProviderClient client,
            URI endpoint,
            long timeoutMillis) {
        return assertThrows(AiProviderException.class, () -> post(client, endpoint, timeoutMillis));
    }

    private static AiProperties properties(long timeoutMillis, int maxResponseBytes) {
        AiProperties properties = new AiProperties();
        properties.setConnectTimeoutMs(timeoutMillis);
        properties.setRequestTimeoutMs(timeoutMillis);
        properties.setMaxResponseBytes(maxResponseBytes);
        return properties;
    }
}
