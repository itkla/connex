package ooo.klae.connex.backend.delivery.provider.list;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;

import ooo.klae.connex.backend.ai.egress.AiEgressGuard;
import ooo.klae.connex.backend.delivery.AudienceMember;
import ooo.klae.connex.backend.delivery.AudiencePush;
import ooo.klae.connex.backend.delivery.AudiencePushResult;
import ooo.klae.connex.backend.delivery.DeliveryChannel;
import ooo.klae.connex.backend.delivery.DeliveryCredentials;
import ooo.klae.connex.backend.delivery.DeliveryProperties;
import ooo.klae.connex.backend.delivery.ResolvedDeliveryProvider;

import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for the HTTP list connector over stubbed transports and a loopback deadline server.
 */
class HttpListConnectorTest {

    private static final String ENDPOINT = "https://lists.example.com/v1/lists/add";
    private static final String API_KEY = "list_api_key_123456";
    private final ObjectMapper objectMapper = new ObjectMapper();

    private ResolvedDeliveryProvider connectorTarget() {
        return new ResolvedDeliveryProvider(
                HttpListConnector.PROVIDER_ID, DeliveryChannel.EMAIL, 7, ENDPOINT, null, null,
                DeliveryCredentials.of(Map.of("apiKey", API_KEY)));
    }

    private AudiencePush push() {
        return new AudiencePush("list-9", List.of(
                new AudienceMember("a@dest.test", "Ada", "Lovelace"),
                new AudienceMember("b@dest.test", null, null)), "campaign-export-71-attempt-1");
    }

    @Test
    void pushAudience_mapsSuccessResponseToReceiptCounts() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpListConnector connector = new HttpListConnector(builder.build(), 1024, objectMapper);
        server.expect(requestTo(ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + API_KEY))
                .andExpect(header("Idempotency-Key", "campaign-export-71-attempt-1"))
                .andExpect(jsonPath("$.listId").value("list-9"))
                .andExpect(jsonPath("$.members[0].email").value("a@dest.test"))
                .andExpect(jsonPath("$.members[0].firstName").value("Ada"))
                .andRespond(withSuccess("{\"added\":2,\"failed\":0}", MediaType.APPLICATION_JSON));

