package ooo.klae.connex.backend.delivery.provider.esp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import ooo.klae.connex.backend.ai.egress.AiEgressGuard;
import ooo.klae.connex.backend.delivery.DeliveryChannel;
import ooo.klae.connex.backend.delivery.DeliveryCredentials;
import ooo.klae.connex.backend.delivery.DeliveryEvent;
import ooo.klae.connex.backend.delivery.DeliveryEventType;
import ooo.klae.connex.backend.delivery.DeliveryProperties;
import ooo.klae.connex.backend.delivery.DeliveryProviderException;
import ooo.klae.connex.backend.delivery.DeliveryRequest;
import ooo.klae.connex.backend.delivery.DispatchReceipt;
import ooo.klae.connex.backend.delivery.DispatchStatus;
import ooo.klae.connex.backend.delivery.RenderedMessage;
import ooo.klae.connex.backend.delivery.ResolvedDeliveryProvider;
import ooo.klae.connex.backend.delivery.provider.http.DeadlineBoundHttpTransport;

import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for the ESP adapter: the send request/response mapping over a stubbed HTTP layer, the
 * webhook signature check, and the webhook event translation. No test touches the network.
 */
class HttpEspDeliveryProviderTest {

    private static final String ENDPOINT = "https://esp.example.com/v1/send";
    private static final String API_KEY = "esp_api_key_123456";
    private static final String WEBHOOK_SECRET = "whsec_abcdef0123456789";
    private final ObjectMapper objectMapper = new ObjectMapper();

    private ResolvedDeliveryProvider espTarget(String credentialKey, String credentialValue) {
        return espTarget(ENDPOINT, credentialKey, credentialValue);
    }

    private ResolvedDeliveryProvider espTarget(
            String endpoint, String credentialKey, String credentialValue) {
        return new ResolvedDeliveryProvider(
                HttpEspDeliveryProvider.PROVIDER_ID, DeliveryChannel.EMAIL, 7, endpoint,
                "no-reply@sender.test", "Sender", DeliveryCredentials.of(Map.of(credentialKey, credentialValue)));
    }

    private DeliveryRequest request() {
        return new DeliveryRequest(DeliveryChannel.EMAIL, "recipient@dest.test",
                new RenderedMessage("Hi", "<p>Hi</p>", "Hi"), 42, "send:1:2");
    }

    @Test
    void genericAdapterDoesNotPromiseEndpointIdempotency() {
        HttpEspDeliveryProvider provider =
                new HttpEspDeliveryProvider(RestClient.create(), 1024, objectMapper);
        try {
            assertFalse(provider.capabilities().idempotentSubmission());
        } finally {
            provider.shutdown();
        }
    }

    @Test
    void dispatch_mapsSuccessResponseToSentReceiptWithProviderMessageId() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpEspDeliveryProvider provider = new HttpEspDeliveryProvider(builder.build(), 1024, objectMapper);
        server.expect(requestTo(ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + API_KEY))
                .andExpect(header("Idempotency-Key", "send:1:2"))
                .andRespond(withSuccess("{\"messageId\":\"esp-777\"}", MediaType.APPLICATION_JSON));

