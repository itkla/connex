package ooo.klae.connex.backend.ai.provider.vertex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.egress.AiRequestDeadline;
import ooo.klae.connex.backend.ai.provider.AiCompletionRequest;
import ooo.klae.connex.backend.ai.provider.AiCompletionResult;
import ooo.klae.connex.backend.ai.provider.AiCredentials;
import ooo.klae.connex.backend.ai.provider.AiMessage;
import ooo.klae.connex.backend.ai.provider.AiOutputMode;
import ooo.klae.connex.backend.ai.provider.AiProviderException;
import ooo.klae.connex.backend.ai.provider.AiProviderAttemptExecutor;
import ooo.klae.connex.backend.ai.provider.AiProviderStreamObserver;
import ooo.klae.connex.backend.ai.provider.AiProviderTarget;
import ooo.klae.connex.backend.ai.provider.AiReasoningMode;
import ooo.klae.connex.backend.ai.provider.AiResponseSchema;
import ooo.klae.connex.backend.ai.provider.AiStructuredOutputEnforcement;
import ooo.klae.connex.backend.ai.provider.AiToolCallingMode;
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
    private AiProperties aiProperties;
    private VertexAdapter adapter;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        aiProperties = new AiProperties();
        adapter = new VertexAdapter(vertexClient, googleAccessTokenClient, objectMapper, aiProperties);
    }

    @Test
    void providerId_registersVertexAdapter() {
        assertEquals("vertex", adapter.providerId());
        assertEquals(AiToolCallingMode.NONE, adapter.toolCallingCapability(null));
    }

    @Test
    void reasoningCapabilityUsesNativeModeOnlyForKnownModels() {
        assertEquals(AiReasoningMode.NATIVE,
                adapter.reasoningCapability(target("gemini-2.5-flash")));
        assertEquals(AiReasoningMode.TAGGED,
                adapter.reasoningCapability(target("gemini-1.5-pro")));
        assertEquals(AiReasoningMode.TAGGED,
                adapter.reasoningCapability(target("gemini-2.5-flash-image")));
        assertEquals(AiReasoningMode.NATIVE,
                adapter.reasoningCapability(target("gemini-3-pro-image")));
        assertEquals(AiReasoningMode.NATIVE,
                adapter.reasoningCapability(target("gemini-3.6-flash-preview")));
        assertEquals(AiReasoningMode.TAGGED,
                adapter.reasoningCapability(target("gemini-3.6-ultra")));
    }

    @Test
    void contextWindowUsesSafeDefaultForUnknownModelFamilies() {
        assertEquals(32_768, adapter.contextWindowTokens(target("gemini-2.5-flash-image")));
        assertEquals(65_536, adapter.contextWindowTokens(target("gemini-3-pro-image")));
        assertEquals(4_096, adapter.contextWindowTokens(target("gemini-unknown")));
        assertEquals(4_096, adapter.contextWindowTokens(target("gemini-3.6-ultra")));
        assertEquals(4_096, adapter.contextWindowTokens(null));
        assertEquals(AiStructuredOutputEnforcement.PROMPT_ONLY,
                adapter.structuredOutputCapability(target("gemini-3-pro-image")));
    }

    /**
     * Gemini text models have been million-token models since 2.5; the adapter previously reported
     * the 128,000-token window that predated them.
     */
    @Test
    void geminiTextModelsReportTheirDocumentedMillionTokenWindow() {
        for (String modelId : List.of(
                "gemini-2.5-flash", "gemini-2.5-pro", "gemini-3.6-flash",
                "gemini-3.1-pro-preview")) {
            assertEquals(1_048_576, adapter.contextWindowTokens(target(modelId)), modelId);
            assertEquals(65_536, adapter.maxOutputTokens(target(modelId)), modelId);
        }
    }

    @Test
    void outputCapacityUsesDocumentedPublisherFamilyLimits() {
        assertEquals(8_192, adapter.maxOutputTokens(target("gemini-1.5-pro")));
        assertEquals(128_000, adapter.contextWindowTokens(target("gemini-1.5-pro")));
        assertEquals(32_768, adapter.maxOutputTokens(target("gemini-3-pro-image")));
        assertEquals(4_096, adapter.maxOutputTokens(target("gemini-3.6-ultra")));
        assertEquals(4_096, adapter.maxOutputTokens(target("claude-3-haiku@20240307")));
        assertEquals(65_536, adapter.maxOutputTokens(target("claude-sonnet-4@20250514")));
        assertEquals(4_096, adapter.maxOutputTokens(null));
    }

    /**
     * Google publishes no partner-model limit page this catalog could cite for Claude on Vertex,
     * so those entries stay at the values the adapter has always returned rather than inheriting
     * the first-party numbers the Bedrock model cards independently confirm.
     */
    @Test
    void unverifiedVertexPartnerClaudeModelsKeepTheirPreviousLimits() {
        assertEquals(200_000, adapter.contextWindowTokens(target("claude-opus-4-6")));
        assertEquals(131_072, adapter.maxOutputTokens(target("claude-opus-4-6")));
        assertEquals(200_000, adapter.contextWindowTokens(target("claude-sonnet-4-6")));
        assertEquals(131_072, adapter.maxOutputTokens(target("claude-sonnet-4-6")));
        assertEquals(200_000, adapter.contextWindowTokens(target("claude-opus-4-5@20251101")));
        assertEquals(65_536, adapter.maxOutputTokens(target("claude-opus-4-5@20251101")));
        assertEquals(200_000, adapter.contextWindowTokens(target("claude-opus-4@20250514")));
        assertEquals(32_768, adapter.maxOutputTokens(target("claude-opus-4@20250514")));
        assertEquals(200_000, adapter.contextWindowTokens(target("claude-3-5-sonnet@20241022")));
        assertEquals(8_192, adapter.maxOutputTokens(target("claude-3-5-sonnet@20241022")));
    }

    @Test
    void anOperatorOverrideCorrectsAnUnverifiedVertexPartnerModel() {
        AiProperties.ModelOverride override = new AiProperties.ModelOverride();
        override.setProvider("vertex");
        override.setModelId("claude-opus-4-6");
        override.setContextWindowTokens(1_000_000);
        override.setMaxOutputTokens(128_000);
        aiProperties.setModelOverrides(List.of(override));

        assertEquals(1_000_000, adapter.contextWindowTokens(target("claude-opus-4-6")));
        assertEquals(128_000, adapter.maxOutputTokens(target("claude-opus-4-6")));
        assertEquals(200_000, adapter.contextWindowTokens(target("claude-sonnet-4-6")));
    }

    @Test
    void complete_geminiBuildsEncodedEndpointAndRequestAndParsesResponse() throws Exception {
        when(googleAccessTokenClient.accessToken(
                any(AiCredentials.class), any(AiRequestDeadline.class))).thenReturn(ACCESS_TOKEN);
        when(vertexClient.complete(
                any(URI.class), eq(ACCESS_TOKEN), anyString(), any(AiRequestDeadline.class)))
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
        ArgumentCaptor<AiRequestDeadline> deadlines = ArgumentCaptor.forClass(AiRequestDeadline.class);
        verify(googleAccessTokenClient).accessToken(any(AiCredentials.class), deadlines.capture());
        verify(vertexClient).complete(
                endpoint.capture(), eq(ACCESS_TOKEN), body.capture(), deadlines.capture());
        assertSame(deadlines.getAllValues().getFirst(), deadlines.getAllValues().getLast());
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
        assertFalse(root.path("generationConfig").has("responseMimeType"));
        assertEquals("Hello world", result.text());
        assertEquals(12, result.inputTokens());
        assertEquals(3, result.outputTokens());
        assertEquals("stop", result.stopReason());
        assertFalse(result.toString().contains("Hello world"));
    }

    @Test
    void completeStreamingUsesGeminiSseEndpointAndExistingRequestAssembly() throws Exception {
        when(googleAccessTokenClient.accessToken(
                any(AiCredentials.class), any(AiRequestDeadline.class))).thenReturn(ACCESS_TOKEN);
        when(vertexClient.stream(
                any(URI.class), eq(ACCESS_TOKEN), anyString(), any(AiRequestDeadline.class),
                any(VertexSseAccumulator.class), any(AiProviderStreamObserver.class)))
                .thenReturn(new AiCompletionResult("Hello", 5, 1, "stop"));

        AiCompletionResult result = adapter.completeStreaming(
                request("gemini-2.5-flash", "Use short answers"), text -> {});

        ArgumentCaptor<URI> endpoint = ArgumentCaptor.forClass(URI.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(vertexClient).stream(
                endpoint.capture(), eq(ACCESS_TOKEN), body.capture(), any(AiRequestDeadline.class),
                any(VertexSseAccumulator.class), any(AiProviderStreamObserver.class));
        assertEquals("https://us-central1-aiplatform.googleapis.com/v1/projects/connex-prod1/locations/"
                + "us-central1/publishers/google/models/gemini-2.5-flash:streamGenerateContent?alt=sse",
                endpoint.getValue().toString());
        assertEquals("Use short answers",
                objectMapper.readTree(body.getValue())
                        .path("systemInstruction").path("parts").path(0).path("text").asString());
        assertEquals("Hello", result.text());
        assertTrue(adapter.supportsStreaming(target("gemini-2.5-flash")));
        assertFalse(adapter.supportsStreaming(target("claude-sonnet-4")));
    }

    @Test
    void completeStreamingRechecksInvocationAfterOAuthBeforeModelEgress() {
        when(googleAccessTokenClient.accessToken(
                any(AiCredentials.class), any(AiRequestDeadline.class))).thenReturn(ACCESS_TOKEN);
        AiCompletionRequest base = request("gemini-2.5-flash", null);
        AiCompletionRequest fenced = new AiCompletionRequest(
                base.target(), base.credentials(), base.systemPrompt(), base.messages(),
                base.images(), base.outputMode(), base.responseSchema(), base.nativeTools(),
                base.reasoningMode(), rejectingCheckpoint(), base.maxTokens(), base.temperature());

        AiProviderException exception = assertThrows(
                AiProviderException.class,
                () -> adapter.completeStreaming(fenced, text -> {}));

        assertEquals("provider snapshot changed", exception.getMessage());
        verifyNoInteractions(vertexClient);
    }

    @Test
    void completeBufferedRechecksInvocationAfterOAuthBeforeModelEgress() {
        when(googleAccessTokenClient.accessToken(
                any(AiCredentials.class), any(AiRequestDeadline.class))).thenReturn(ACCESS_TOKEN);
        AiCompletionRequest base = request("gemini-2.5-flash", null);
        AiCompletionRequest fenced = new AiCompletionRequest(
                base.target(), base.credentials(), base.systemPrompt(), base.messages(),
                base.images(), base.outputMode(), base.responseSchema(), base.nativeTools(),
                base.reasoningMode(), rejectingCheckpoint(), base.maxTokens(), base.temperature());

        AiProviderException exception = assertThrows(
                AiProviderException.class,
                () -> adapter.complete(fenced));

        assertEquals("provider snapshot changed", exception.getMessage());
        verifyNoInteractions(vertexClient);
    }

    @Test
    void complete_geminiJsonRequestsNativeJsonResponse() throws Exception {
        when(googleAccessTokenClient.accessToken(
                any(AiCredentials.class), any(AiRequestDeadline.class))).thenReturn(ACCESS_TOKEN);
        when(vertexClient.complete(
                any(URI.class), eq(ACCESS_TOKEN), anyString(), any(AiRequestDeadline.class)))
                .thenReturn(geminiResponse());

        adapter.complete(request("gemini-2.5-flash", null, AiOutputMode.JSON));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(vertexClient).complete(
                any(URI.class), eq(ACCESS_TOKEN), body.capture(), any(AiRequestDeadline.class));
        JsonNode generationConfig = objectMapper.readTree(body.getValue()).path("generationConfig");
        assertEquals("application/json", generationConfig.path("responseMimeType").asString());
    }

    @Test
    void complete_geminiSchemaRequestsControlledGeneration() throws Exception {
        when(googleAccessTokenClient.accessToken(
                any(AiCredentials.class), any(AiRequestDeadline.class))).thenReturn(ACCESS_TOKEN);
        when(vertexClient.complete(
                any(URI.class), eq(ACCESS_TOKEN), anyString(), any(AiRequestDeadline.class)))
                .thenReturn(geminiResponse());
        AiCompletionRequest request = new AiCompletionRequest(
                target("gemini-2.5-flash"),
                credentials(),
                null,
                List.of(new AiMessage("user", "Hello?")),
                List.of(),
                AiOutputMode.JSON,
                new AiResponseSchema("assistant_step",
                        objectMapper.readTree("{\"type\":\"object\"}")),
                64,
                0.25);

        AiCompletionResult result = adapter.complete(request);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(vertexClient).complete(
                any(URI.class), eq(ACCESS_TOKEN), body.capture(), any(AiRequestDeadline.class));
        JsonNode generationConfig = objectMapper.readTree(body.getValue()).path("generationConfig");
        assertEquals("application/json", generationConfig.path("responseMimeType").asString());
        assertEquals("object", generationConfig.path("responseJsonSchema").path("type").asString());
        assertEquals(AiStructuredOutputEnforcement.JSON_SCHEMA,
                result.structuredOutputEnforcement());
    }

    @Test
    void complete_geminiImageModelUsesPromptOnlyStructuredOutput() throws Exception {
        when(googleAccessTokenClient.accessToken(
                any(AiCredentials.class), any(AiRequestDeadline.class))).thenReturn(ACCESS_TOKEN);
        when(vertexClient.complete(
                any(URI.class), eq(ACCESS_TOKEN), anyString(), any(AiRequestDeadline.class)))
                .thenReturn(geminiResponse());
        AiCompletionRequest request = new AiCompletionRequest(
                target("gemini-3-pro-image"),
                credentials(),
                null,
                List.of(new AiMessage("user", "Hello?")),
                List.of(),
                AiOutputMode.JSON,
                new AiResponseSchema("assistant_step",
                        objectMapper.readTree("{\"type\":\"object\"}")),
                AiReasoningMode.NATIVE,
                ooo.klae.connex.backend.ai.provider.AiProviderAttemptExecutor.DIRECT,
                64,
                0.25);

        AiCompletionResult result = adapter.complete(request);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(vertexClient).complete(
                any(URI.class), eq(ACCESS_TOKEN), body.capture(), any(AiRequestDeadline.class));
        JsonNode generationConfig = objectMapper.readTree(body.getValue()).path("generationConfig");
        assertFalse(generationConfig.has("responseMimeType"));
        assertFalse(generationConfig.has("responseJsonSchema"));
        assertEquals(AiStructuredOutputEnforcement.PROMPT_ONLY,
                result.structuredOutputEnforcement());
    }

    @Test
    void complete_geminiNativeReasoningKeepsSchemaAndCountsThoughtTokens() throws Exception {
        when(googleAccessTokenClient.accessToken(
                any(AiCredentials.class), any(AiRequestDeadline.class))).thenReturn(ACCESS_TOKEN);
        when(vertexClient.complete(
                any(URI.class), eq(ACCESS_TOKEN), anyString(), any(AiRequestDeadline.class)))
                .thenReturn("""
                        {
                          "candidates":[{
                            "content":{"parts":[
                              {"thought":true,"text":"Compare the authorized signals."},
                              {"text":"{\\\"final\\\":\\\"done\\\"}"}
                            ]},
                            "finishReason":"STOP"
                          }],
                          "usageMetadata":{
                            "promptTokenCount":10,
                            "candidatesTokenCount":7,
                            "thoughtsTokenCount":11
                          }
                        }
                        """);
        AiCompletionRequest request = new AiCompletionRequest(
                target("gemini-2.5-flash"),
                credentials(),
                null,
                List.of(new AiMessage("user", "Hello?")),
                List.of(),
                AiOutputMode.JSON,
                new AiResponseSchema("assistant_step",
                        objectMapper.readTree("{\"type\":\"object\"}")),
                AiReasoningMode.NATIVE,
                ooo.klae.connex.backend.ai.provider.AiProviderAttemptExecutor.DIRECT,
                64,
                0.25);

        AiCompletionResult result = adapter.complete(request);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(vertexClient).complete(
                any(URI.class), eq(ACCESS_TOKEN), body.capture(), any(AiRequestDeadline.class));
        JsonNode generationConfig = objectMapper.readTree(body.getValue()).path("generationConfig");
        assertEquals(true,
                generationConfig.path("thinkingConfig").path("includeThoughts").asBoolean());
        assertEquals("application/json", generationConfig.path("responseMimeType").asString());
        assertEquals("object", generationConfig.path("responseJsonSchema").path("type").asString());
        assertEquals("Compare the authorized signals.", result.reasoning());
        assertEquals("{\"final\":\"done\"}", result.text());
        assertEquals(18, result.outputTokens());
        assertEquals(AiReasoningMode.NATIVE, result.reasoningMode());
    }

    @Test
    void complete_claudeBuildsEncodedEndpointAndRequestAndParsesResponse() throws Exception {
        when(googleAccessTokenClient.accessToken(
                any(AiCredentials.class), any(AiRequestDeadline.class))).thenReturn(ACCESS_TOKEN);
        when(vertexClient.complete(
                any(URI.class), eq(ACCESS_TOKEN), anyString(), any(AiRequestDeadline.class)))
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

        AiCompletionResult result = adapter.complete(
                request("claude-sonnet-4@20250514", "Be concise", AiOutputMode.JSON));

        ArgumentCaptor<URI> endpoint = ArgumentCaptor.forClass(URI.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(vertexClient).complete(
                endpoint.capture(), eq(ACCESS_TOKEN), body.capture(), any(AiRequestDeadline.class));
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
        assertFalse(root.has("generationConfig"));
        assertFalse(root.has("responseMimeType"));
        assertEquals("Hello from Claude", result.text());
        assertEquals(9, result.inputTokens());
        assertEquals(4, result.outputTokens());
        assertEquals("end_turn", result.stopReason());
    }

    @Test
    void complete_omitsBlankSystemPromptsForBothFamilies() throws Exception {
        when(googleAccessTokenClient.accessToken(
                any(AiCredentials.class), any(AiRequestDeadline.class))).thenReturn(ACCESS_TOKEN);
        when(vertexClient.complete(
                any(URI.class), eq(ACCESS_TOKEN), anyString(), any(AiRequestDeadline.class)))
                .thenReturn(geminiResponse(), claudeResponse());

        adapter.complete(request("gemini-2.5-flash", " "));
        adapter.complete(request("claude-sonnet-4", null));

        ArgumentCaptor<String> bodies = ArgumentCaptor.forClass(String.class);
        verify(vertexClient, org.mockito.Mockito.times(2))
                .complete(
                        any(URI.class),
                        eq(ACCESS_TOKEN),
                        bodies.capture(),
                        any(AiRequestDeadline.class));
        assertFalse(objectMapper.readTree(bodies.getAllValues().get(0)).has("systemInstruction"));
        assertFalse(objectMapper.readTree(bodies.getAllValues().get(1)).has("system"));
    }

    @Test
    void complete_rejectsUnsupportedSlashAndConsecutiveDotModelsBeforeAnySend() {
        AiProviderException unsupported = assertThrows(AiProviderException.class,
                () -> adapter.complete(request("mistral-large", null)));
        assertEquals("Unsupported Vertex model", unsupported.getMessage());

        for (String modelId : List.of("claude/sonnet-4", "gemini..flash", "Gemini-2.5-pro")) {
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
                List.of(new AiMessage("system", "role text")), List.of(), AiOutputMode.TEXT, 64, 0.25);

        assertThrows(AiProviderException.class, () -> adapter.complete(invalidProject));
        assertThrows(AiProviderException.class, () -> adapter.complete(invalidRegion));
        assertThrows(AiProviderException.class, () -> adapter.complete(invalidProvider));
        assertThrows(AiProviderException.class, () -> adapter.complete(invalidRole));
        verifyNoInteractions(googleAccessTokenClient, vertexClient);
    }

    @Test
    void complete_malformedFamilyResponsesAreSanitized() {
        when(googleAccessTokenClient.accessToken(
                any(AiCredentials.class), any(AiRequestDeadline.class))).thenReturn(ACCESS_TOKEN);
        when(vertexClient.complete(
                any(URI.class), eq(ACCESS_TOKEN), anyString(), any(AiRequestDeadline.class)))
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
        when(googleAccessTokenClient.accessToken(
                any(AiCredentials.class), any(AiRequestDeadline.class))).thenReturn(ACCESS_TOKEN);
        when(vertexClient.complete(
                any(URI.class), eq(ACCESS_TOKEN), anyString(), any(AiRequestDeadline.class)))
                .thenThrow(new IllegalStateException(SERVICE_ACCOUNT_JSON + PRIVATE_KEY + ACCESS_TOKEN
                        + PROMPT + "SENSITIVE_RESPONSE_BODY"));
        AiCompletionRequest request = new AiCompletionRequest(
                target("gemini-2.5-flash"),
                AiCredentials.of(Map.of("serviceAccountJson", SERVICE_ACCOUNT_JSON + PRIVATE_KEY)),
                PROMPT,
                List.of(new AiMessage("user", PROMPT)),
                List.of(),
                AiOutputMode.TEXT,
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
        return request(modelId, systemPrompt, AiOutputMode.TEXT);
    }

    private static AiCompletionRequest request(
            String modelId, String systemPrompt, AiOutputMode outputMode) {
        return new AiCompletionRequest(
                target(modelId),
                credentials(),
                systemPrompt,
                List.of(
                        new AiMessage("user", "Hello?"),
                        new AiMessage("assistant", "Hello.")),
                List.of(),
                outputMode,
                64,
                0.25);
    }

    private static AiCompletionRequest requestWithTarget(AiProviderTarget target) {
        return new AiCompletionRequest(
                target,
                credentials(),
                null,
                List.of(new AiMessage("user", "Hello?")),
                List.of(),
                AiOutputMode.TEXT,
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

    private static AiProviderAttemptExecutor rejectingCheckpoint() {
        return new AiProviderAttemptExecutor() {
            @Override
            public String execute(Supplier<String> attempt) {
                return attempt.get();
            }

            @Override
            public void checkpoint() {
                throw new AiProviderException("provider snapshot changed");
            }
        };
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