        try (MockedStatic<AiEgressGuard> guard = mockStatic(AiEgressGuard.class)) {
            AudiencePushResult result = connector.pushAudience(connectorTarget(), push());

            assertEquals(2, result.pushedCount());
            assertEquals(0, result.failedCount());
            assertEquals(AudiencePushResult.Outcome.CONFIRMED, result.outcome());
            guard.verify(() -> AiEgressGuard.resolveFetchableHost("lists.example.com", false));
            server.verify();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 400, 401, 403, 404, 422 })
    void pushAudience_classifiesGenericPostSend4xxResponsesAsAmbiguous(int statusCode) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpListConnector connector = new HttpListConnector(builder.build(), 1024, objectMapper);
        server.expect(requestTo(ENDPOINT))
                .andRespond(withStatus(HttpStatus.valueOf(statusCode))
                        .contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"nope\"}"));

        try (MockedStatic<AiEgressGuard> ignored = mockStatic(AiEgressGuard.class)) {
            AudiencePushResult result = connector.pushAudience(connectorTarget(), push());

            assertEquals(0, result.pushedCount());
            assertEquals(2, result.failedCount());
            assertEquals(AudiencePushResult.Outcome.AMBIGUOUS, result.outcome());
            server.verify();
        }
    }

    @Test
    void pushAudience_classifiesA4xxWithReportedAcceptanceAsAmbiguous() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpListConnector connector = new HttpListConnector(builder.build(), 1024, objectMapper);
        server.expect(requestTo(ENDPOINT))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"added\":1,\"failed\":1}"));

        try (MockedStatic<AiEgressGuard> ignored = mockStatic(AiEgressGuard.class)) {
            AudiencePushResult result = connector.pushAudience(connectorTarget(), push());

            assertEquals(AudiencePushResult.Outcome.AMBIGUOUS, result.outcome());
            server.verify();
        }
    }

    @Test
    void pushAudience_classifiesAPostSideEffect500AsAmbiguous() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpListConnector connector = new HttpListConnector(builder.build(), 1024, objectMapper);
        server.expect(requestTo(ENDPOINT))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"added\":2,\"failed\":0}"));

        try (MockedStatic<AiEgressGuard> ignored = mockStatic(AiEgressGuard.class)) {
            AudiencePushResult result = connector.pushAudience(connectorTarget(), push());

            assertEquals(AudiencePushResult.Outcome.AMBIGUOUS, result.outcome());
            server.verify();
        }
    }

    @Test
    void pushAudience_classifiesAProxy502AsAmbiguous() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpListConnector connector = new HttpListConnector(builder.build(), 1024, objectMapper);
        server.expect(requestTo(ENDPOINT))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY)
                        .contentType(MediaType.TEXT_PLAIN)
                        .body("upstream response unavailable"));

        try (MockedStatic<AiEgressGuard> ignored = mockStatic(AiEgressGuard.class)) {
            AudiencePushResult result = connector.pushAudience(connectorTarget(), push());

            assertEquals(AudiencePushResult.Outcome.AMBIGUOUS, result.outcome());
            server.verify();
        }
    }

    @Test
    void pushAudience_classifiesATimeoutAfterSendAsAmbiguous() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpListConnector connector = new HttpListConnector(builder.build(), 1024, objectMapper);
        server.expect(requestTo(ENDPOINT)).andRespond(request -> {
            throw new ResourceAccessException(
                    "Response timed out", new SocketTimeoutException("Read timed out"));
        });

        try (MockedStatic<AiEgressGuard> ignored = mockStatic(AiEgressGuard.class)) {
            AudiencePushResult result = connector.pushAudience(connectorTarget(), push());

            assertEquals(AudiencePushResult.Outcome.AMBIGUOUS, result.outcome());
            server.verify();
        }
    }

    @Test
    void pushAudience_hardDeadlineAbortsASlowDripWhileTheExportLeaseIsValid() throws Exception {
        Duration deadline = Duration.ofMillis(500);
        long dripMillis = 25;
        byte[] response = ("{\"added\":2,\"failed\":0,\"padding\":\""
                + "x".repeat(32) + "\"}").getBytes(StandardCharsets.UTF_8);
        assertTrue(Duration.ofMillis(dripMillis * response.length)
                .compareTo(deadline.plusMillis(200)) >= 0);
        CountDownLatch responseStarted = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/slow", exchange -> {
            responseStarted.countDown();
            try {
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream output = exchange.getResponseBody()) {
                    for (byte value : response) {
                        output.write(value);
                        output.flush();
                        Thread.sleep(dripMillis);
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
        DeliveryProperties properties = new DeliveryProperties();
        properties.setEspConnectTimeoutMs(1_000);
        properties.setEspRequestTimeoutMs(1_000);
        properties.setAudienceExportProviderDeadlineMs(2_000);
        HttpListConnector connector = new HttpListConnector(
                properties, objectMapper, host -> InetAddress.getLoopbackAddress());
        String endpoint = "http://list-provider.example.test:"
                + server.getAddress().getPort() + "/slow";
        long started = System.nanoTime();
        try {
            AudiencePushResult result = connector.pushAudience(
                    connectorTarget(endpoint), pushWithDeadline(deadline));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

            assertTrue(responseStarted.await(10, TimeUnit.SECONDS));
            assertEquals(AudiencePushResult.Outcome.AMBIGUOUS, result.outcome());
            assertEquals("Connector audience push exceeded its hard deadline", result.detail());
            assertTrue(elapsed.compareTo(deadline.minusMillis(50)) >= 0);
            assertTrue(elapsed.compareTo(properties.audienceExportLeaseDuration()) < 0);
        } finally {
            connector.shutdown();
            server.stop(0);
        }
    }

    @Test
    void pushAudience_refusesEgressWhenSerializationConsumesTheLeaseAnchoredBudget() {
        DeliveryProperties properties = new DeliveryProperties();
        AtomicLong nanoTime = new AtomicLong(1_000_000L);
        AtomicBoolean resolutionAttempted = new AtomicBoolean();
        HttpListConnector.PayloadSerializer slowSerializer = payload -> {
            nanoTime.addAndGet(Duration.ofMillis(11).toNanos());
            return objectMapper.writeValueAsBytes(payload);
        };
        HttpListConnector connector = new HttpListConnector(
                properties,
                objectMapper,
                host -> {
                    resolutionAttempted.set(true);
                    return InetAddress.getLoopbackAddress();
                },
                false,
                slowSerializer,
                nanoTime::get);
        List<AudienceMember> members = IntStream.range(0, 10_000)
                .mapToObj(index -> new AudienceMember(
                        "member-" + index + "@example.test", "First", "Last"))
                .toList();
        AudiencePush push = new AudiencePush(
                "list-9", members, "campaign-export-71-attempt-1",
                nanoTime.get() + Duration.ofMillis(10).toNanos());

        try {
            AudiencePushResult result = connector.pushAudience(connectorTarget(), push);

            assertEquals(AudiencePushResult.Outcome.DEFINITE_NO_SIDE_EFFECT, result.outcome());
            assertEquals("Connector audience push exceeded its hard deadline", result.detail());
            assertEquals("provider_deadline_exceeded", result.failureReason());
            assertFalse(resolutionAttempted.get());
        } finally {
            connector.shutdown();
        }
    }

    @Test
    void pushAudience_preservesAbsoluteDeadlineAcrossServiceToConnectorPause() throws Exception {
        DeliveryProperties properties = new DeliveryProperties();
        AtomicLong nanoTime = new AtomicLong(1_000_000L);
        AtomicBoolean resolutionAttempted = new AtomicBoolean();
        AtomicBoolean providerContacted = new AtomicBoolean();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/audience", exchange -> {
            providerContacted.set(true);
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        HttpListConnector connector = new HttpListConnector(
                properties,
                objectMapper,
                host -> {
                    resolutionAttempted.set(true);
                    return InetAddress.getLoopbackAddress();
                },
                false,
                objectMapper::writeValueAsBytes,
                nanoTime::get);
        long serviceDeadlineNanos = nanoTime.get() + Duration.ofMillis(10).toNanos();
        AudiencePush expiredPush = new AudiencePush(
                "list-9", push().members(), "campaign-export-71-attempt-1", serviceDeadlineNanos);
        nanoTime.addAndGet(Duration.ofMillis(11).toNanos());
        String endpoint = "http://list-provider.example.test:"
                + server.getAddress().getPort() + "/audience";

        try {
            AudiencePushResult result = connector.pushAudience(connectorTarget(endpoint), expiredPush);

            assertEquals(AudiencePushResult.Outcome.DEFINITE_NO_SIDE_EFFECT, result.outcome());
            assertEquals("Connector audience push exceeded its hard deadline", result.detail());
            assertEquals("provider_deadline_exceeded", result.failureReason());
            assertFalse(resolutionAttempted.get());
            assertFalse(providerContacted.get());
        } finally {
            connector.shutdown();
            server.stop(0);
        }
    }

    @Test
    void pushAudience_boundsStuckDnsTasksUntilTheyReturnAndThenRecovers() throws Exception {
        CountDownLatch stuckResolversStarted = new CountDownLatch(2);
        CountDownLatch stuckResolversInterrupted = new CountDownLatch(2);
        CountDownLatch stuckResolversReturned = new CountDownLatch(2);
        CountDownLatch releaseStuckResolvers = new CountDownLatch(1);
        AtomicInteger resolutions = new AtomicInteger();
        DeliveryProperties properties = new DeliveryProperties();
        properties.setEspConnectTimeoutMs(2_000);
        properties.setEspRequestTimeoutMs(2_000);
        properties.setAudienceExportProviderDeadlineMs(5_000);
        HttpListConnector connector = new HttpListConnector(properties, objectMapper, host -> {
            int resolution = resolutions.incrementAndGet();
            if (resolution <= 2) {
                stuckResolversStarted.countDown();
                while (releaseStuckResolvers.getCount() > 0) {
                    try {
                        releaseStuckResolvers.await();
                    } catch (InterruptedException exception) {
                        stuckResolversInterrupted.countDown();
                    }
                }
                stuckResolversReturned.countDown();
            }
            return InetAddress.getLoopbackAddress();
        });
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/audience", exchange -> {
            byte[] response = "{\"added\":2,\"failed\":0}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(response);
            } finally {
                exchange.close();
            }
        });
        server.start();
        String endpoint = "http://list-provider.example.test:"
                + server.getAddress().getPort() + "/audience";
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<AudiencePushResult> first = callers.submit(
                    () -> connector.pushAudience(
                            connectorTarget(endpoint), pushWithDeadline(Duration.ofSeconds(2))));
            Future<AudiencePushResult> second = callers.submit(
                    () -> connector.pushAudience(
                            connectorTarget(endpoint), pushWithDeadline(Duration.ofSeconds(2))));

            assertTrue(stuckResolversStarted.await(10, TimeUnit.SECONDS));
            assertEquals(AudiencePushResult.Outcome.DEFINITE_NO_SIDE_EFFECT,
                    first.get(10, TimeUnit.SECONDS).outcome());
            assertEquals(AudiencePushResult.Outcome.DEFINITE_NO_SIDE_EFFECT,
                    second.get(10, TimeUnit.SECONDS).outcome());
            assertTrue(stuckResolversInterrupted.await(10, TimeUnit.SECONDS));

            AudiencePushResult saturated = connector.pushAudience(
                    connectorTarget(endpoint), pushWithDeadline(Duration.ofSeconds(10)));

            assertEquals(AudiencePushResult.Outcome.DEFINITE_NO_SIDE_EFFECT, saturated.outcome());
            assertEquals("Connector resolver saturated", saturated.detail());
            assertEquals("resolver_saturated", saturated.failureReason());
            assertEquals(2, resolutions.get());

            releaseStuckResolvers.countDown();
            assertTrue(stuckResolversReturned.await(10, TimeUnit.SECONDS));
            AudiencePushResult recovered = connector.pushAudience(
                    connectorTarget(endpoint), pushWithDeadline(Duration.ofSeconds(10)));

            assertEquals(AudiencePushResult.Outcome.CONFIRMED, recovered.outcome());
            assertEquals(3, resolutions.get());
        } finally {
            releaseStuckResolvers.countDown();
            callers.shutdownNow();
            connector.shutdown();
            server.stop(0);
        }
    }

    @Test
    void pushAudience_classifiesAConnectionRefusalBeforeSendAsDefiniteNoSideEffect() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpListConnector connector = new HttpListConnector(builder.build(), 1024, objectMapper);
        server.expect(requestTo(ENDPOINT)).andRespond(request -> {
            throw new ResourceAccessException(
                    "Connection refused", new ConnectException("Connection refused"));
        });

        try (MockedStatic<AiEgressGuard> ignored = mockStatic(AiEgressGuard.class)) {
            AudiencePushResult result = connector.pushAudience(connectorTarget(), push());

            assertEquals(AudiencePushResult.Outcome.DEFINITE_NO_SIDE_EFFECT, result.outcome());
            server.verify();
        }
    }

    @Test
    void pushAudience_reportsAmbiguousOutcomeWhenA2xxBodyIsUnparseable() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpListConnector connector = new HttpListConnector(builder.build(), 1024, objectMapper);
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess("not json", MediaType.APPLICATION_JSON));

        try (MockedStatic<AiEgressGuard> ignored = mockStatic(AiEgressGuard.class)) {
            AudiencePushResult result = connector.pushAudience(connectorTarget(), push());

            assertEquals(0, result.pushedCount());
            assertEquals(2, result.failedCount());
            assertEquals(AudiencePushResult.Outcome.AMBIGUOUS, result.outcome());
            server.verify();
        }
    }

    @Test
    void pushAudience_classifiesA2xxResponseWithMissingCountersAsAmbiguous() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpListConnector connector = new HttpListConnector(builder.build(), 1024, objectMapper);
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess("{\"added\":2}", MediaType.APPLICATION_JSON));

        try (MockedStatic<AiEgressGuard> ignored = mockStatic(AiEgressGuard.class)) {
            AudiencePushResult result = connector.pushAudience(connectorTarget(), push());

            assertEquals(AudiencePushResult.Outcome.AMBIGUOUS, result.outcome());
            server.verify();
        }
    }

    @Test
    void pushAudience_classifiesA2xxResponseWithInconsistentCountersAsAmbiguous() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpListConnector connector = new HttpListConnector(builder.build(), 1024, objectMapper);
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess("{\"added\":2,\"failed\":1}", MediaType.APPLICATION_JSON));

        try (MockedStatic<AiEgressGuard> ignored = mockStatic(AiEgressGuard.class)) {
            AudiencePushResult result = connector.pushAudience(connectorTarget(), push());

            assertEquals(AudiencePushResult.Outcome.AMBIGUOUS, result.outcome());
            server.verify();
        }
    }

    @Test
    void pushAudience_classifiesA2xxResponseWithNegativeCountersAsAmbiguous() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpListConnector connector = new HttpListConnector(builder.build(), 1024, objectMapper);
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess("{\"added\":-1,\"failed\":3}", MediaType.APPLICATION_JSON));

        try (MockedStatic<AiEgressGuard> ignored = mockStatic(AiEgressGuard.class)) {
            AudiencePushResult result = connector.pushAudience(connectorTarget(), push());

            assertEquals(AudiencePushResult.Outcome.AMBIGUOUS, result.outcome());
            server.verify();
        }
    }

    @Test
    void pushAudience_classifiesA2xxResponseWithOverflowingCountersAsAmbiguous() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpListConnector connector = new HttpListConnector(builder.build(), 1024, objectMapper);
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess(
                        "{\"added\":2147483648,\"failed\":0}", MediaType.APPLICATION_JSON));

        try (MockedStatic<AiEgressGuard> ignored = mockStatic(AiEgressGuard.class)) {
            AudiencePushResult result = connector.pushAudience(connectorTarget(), push());

            assertEquals(AudiencePushResult.Outcome.AMBIGUOUS, result.outcome());
            server.verify();
        }
    }

    @Test
    void pushAudience_returnsAConfirmedZeroAcceptance() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpListConnector connector = new HttpListConnector(builder.build(), 1024, objectMapper);
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess("{\"added\":0,\"failed\":2}", MediaType.APPLICATION_JSON));

        try (MockedStatic<AiEgressGuard> ignored = mockStatic(AiEgressGuard.class)) {
            AudiencePushResult result = connector.pushAudience(connectorTarget(), push());

            assertEquals(0, result.pushedCount());
            assertEquals(2, result.failedCount());
            assertEquals(AudiencePushResult.Outcome.CONFIRMED, result.outcome());
            server.verify();
        }
    }

    @Test
    void pushAudience_classifiesAnUnexpectedTransportExceptionAsAmbiguous() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpListConnector connector = new HttpListConnector(builder.build(), 1024, objectMapper);
        server.expect(requestTo(ENDPOINT)).andRespond(request -> {
            throw new IllegalStateException("unexpected transport failure");
        });

        try (MockedStatic<AiEgressGuard> ignored = mockStatic(AiEgressGuard.class)) {
            AudiencePushResult result = connector.pushAudience(connectorTarget(), push());

            assertEquals(AudiencePushResult.Outcome.AMBIGUOUS, result.outcome());
            server.verify();
        }
    }

    @Test
    void pushAudience_classifiesAnOtherHttpStatusAsAmbiguous() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpListConnector connector = new HttpListConnector(builder.build(), 1024, objectMapper);
        server.expect(requestTo(ENDPOINT))
                .andRespond(withStatus(HttpStatus.FOUND)
                        .contentType(MediaType.TEXT_PLAIN).body("redirect refused"));

        try (MockedStatic<AiEgressGuard> ignored = mockStatic(AiEgressGuard.class)) {
            AudiencePushResult result = connector.pushAudience(connectorTarget(), push());

            assertEquals(AudiencePushResult.Outcome.AMBIGUOUS, result.outcome());
            server.verify();
        }
    }

    @Test
    void pushAudience_reportsPartialFailuresFromTheResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpListConnector connector = new HttpListConnector(builder.build(), 1024, objectMapper);
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess("{\"added\":1,\"failed\":1}", MediaType.APPLICATION_JSON));

        try (MockedStatic<AiEgressGuard> ignored = mockStatic(AiEgressGuard.class)) {
            AudiencePushResult result = connector.pushAudience(connectorTarget(), push());

            assertEquals(1, result.pushedCount());
            assertEquals(1, result.failedCount());
            server.verify();
        }
    }

    private ResolvedDeliveryProvider connectorTarget(String endpoint) {
        return new ResolvedDeliveryProvider(
                HttpListConnector.PROVIDER_ID, DeliveryChannel.EMAIL, 7, endpoint, null, null,
                DeliveryCredentials.of(Map.of("apiKey", API_KEY)));
    }

    private AudiencePush pushWithDeadline(Duration budget) {
        return new AudiencePush(
                push().externalListId(), push().members(), push().idempotencyKey(),
                System.nanoTime() + budget.toNanos());
    }
}
