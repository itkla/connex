package ooo.klae.connex.backend.ai.provider.vertex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.apache.hc.core5.http.ContentType;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import ooo.klae.connex.backend.ai.egress.AiEgressGuard;
import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.egress.AiRequestDeadline;
import ooo.klae.connex.backend.ai.egress.FixedAiProviderClient;
import ooo.klae.connex.backend.ai.provider.AiCompletionResult;
import ooo.klae.connex.backend.ai.provider.AiProviderException;
import ooo.klae.connex.backend.ai.provider.AiProviderStreamObserver;
import ooo.klae.connex.backend.ai.provider.AiReasoningMode;
import ooo.klae.connex.backend.ai.provider.AiStructuredOutputEnforcement;
import tools.jackson.databind.ObjectMapper;

class VertexClientTest {
    private static final URI ENDPOINT = URI.create("https://us-central1-aiplatform.googleapis.com/v1/projects/"
            + "connex-prod1/locations/us-central1/publishers/google/models/gemini-2.5-flash:generateContent");
    private static final URI STREAM_ENDPOINT = URI.create(
            "https://us-central1-aiplatform.googleapis.com/v1/projects/connex-prod1/locations/"
            + "us-central1/publishers/google/models/gemini-2.5-flash:streamGenerateContent?alt=sse");
    private static final String ACCESS_TOKEN = "vertex_access_token_secret";
    private static final String REQUEST_BODY = "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"Hello?\"}]}]}";

    @Test
    void complete_productionPathUsesThePinnedFixedProviderTransport() {
        AiProperties properties = new AiProperties();
        FixedAiProviderClient providerClient = mock(FixedAiProviderClient.class);
        when(providerClient.post(
                any(URI.class), anySet(), anyMap(), any(ContentType.class), any(byte[].class),
                any(AiRequestDeadline.class), any(), any(Runnable.class)))
                .thenReturn(new FixedAiProviderClient.Response(
                        200, "{\"candidates\":[]}".getBytes(StandardCharsets.UTF_8)));
        VertexClient client = new VertexClient(properties, providerClient);

        String response = client.complete(ENDPOINT, ACCESS_TOKEN, REQUEST_BODY);

        assertEquals("{\"candidates\":[]}", response);
        verify(providerClient).post(
                eq(ENDPOINT),
                eq(java.util.Set.of("us-central1-aiplatform.googleapis.com")),
                argThat(headers -> ("Bearer " + ACCESS_TOKEN).equals(headers.get("Authorization"))),
                eq(ContentType.APPLICATION_JSON),
                aryEq(REQUEST_BODY.getBytes(StandardCharsets.UTF_8)),
                any(AiRequestDeadline.class),
                eq("Vertex invocation"), any(Runnable.class));
    }