        try (MockedStatic<AiEgressGuard> guard = mockStatic(AiEgressGuard.class)) {
            DispatchReceipt receipt = provider.dispatch(espTarget("apiKey", API_KEY), request());

            assertEquals(DispatchStatus.SENT, receipt.status());
            assertEquals("esp-777", receipt.providerMessageId());
            guard.verify(() -> AiEgressGuard.resolveFetchableHost("esp.example.com", false));
            server.verify();
        }
    }

    @Test
    void dispatch_hardDeadlineAbortsASlowDripBeforeTheClaimLeaseExpires() throws Exception {
        Duration deadline = Duration.ofMillis(500);
        DeliveryProperties properties = new DeliveryProperties();
        properties.setEspConnectTimeoutMs(100);
        properties.setEspRequestTimeoutMs(100);
        properties.setAudienceExportProviderDeadlineMs(deadline.toMillis());
        long dripMillis = 25;
        byte[] response = ("{\"messageId\":\"esp-777\",\"padding\":\""
                + "x".repeat(32) + "\"}").getBytes(StandardCharsets.UTF_8);
        CountDownLatch responseStarted = new CountDownLatch(1);
        AtomicBoolean idempotencyHeaderSeen = new AtomicBoolean();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/slow", exchange -> {
            idempotencyHeaderSeen.set("send:1:2".equals(
                    exchange.getRequestHeaders().getFirst("Idempotency-Key")));
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
        DeadlineBoundHttpTransport transport = new DeadlineBoundHttpTransport(
                Duration.ofSeconds(1), Duration.ofSeconds(1), 1024,
                host -> InetAddress.getLoopbackAddress(), System::nanoTime);
        HttpEspDeliveryProvider provider =
                new HttpEspDeliveryProvider(transport, 1024, objectMapper, false);
        String endpoint = "http://esp-provider.example.test:"
                + server.getAddress().getPort() + "/slow";
        DeliveryRequest request = new DeliveryRequest(
                DeliveryChannel.EMAIL, "recipient@dest.test",
                new RenderedMessage("Hi", "<p>Hi</p>", "Hi"), 42, "send:1:2",
                System.nanoTime() + deadline.toNanos());
        long started = System.nanoTime();
        try {
            DispatchReceipt receipt = provider.dispatch(
                    espTarget(endpoint, "apiKey", API_KEY), request);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

            assertTrue(responseStarted.await(10, TimeUnit.SECONDS));
            assertTrue(idempotencyHeaderSeen.get());
            assertEquals(DispatchStatus.AMBIGUOUS, receipt.status());
            assertTrue(elapsed.compareTo(deadline.minusMillis(50)) >= 0);
            assertTrue(elapsed.compareTo(deadline.plusSeconds(1)) < 0);
            assertTrue(elapsed.compareTo(properties.providerCallLeaseDuration()) < 0);
        } finally {
            provider.shutdown();
            server.stop(0);
        }
    }

    @Test
    void dispatch_expiredDeadlineRefusesProviderResolutionAndEgress() {
        AtomicBoolean resolutionAttempted = new AtomicBoolean();
        DeadlineBoundHttpTransport transport = new DeadlineBoundHttpTransport(
                Duration.ofSeconds(1), Duration.ofSeconds(1), 1024,
                host -> {
                    resolutionAttempted.set(true);
                    return InetAddress.getLoopbackAddress();
                }, System::nanoTime);
        HttpEspDeliveryProvider provider =
                new HttpEspDeliveryProvider(transport, 1024, objectMapper, false);
        DeliveryRequest expired = new DeliveryRequest(
                DeliveryChannel.EMAIL, "recipient@dest.test",
                new RenderedMessage("Hi", "<p>Hi</p>", "Hi"), 42, "send:1:2",
                System.nanoTime() - 1);
        try {
            DispatchReceipt receipt = provider.dispatch(
                    espTarget("http://esp-provider.example.test/send", "apiKey", API_KEY), expired);

            assertEquals(DispatchStatus.REJECTED, receipt.status());
            assertFalse(resolutionAttempted.get());
        } finally {
            provider.shutdown();
        }
    }

    @Test
    void dispatch_mapsNonSuccessResponseToRejectedReceipt() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpEspDeliveryProvider provider = new HttpEspDeliveryProvider(builder.build(), 1024, objectMapper);
        server.expect(requestTo(ENDPOINT))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"nope\"}"));

        try (MockedStatic<AiEgressGuard> ignored = mockStatic(AiEgressGuard.class)) {
            DispatchReceipt receipt = provider.dispatch(espTarget("apiKey", API_KEY), request());

            assertEquals(DispatchStatus.REJECTED, receipt.status());
            assertNull(receipt.providerMessageId());
            server.verify();
        }
    }

    @Test
    void verifySignature_acceptsAMatchingSignatureAndRejectsATamperedOne() {
        HttpEspDeliveryProvider provider = new HttpEspDeliveryProvider((RestClient) null, 1024, objectMapper);
        byte[] body = "{\"events\":[]}".getBytes(StandardCharsets.UTF_8);
        String signature = hmacHex(WEBHOOK_SECRET, body);
        ResolvedDeliveryProvider target = espTarget("webhookSecret", WEBHOOK_SECRET);

        provider.verifySignature(target, body, Map.of(HttpEspDeliveryProvider.SIGNATURE_HEADER, signature));

        assertThrows(DeliveryProviderException.class, () -> provider.verifySignature(
                target, "{\"events\":[1]}".getBytes(StandardCharsets.UTF_8),
                Map.of(HttpEspDeliveryProvider.SIGNATURE_HEADER, signature)));
        assertThrows(DeliveryProviderException.class, () -> provider.verifySignature(target, body, Map.of()));
    }

    @Test
    void translate_mapsProviderVocabularyToNormalizedEvents() {
        HttpEspDeliveryProvider provider = new HttpEspDeliveryProvider((RestClient) null, 1024, objectMapper);
        String payload = "["
                + "{\"messageId\":\"m1\",\"eventId\":\"e1\",\"event\":\"delivered\",\"timestamp\":1700000000},"
                + "{\"messageId\":\"m2\",\"eventId\":\"e2\",\"event\":\"bounce\",\"bounceType\":\"hard\"},"
                + "{\"messageId\":\"m3\",\"eventId\":\"e3\",\"event\":\"bounce\",\"bounceType\":\"soft\"},"
                + "{\"messageId\":\"m4\",\"eventId\":\"e4\",\"event\":\"complaint\"},"
                + "{\"messageId\":\"m5\",\"eventId\":\"e5\",\"event\":\"opened\"},"
                + "{\"event\":\"delivered\"}"
                + "]";

        List<DeliveryEvent> events = provider.translate(payload.getBytes(StandardCharsets.UTF_8));

        assertEquals(4, events.size());
        assertEquals(DeliveryEventType.DELIVERED, events.get(0).type());
        assertEquals("m1", events.get(0).providerMessageId());
        assertEquals("e1", events.get(0).providerEventId());
        assertEquals(DeliveryEventType.BOUNCED, events.get(1).type());
        assertEquals(DeliveryEventType.FAILED, events.get(2).type());
        assertEquals(DeliveryEventType.COMPLAINED, events.get(3).type());
    }

    private static String hmacHex(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
