package ooo.klae.connex.backend.ai.egress;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import org.apache.hc.core5.http.ContentType;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.provider.AiProviderException;

class FixedAiProviderClientTest {
    private static final String HOST = "fixed-provider.example.test";
    private static final byte[] REQUEST_BODY = "{}".getBytes(StandardCharsets.UTF_8);

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
        CountDownLatch requestsStarted = new CountDownLatch(2);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService serverExecutor = Executors.newFixedThreadPool(2);
        server.setExecutor(serverExecutor);
        server.createContext("/slow", exchange -> {
            requestsStarted.countDown();
            try {
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream output = exchange.getResponseBody()) {
                    for (int index = 0; index < 100; index += 1) {
                        output.write(' ');
                        output.flush();
                        Thread.sleep(40);
                    }
                }
            } catch (IOException ignored) {
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        FixedAiProviderClient client = new FixedAiProviderClient(
                properties(500, 1024), host -> InetAddress.getLoopbackAddress());
        ExecutorService callers = Executors.newFixedThreadPool(2);
        URI endpoint = URI.create("http://" + HOST + ":" + server.getAddress().getPort() + "/slow");
        long started = System.nanoTime();
        try {
            Future<AiProviderException> first = callers.submit(() -> failedPost(client, endpoint, 500));
            Future<AiProviderException> second = callers.submit(() -> failedPost(client, endpoint, 500));
            assertTrue(requestsStarted.await(5, TimeUnit.SECONDS));

            assertEquals("Fixed provider test exceeded its deadline",
                    first.get(5, TimeUnit.SECONDS).getMessage());
            assertEquals("Fixed provider test exceeded its deadline",
                    second.get(5, TimeUnit.SECONDS).getMessage());
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 5000);
        } finally {
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