    @Test
    void complete_sendsBearerTokenAndBodyAfterEgressVetting() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        VertexClient client = new VertexClient(builder.build(), 1024);
        server.expect(requestTo(ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + ACCESS_TOKEN))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json(REQUEST_BODY))
                .andRespond(withSuccess("{\"candidates\":[]}", MediaType.APPLICATION_JSON));

        try (MockedStatic<AiEgressGuard> guard = mockStatic(AiEgressGuard.class)) {
            String response = client.complete(ENDPOINT, ACCESS_TOKEN, REQUEST_BODY);

            assertEquals("{\"candidates\":[]}", response);
            guard.verify(() -> AiEgressGuard.requireFetchableHost(
                    "us-central1-aiplatform.googleapis.com", false));
            server.verify();
        }
    }

    @Test
    void complete_nonSuccessStatusRaisesSanitizedException() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        VertexClient client = new VertexClient(builder.build(), 1024);
        server.expect(requestTo(ENDPOINT))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("SENSITIVE_RESPONSE_BODY " + ACCESS_TOKEN));

        try (MockedStatic<AiEgressGuard> ignored = mockStatic(AiEgressGuard.class)) {
            AiProviderException exception = assertThrows(AiProviderException.class,
                    () -> client.complete(ENDPOINT, ACCESS_TOKEN, REQUEST_BODY));

            assertEquals("Vertex invocation failed with status 403", exception.getMessage());
            assertFalse(String.valueOf(exception).contains(ACCESS_TOKEN));
            assertFalse(String.valueOf(exception).contains("SENSITIVE_RESPONSE_BODY"));
            assertFalse(client.toString().contains(ACCESS_TOKEN));
            assertNull(exception.getCause());
            server.verify();
        }
    }

    @Test
    void complete_oversizedResponseRaisesSanitizedException() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        VertexClient client = new VertexClient(builder.build(), 8);
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess("SENSITIVE_RESPONSE_BODY", MediaType.APPLICATION_JSON));

        try (MockedStatic<AiEgressGuard> ignored = mockStatic(AiEgressGuard.class)) {
            AiProviderException exception = assertThrows(AiProviderException.class,
                    () -> client.complete(ENDPOINT, ACCESS_TOKEN, REQUEST_BODY));

            assertFalse(String.valueOf(exception).contains("SENSITIVE_RESPONSE_BODY"));
            assertFalse(String.valueOf(exception).contains(ACCESS_TOKEN));
            assertNull(exception.getCause());
            server.verify();
        }
    }

    @Test
    void complete_rejectsUnconstructedEndpointAndHeaderInjectionBeforeSend() {
        VertexClient client = new VertexClient(RestClient.create(), 1024);

        assertThrows(AiProviderException.class, () -> client.complete(
                URI.create("https://us-central1-aiplatform.googleapis.com.evil.test/v1"),
                ACCESS_TOKEN,
                REQUEST_BODY));
        assertThrows(AiProviderException.class, () -> client.complete(
                ENDPOINT,
                ACCESS_TOKEN + "\r\nInjected: true",
                REQUEST_BODY));
    }

    /**
     * Vertex answers {@code streamGenerateContent} as server-sent events only when {@code alt=sse}
     * is on the query string, so the endpoint the adapter builds has to survive validation and
     * reach the transport rather than being refused for carrying a query at all.
     */
    @Test
    void stream_sseEndpointReachesTheTransportWithItsQueryIntact() {
        AiProperties properties = new AiProperties();
        FixedAiProviderClient providerClient = mock(FixedAiProviderClient.class);
        AiCompletionResult completion = new AiCompletionResult(
                "Hello", 3, 2, "stop", AiStructuredOutputEnforcement.PROMPT_ONLY);
        when(providerClient.<AiCompletionResult>postStream(
                any(URI.class), anySet(), anyMap(), any(ContentType.class), any(byte[].class),
                any(AiRequestDeadline.class), any(), any(AiProviderStreamObserver.class), any(),
                any(Runnable.class)))
                .thenReturn(new FixedAiProviderClient.StreamResponse<>(200, completion, null));
        VertexClient client = new VertexClient(properties, providerClient);
        AiProviderStreamObserver observer = text -> {
        };

        AiCompletionResult result = client.stream(
                STREAM_ENDPOINT, ACCESS_TOKEN, REQUEST_BODY,
                AiRequestDeadline.afterMillis(5_000), accumulator(observer), observer);

        assertSame(completion, result);
        verify(providerClient).<AiCompletionResult>postStream(
                eq(STREAM_ENDPOINT),
                eq(java.util.Set.of("us-central1-aiplatform.googleapis.com")),
                argThat(headers -> ("Bearer " + ACCESS_TOKEN).equals(headers.get("Authorization"))),
                eq(ContentType.APPLICATION_JSON),
                aryEq(REQUEST_BODY.getBytes(StandardCharsets.UTF_8)),
                any(AiRequestDeadline.class),
                eq("Vertex invocation"),
                eq(observer),
                any(),
                any(Runnable.class));
    }

    /** The streaming allowance is that one raw query verbatim, never a query string in general. */
    @Test
    void stream_rejectsEveryQueryOtherThanTheExactSseSelector() {
        FixedAiProviderClient providerClient = mock(FixedAiProviderClient.class);
        VertexClient client = new VertexClient(new AiProperties(), providerClient);
        AiProviderStreamObserver observer = text -> {
        };

        for (String query : java.util.List.of(
                "?alt=json", "?alt=sse&trace=1", "?alt=SSE", "?alt=sse%26x=1", "?x=1&alt=sse")) {
            URI endpoint = URI.create(STREAM_ENDPOINT.toString().replace("?alt=sse", query));

            AiProviderException exception = assertThrows(AiProviderException.class,
                    () -> client.stream(endpoint, ACCESS_TOKEN, REQUEST_BODY,
                            AiRequestDeadline.afterMillis(5_000), accumulator(observer), observer));

            assertEquals("Invalid Vertex endpoint", exception.getMessage());
        }
        verifyNoInteractions(providerClient);
    }

    /** Only the streaming path needs the selector; a buffered call still carries no query. */
    @Test
    void complete_stillRejectsTheStreamingSseQuery() {
        FixedAiProviderClient providerClient = mock(FixedAiProviderClient.class);
        VertexClient client = new VertexClient(new AiProperties(), providerClient);

        AiProviderException exception = assertThrows(AiProviderException.class,
                () -> client.complete(STREAM_ENDPOINT, ACCESS_TOKEN, REQUEST_BODY));

        assertEquals("Invalid Vertex endpoint", exception.getMessage());
        verifyNoInteractions(providerClient);
    }

    private static VertexSseAccumulator accumulator(AiProviderStreamObserver observer) {
        return new VertexSseAccumulator(
                new ObjectMapper(), observer, AiStructuredOutputEnforcement.PROMPT_ONLY,
                AiReasoningMode.NONE);
    }
}
