package ooo.klae.connex.backend.ai.provider.vertex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.ai.provider.AiCompletionRequest;
import ooo.klae.connex.backend.ai.provider.AiCompletionResult;
import ooo.klae.connex.backend.ai.provider.AiCredentials;
import ooo.klae.connex.backend.ai.provider.AiMessage;
import ooo.klae.connex.backend.ai.provider.AiProviderException;
import ooo.klae.connex.backend.ai.provider.AiProviderTarget;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class VertexAdapterTest {
    private static final String SERVICE_ACCOUNT_JSON = "SERVICE_ACCOUNT_JSON_SECRET";
    private static final String PRIVATE_KEY = "PRIVATE_KEY_SECRET";
    private static final String ACCESS_TOKEN = "ACCESS_TOKEN_SECRET";
    private static final String PROMPT = "PROMPT_TEXT_SECRET";

    @Mock private VertexClient vertexClient;
    @Mock private GoogleAccessTokenClient googleAccessTokenClient;

    private ObjectMapper objectMapper;
    private VertexAdapter adapter;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        adapter = new VertexAdapter(vertexClient, googleAccessTokenClient, objectMapper);
    }

    @Test
    void providerId_registersVertexAdapter() {
        assertEquals("vertex", adapter.providerId());
    }

    @Test
    void complete_geminiBuildsEncodedEndpointAndRequestAndParsesResponse() throws Exception {
        when(googleAccessTokenClient.accessToken(any(AiCredentials.class))).thenReturn(ACCESS_TOKEN);
        when(vertexClient.complete(any(URI.class), eq(ACCESS_TOKEN), anyString()))
                .thenReturn("""
                        {
                          "candidates": [{
                            "content": {"parts": [{"text":"Hello"},{"text":" world"}]},
                            "finishReason": "STOP"
                          }],
                          "usageMetadata": {"promptTokenCount":12,"candidatesTokenCount":3}
                        }
                        """);

        AiCompletionResult result = adapter.complete(request("gemini-2.5-pro@001", "Use short answers"));

        ArgumentCaptor<URI> endpoint = ArgumentCaptor.forClass(URI.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(vertexClient).complete(endpoint.capture(), eq(ACCESS_TOKEN), body.capture());
        assertEquals("https://us-central1-aiplatform.googleapis.com/v1/projects/connex-prod1/locations/"
                + "us-central1/publishers/google/models/gemini-2.5-pro%40001:generateContent",
                endpoint.getValue().toString());
        JsonNode root = objectMapper.readTree(body.getValue());
        assertEquals("user", root.path("contents").path(0).path("role").asString());
        assertEquals("Hello?", root.path("contents").path(0).path("parts").path(0).path("text").asString());
        assertEquals("model", root.path("contents").path(1).path("role").asString());
        assertEquals("Hello.", root.path("contents").path(1).path("parts").path(0).path("text").asString());
        assertEquals("Use short answers",
                root.path("systemInstruction").path("parts").path(0).path("text").asString());
        assertEquals(64, root.path("generationConfig").path("maxOutputTokens").asInt());
        assertEquals(0.25, root.path("generationConfig").path("temperature").asDouble());
        assertEquals("Hello world", result.text());
        assertEquals(12, result.inputTokens());
        assertEquals(3, result.outputTokens());
        assertEquals("stop", result.stopReason());
        assertFalse(result.toString().contains("Hello world"));
    }

    @Test
    void complete_claudeBuildsEncodedEndpointAndRequestAndParsesResponse() throws Exception {
        when(googleAccessTokenClient.accessToken(any(AiCredentials.class))).thenReturn(ACCESS_TOKEN);
        when(vertexClient.complete(any(URI.class), eq(ACCESS_TOKEN), anyString()))
                .thenReturn("""
                        {
                          "content": [
                            {"type":"text","text":"Hello"},
                            {"type":"text","text":" from Claude"}
                          ],
                          "usage": {"input_tokens":9,"output_tokens":4},
                          "stop_reason":"end_turn"
                        }
                        """);

        AiCompletionResult result = adapter.complete(request("claude-sonnet-4@20250514", "Be concise"));

        ArgumentCaptor<URI> endpoint = ArgumentCaptor.forClass(URI.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(vertexClient).complete(endpoint.capture(), eq(ACCESS_TOKEN), body.capture());
        assertEquals("https://us-central1-aiplatform.googleapis.com/v1/projects/connex-prod1/locations/"
                + "us-central1/publishers/anthropic/models/claude-sonnet-4%4020250514:rawPredict",
                endpoint.getValue().toString());
        JsonNode root = objectMapper.readTree(body.getValue());
        assertEquals("vertex-2023-10-16", root.path("anthropic_version").asString());
        assertEquals("Be concise", root.path("system").asString());
        assertEquals("user", root.path("messages").path(0).path("role").asString());
        assertEquals("Hello?", root.path("messages").path(0).path("content").asString());
        assertEquals("assistant", root.path("messages").path(1).path("role").asString());
        assertEquals("Hello.", root.path("messages").path(1).path("content").asString());
        assertEquals(64, root.path("max_tokens").asInt());
        assertEquals(0.25, root.path("temperature").asDouble());
        assertEquals("Hello from Claude", result.text());
        assertEquals(9, result.inputTokens());
        assertEquals(4, result.outputTokens());
        assertEquals("end_turn", result.stopReason());
    }

    @Test
    void complete_omitsBlankSystemPromptsForBothFamilies() throws Exception {
        when(googleAccessTokenClient.accessToken(any(AiCredentials.class))).thenReturn(ACCESS_TOKEN);
        when(vertexClient.complete(any(URI.class), eq(ACCESS_TOKEN), anyString()))
                .thenReturn(geminiResponse(), claudeResponse());

        adapter.complete(request("gemini-2.5-flash", " "));
        adapter.complete(request("claude-sonnet-4", null));

        ArgumentCaptor<String> bodies = ArgumentCaptor.forClass(String.class);
        verify(vertexClient, org.mockito.Mockito.times(2))
                .complete(any(URI.class), eq(ACCESS_TOKEN), bodies.capture());
        assertFalse(objectMapper.readTree(bodies.getAllValues().get(0)).has("systemInstruction"));
        assertFalse(objectMapper.readTree(bodies.getAllValues().get(1)).has("system"));
    }

    @Test
    void complete_rejectsUnsupportedSlashAndConsecutiveDotModelsBeforeAnySend() {
        AiProviderException unsupported = assertThrows(AiProviderException.class,
                () -> adapter.complete(request("mistral-large", null)));
        assertEquals("Unsupported Vertex model", unsupported.getMessage());

        for (String modelId : List.of("claude/sonnet-4", "gemini..flash")) {
            AiProviderException invalid = assertThrows(AiProviderException.class,
                    () -> adapter.complete(request(modelId, null)));
            assertEquals("Invalid Vertex model id", invalid.getMessage());
        }
        verifyNoInteractions(googleAccessTokenClient, vertexClient);
    }

    @Test
    void complete_revalidatesProjectRegionProviderAndMessageRoleBeforeAnySend() {
        AiCompletionRequest invalidProject = requestWithTarget(new AiProviderTarget(
                "vertex", "us-central1", "gemini-2.5-flash", null, null, null, "Connex Prod", false));
        AiCompletionRequest invalidRegion = requestWithTarget(new AiProviderTarget(
                "vertex", "us/central1", "gemini-2.5-flash", null, null, null, "connex-prod1", false));
        AiCompletionRequest invalidProvider = requestWithTarget(new AiProviderTarget(
                "bedrock", "us-central1", "gemini-2.5-flash", null, null, null, "connex-prod1", false));
        AiCompletionRequest invalidRole = new AiCompletionRequest(
                target("gemini-2.5-flash"), credentials(), null,
                List.of(new AiMessage("system", "role text")), 64, 0.25);

        assertThrows(AiProviderException.class, () -> adapter.complete(invalidProject));
        assertThrows(AiProviderException.class, () -> adapter.complete(invalidRegion));
        assertThrows(AiProviderException.class, () -> adapter.complete(invalidProvider));
        assertThrows(AiProviderException.class, () -> adapter.complete(invalidRole));
        verifyNoInteractions(googleAccessTokenClient, vertexClient);
    }

    @Test
    void complete_malformedFamilyResponsesAreSanitized() {
        when(googleAccessTokenClient.accessToken(any(AiCredentials.class))).thenReturn(ACCESS_TOKEN);
        when(vertexClient.complete(any(URI.class), eq(ACCESS_TOKEN), anyString()))
                .thenReturn("{\"response\":\"SENSITIVE_RESPONSE_BODY\"}");

        AiProviderException gemini = assertThrows(AiProviderException.class,
                () -> adapter.complete(request("gemini-2.5-flash", null)));
        AiProviderException claude = assertThrows(AiProviderException.class,
                () -> adapter.complete(request("claude-sonnet-4", null)));

        assertEquals("Vertex response was invalid", gemini.getMessage());
        assertEquals("Vertex response was invalid", claude.getMessage());
        assertFalse(String.valueOf(gemini).contains("SENSITIVE_RESPONSE_BODY"));
        assertFalse(String.valueOf(claude).contains("SENSITIVE_RESPONSE_BODY"));
        assertNull(gemini.getCause());
        assertNull(claude.getCause());
    }

    @Test
    void complete_neverExposesCredentialsTokenPromptOrResponseInExceptionsOrToString() {
        when(googleAccessTokenClient.accessToken(any(AiCredentials.class))).thenReturn(ACCESS_TOKEN);
        when(vertexClient.complete(any(URI.class), eq(ACCESS_TOKEN), anyString()))
                .thenThrow(new IllegalStateException(SERVICE_ACCOUNT_JSON + PRIVATE_KEY + ACCESS_TOKEN
                        + PROMPT + "SENSITIVE_RESPONSE_BODY"));
        AiCompletionRequest request = new AiCompletionRequest(
                target("gemini-2.5-flash"),
                AiCredentials.of(Map.of("serviceAccountJson", SERVICE_ACCOUNT_JSON + PRIVATE_KEY)),
                PROMPT,
                List.of(new AiMessage("user", PROMPT)),
                64,
                0.25);

        AiProviderException exception = assertThrows(AiProviderException.class, () -> adapter.complete(request));

        assertEquals("Vertex adapter failed", exception.getMessage());
        for (String secret : List.of(SERVICE_ACCOUNT_JSON, PRIVATE_KEY, ACCESS_TOKEN, PROMPT,
                "SENSITIVE_RESPONSE_BODY")) {
            assertFalse(String.valueOf(exception).contains(secret));
            assertFalse(request.toString().contains(secret));
            assertFalse(request.messages().get(0).toString().contains(secret));
        }
        assertNull(exception.getCause());
    }

    private static AiCompletionRequest request(String modelId, String systemPrompt) {
        return new AiCompletionRequest(
                target(modelId),
                credentials(),
                systemPrompt,
                List.of(
                        new AiMessage("user", "Hello?"),
                        new AiMessage("assistant", "Hello.")),
                64,
                0.25);
    }

    private static AiCompletionRequest requestWithTarget(AiProviderTarget target) {
        return new AiCompletionRequest(
                target,
                credentials(),
                null,
                List.of(new AiMessage("user", "Hello?")),
                64,
                0.25);
    }

    private static AiProviderTarget target(String modelId) {
        return new AiProviderTarget(
                "vertex", "us-central1", modelId, null, null, null, "connex-prod1", false);
    }

    private static AiCredentials credentials() {
        return AiCredentials.of(Map.of("serviceAccountJson", SERVICE_ACCOUNT_JSON + PRIVATE_KEY));
    }

    private static String geminiResponse() {
        return """
                {
                  "candidates":[{"content":{"parts":[{"text":"Done"}]},"finishReason":"STOP"}],
                  "usageMetadata":{"promptTokenCount":1,"candidatesTokenCount":1}
                }
                """;
    }

    private static String claudeResponse() {
        return """
                {
                  "content":[{"type":"text","text":"Done"}],
                  "usage":{"input_tokens":1,"output_tokens":1},
                  "stop_reason":"end_turn"
                }
                """;
    }
}
