package ooo.klae.connex.backend.ai.provider.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import ooo.klae.connex.backend.ai.egress.AiEndpointAddressValidator;
import ooo.klae.connex.backend.ai.egress.AiRequestDeadline;
import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.provider.AiCredentials;
import ooo.klae.connex.backend.ai.provider.AiProviderException;

class OpenAiCompatibleClientTest {
    private static final URI PUBLIC_ENDPOINT = URI.create(
            "https://api.example.test:8443/v1/chat/completions");
    private static final URI HTTP_LOOPBACK_ENDPOINT = URI.create(
            "http://localhost:11434/v1/chat/completions");
    private static final URI HTTPS_LOOPBACK_ENDPOINT = URI.create(
            "https://localhost:11434/v1/chat/completions");
    private static final String API_KEY = "openai_compatible_api_key_secret";
    private static final String REQUEST_BODY =
            "{\"model\":\"llama3.3:70b\",\"messages\":[{\"role\":\"user\",\"content\":\"Hello?\"}]}";
    private final AiEndpointAddressValidator endpointAddressValidator = mock(AiEndpointAddressValidator.class);

    @Test
    void complete_sendsBearerHeaderAndBodyAfterEgressVetting() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleClient client = client(builder.build(), 1024);
        server.expect(requestTo(PUBLIC_ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + API_KEY))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json(REQUEST_BODY))
                .andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));

        String response = client.complete(PUBLIC_ENDPOINT, false, credentials(), REQUEST_BODY);

        assertEquals("{\"choices\":[]}", response);
        verify(endpointAddressValidator).resolveFetchable("api.example.test", false);
        server.verify();
    }

    @Test
    void complete_withoutApiKeyOmitsBearerHeader() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleClient client = client(builder.build(), 1024);
        server.expect(requestTo(PUBLIC_ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> assertNull(request.getHeaders().getFirst("Authorization")))
                .andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));

        client.complete(PUBLIC_ENDPOINT, false, emptyCredentials(), REQUEST_BODY);

        verify(endpointAddressValidator).resolveFetchable("api.example.test", false);
        server.verify();
    }

    @Test
    void complete_httpEndpointRequiresInternalAllowanceBeforeSend() {
        RestClient.Builder deniedBuilder = RestClient.builder();
        MockRestServiceServer deniedServer = MockRestServiceServer.bindTo(deniedBuilder).build();
        OpenAiCompatibleClient deniedClient = client(deniedBuilder.build(), 1024);

        AiProviderException denied = assertThrows(AiProviderException.class,
                () -> deniedClient.complete(
                        HTTP_LOOPBACK_ENDPOINT, false, emptyCredentials(), REQUEST_BODY));

        assertEquals("Invalid OpenAI-compatible endpoint", denied.getMessage());
        deniedServer.verify();

        RestClient.Builder allowedBuilder = RestClient.builder();
        MockRestServiceServer allowedServer = MockRestServiceServer.bindTo(allowedBuilder).build();
        OpenAiCompatibleClient allowedClient = client(allowedBuilder.build(), 1024);
        allowedServer.expect(requestTo(HTTP_LOOPBACK_ENDPOINT))
                .andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));

        String response = allowedClient.complete(
                HTTP_LOOPBACK_ENDPOINT, true, emptyCredentials(), REQUEST_BODY);

        assertEquals("{\"choices\":[]}", response);
        allowedServer.verify();
    }

    @Test
    void complete_loopbackHostIsBlockedUnlessInternalAllowanceIsClampedOn() {
        RestClient.Builder deniedBuilder = RestClient.builder();
        MockRestServiceServer deniedServer = MockRestServiceServer.bindTo(deniedBuilder).build();
        OpenAiCompatibleClient deniedClient = client(deniedBuilder.build(), 1024);

        AiProviderException denied = assertThrows(AiProviderException.class,
                () -> deniedClient.complete(
                        HTTPS_LOOPBACK_ENDPOINT, false, emptyCredentials(), REQUEST_BODY));

        assertEquals("AI provider egress host resolved to a blocked address", denied.getMessage());
        deniedServer.verify();

        RestClient.Builder allowedBuilder = RestClient.builder();
        MockRestServiceServer allowedServer = MockRestServiceServer.bindTo(allowedBuilder).build();
        OpenAiCompatibleClient allowedClient = client(allowedBuilder.build(), 1024);
        allowedServer.expect(requestTo(HTTPS_LOOPBACK_ENDPOINT))
                .andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));

        String response = allowedClient.complete(
                HTTPS_LOOPBACK_ENDPOINT, true, emptyCredentials(), REQUEST_BODY);

        assertEquals("{\"choices\":[]}", response);
        allowedServer.verify();
    }

    @Test
    void complete_nonSuccessStatusRaisesStatusOnlySanitizedException() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleClient client = client(builder.build(), 1024);
        server.expect(requestTo(PUBLIC_ENDPOINT))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("SENSITIVE_RESPONSE_BODY " + API_KEY));

        AiProviderException exception = assertThrows(AiProviderException.class,
                () -> client.complete(PUBLIC_ENDPOINT, false, credentials(), REQUEST_BODY));

        assertEquals("OpenAI-compatible invocation failed with status 401", exception.getMessage());
        assertFalse(String.valueOf(exception).contains(API_KEY));
        assertFalse(String.valueOf(exception).contains("SENSITIVE_RESPONSE_BODY"));
        assertFalse(client.toString().contains(API_KEY));
        assertNull(exception.getCause());
        server.verify();
    }

    @Test
    void complete_oversizedResponseRaisesSanitizedException() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleClient client = client(builder.build(), 8);
        server.expect(requestTo(PUBLIC_ENDPOINT))
                .andRespond(withSuccess("SENSITIVE_RESPONSE_BODY", MediaType.APPLICATION_JSON));

        AiProviderException exception = assertThrows(AiProviderException.class,
                () -> client.complete(PUBLIC_ENDPOINT, false, credentials(), REQUEST_BODY));

        assertFalse(String.valueOf(exception).contains(API_KEY));
        assertFalse(String.valueOf(exception).contains("SENSITIVE_RESPONSE_BODY"));
        assertNull(exception.getCause());
        server.verify();
    }

    @Test
    void complete_revalidatesEndpointAndRejectsHeaderInjectionBeforeSend() {
        OpenAiCompatibleClient client = client(RestClient.create(), 1024);

        for (URI endpoint : List.of(
                URI.create("ftp://api.example.test/v1/chat/completions"),
                URI.create("https://user@api.example.test/v1/chat/completions"),
                URI.create("https://api.example.test/v1/chat/completions#fragment"),
                URI.create("https:///v1/chat/completions"))) {
            assertThrows(AiProviderException.class,
                    () -> client.complete(endpoint, false, emptyCredentials(), REQUEST_BODY));
        }

        String injectedKey = API_KEY + "\r\nInjected: true";
        AiProviderException exception = assertThrows(AiProviderException.class,
                () -> client.complete(PUBLIC_ENDPOINT, false,
                        AiCredentials.of(Map.of("apiKey", injectedKey)), REQUEST_BODY));

        assertEquals("Invalid OpenAI-compatible credentials", exception.getMessage());
        assertFalse(String.valueOf(exception).contains(API_KEY));
        assertNull(exception.getCause());
    }

    @RepeatedTest(5)
    void completeHardCancelsAResponseThatDripsWithinTheSocketTimeout() throws Exception {
        HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService serverExecutor = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().daemon().name("openai-compatible-test-server-", 0).factory());
        server.setExecutor(serverExecutor);
        server.createContext("/v1/chat/completions", exchange -> {
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
        AiProperties properties = new AiProperties();
        properties.setConnectTimeoutMs(500);
        properties.setRequestTimeoutMs(150);
        when(endpointAddressValidator.resolveFetchable("127.0.0.1", true))
                .thenReturn(InetAddress.getByName("127.0.0.1"));
        OpenAiCompatibleClient client = new OpenAiCompatibleClient(properties, endpointAddressValidator);
        URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                + "/v1/chat/completions");
        long started = System.nanoTime();
        try {
            AiProviderException exception = assertThrows(AiProviderException.class,
                    () -> client.complete(endpoint, true, emptyCredentials(), REQUEST_BODY));

            assertEquals("OpenAI-compatible invocation exceeded its deadline", exception.getMessage());
            assertTrue(java.time.Duration.ofNanos(System.nanoTime() - started).toMillis() < 5000);
        } finally {
            client.shutdown();
            server.stop(0);
            serverExecutor.shutdownNow();
        }
    }

    @Test
    void completeBoundsFinalDnsResolutionWithinTheRequestDeadline() throws Exception {
        CountDownLatch resolverStarted = new CountDownLatch(1);
        CountDownLatch releaseResolver = new CountDownLatch(1);
        when(endpointAddressValidator.resolveFetchable("api.example.test", false)).thenAnswer(invocation -> {
            resolverStarted.countDown();
            boolean interrupted = false;
            while (true) {
                try {
                    releaseResolver.await();
                    break;
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            return InetAddress.getByName("8.8.8.8");
        });
        AiProperties properties = new AiProperties();
        properties.setConnectTimeoutMs(500);
        properties.setRequestTimeoutMs(100);
        OpenAiCompatibleClient client = new OpenAiCompatibleClient(properties, endpointAddressValidator);
        long started = System.nanoTime();
        try {
            AiProviderException exception = assertThrows(AiProviderException.class,
                    () -> client.complete(PUBLIC_ENDPOINT, false, emptyCredentials(), REQUEST_BODY));

            assertEquals("OpenAI-compatible invocation exceeded its deadline", exception.getMessage());
            assertEquals(0, resolverStarted.getCount());
            assertTrue(java.time.Duration.ofNanos(System.nanoTime() - started).toMillis() < 5000);
        } finally {
            releaseResolver.countDown();
            client.shutdown();
        }
    }

    @Test
    void expiredDeadlineDoesNotStartFinalDnsResolution() {
        OpenAiCompatibleClient client = new OpenAiCompatibleClient(
                new AiProperties(), endpointAddressValidator);
        AiRequestDeadline deadline = AiRequestDeadline.afterNanos(1);
        while (!deadline.isExpired()) {
            Thread.onSpinWait();
        }
        try {
            AiProviderException exception = assertThrows(
                    AiProviderException.class,
                    () -> client.complete(
                            PUBLIC_ENDPOINT,
                            false,
                            emptyCredentials(),
                            REQUEST_BODY,
                            deadline));

            assertEquals(
                    "OpenAI-compatible invocation exceeded its deadline",
                    exception.getMessage());
            verify(endpointAddressValidator, never()).resolveFetchable(anyString(), anyBoolean());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void completeSharesOneDeadlineAcrossDnsAndHttpBodyRead() throws Exception {
        HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService serverExecutor = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().daemon().name("openai-compatible-budget-test-", 0).factory());
        server.setExecutor(serverExecutor);
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 0);
                Thread.sleep(180);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        when(endpointAddressValidator.resolveFetchable("127.0.0.1", true)).thenAnswer(invocation -> {
            Thread.sleep(180);
            return InetAddress.getByName("127.0.0.1");
        });
        AiProperties properties = new AiProperties();
        properties.setConnectTimeoutMs(500);
        properties.setRequestTimeoutMs(300);
        OpenAiCompatibleClient client = new OpenAiCompatibleClient(properties, endpointAddressValidator);
        URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                + "/v1/chat/completions");
        long started = System.nanoTime();
        try {
            AiProviderException exception = assertThrows(AiProviderException.class,
                    () -> client.complete(endpoint, true, emptyCredentials(), REQUEST_BODY));

            assertEquals("OpenAI-compatible invocation exceeded its deadline", exception.getMessage());
            assertTrue(java.time.Duration.ofNanos(System.nanoTime() - started).toMillis() < 5000);
        } finally {
            client.shutdown();
            server.stop(0);
            serverExecutor.shutdownNow();
        }
    }

    @Test
    void completeFailsClosedWhenFinalResolutionWorkersAreSaturated() throws Exception {
        CountDownLatch resolversStarted = new CountDownLatch(2);
        CountDownLatch releaseResolvers = new CountDownLatch(1);
        when(endpointAddressValidator.resolveFetchable(anyString(), anyBoolean())).thenAnswer(invocation -> {
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
            return InetAddress.getByName("8.8.8.8");
        });
        AiProperties properties = new AiProperties();
        properties.setConnectTimeoutMs(500);
        properties.setRequestTimeoutMs(50);
        OpenAiCompatibleClient client = new OpenAiCompatibleClient(properties, endpointAddressValidator);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = callers.submit(() -> assertThrows(AiProviderException.class,
                    () -> client.complete(PUBLIC_ENDPOINT, false, emptyCredentials(), REQUEST_BODY)));
            Future<?> second = callers.submit(() -> assertThrows(AiProviderException.class,
                    () -> client.complete(PUBLIC_ENDPOINT, false, emptyCredentials(), REQUEST_BODY)));
            assertTrue(resolversStarted.await(1, TimeUnit.SECONDS));

            long started = System.nanoTime();
            AiProviderException exception = assertThrows(AiProviderException.class,
                    () -> client.complete(PUBLIC_ENDPOINT, false, emptyCredentials(), REQUEST_BODY));
            assertEquals("OpenAI-compatible invocation failed during transport", exception.getMessage());
            assertTrue(java.time.Duration.ofNanos(System.nanoTime() - started).toMillis() < 5000);
            first.get(1, TimeUnit.SECONDS);
            second.get(1, TimeUnit.SECONDS);
        } finally {
            releaseResolvers.countDown();
            callers.shutdownNow();
            client.shutdown();
        }
    }

    private static AiCredentials credentials() {
        return AiCredentials.of(Map.of("apiKey", API_KEY));
    }

    private static AiCredentials emptyCredentials() {
        return AiCredentials.of(Map.of());
    }

    private OpenAiCompatibleClient client(RestClient restClient, int maxResponseBytes) {
        when(endpointAddressValidator.resolveFetchable(anyString(), anyBoolean())).thenAnswer(invocation -> {
            String host = invocation.getArgument(0);
            boolean allowPrivate = invocation.getArgument(1);
            if ("localhost".equals(host) && !allowPrivate) {
                throw new AiProviderException("AI provider egress host resolved to a blocked address");
            }
            return InetAddress.getByName(allowPrivate ? "127.0.0.1" : "8.8.8.8");
        });
        return new OpenAiCompatibleClient(restClient, maxResponseBytes, endpointAddressValidator);
    }
}
