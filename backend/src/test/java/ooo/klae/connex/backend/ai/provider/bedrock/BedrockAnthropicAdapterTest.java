package ooo.klae.connex.backend.ai.provider.bedrock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

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
import ooo.klae.connex.backend.ai.provider.AiProviderTarget;
import ooo.klae.connex.backend.ai.provider.AiReasoningMode;
import ooo.klae.connex.backend.ai.provider.AiResponseSchema;
import ooo.klae.connex.backend.ai.provider.AiStructuredOutputEnforcement;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class BedrockAnthropicAdapterTest {
    @Mock private BedrockClient bedrockClient;

    private ObjectMapper objectMapper;
    private BedrockAnthropicAdapter adapter;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        adapter = new BedrockAnthropicAdapter(
                bedrockClient, objectMapper, new AiProperties());
    }

    @Test
    void complete_buildsAnthropicRequestAndParsesResponse() throws Exception {
        assertEquals("bedrock", adapter.providerId());
        when(bedrockClient.invokeModel(eq(BedrockRegion.US_EAST_1), eq("anthropic.claude-3-sonnet-v1:0"),
                any(AiCredentials.class), anyString(), any(AiRequestDeadline.class)))
                .thenReturn("""
                        {
                          "content": [
                            { "type": "text", "text": "Hello" },
                            { "type": "text", "text": " world" }
                          ],
                          "usage": { "input_tokens": 12, "output_tokens": 3 },
                          "stop_reason": "end_turn"
                        }
                        """);

        AiCompletionResult result = adapter.complete(validRequest("Use short answers", AiOutputMode.JSON));

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(bedrockClient).invokeModel(eq(BedrockRegion.US_EAST_1), eq("anthropic.claude-3-sonnet-v1:0"),
                any(AiCredentials.class), bodyCaptor.capture(), any(AiRequestDeadline.class));
        JsonNode body = objectMapper.readTree(bodyCaptor.getValue());
        assertEquals("bedrock-2023-05-31", body.path("anthropic_version").asString());
        assertEquals(64, body.path("max_tokens").asInt());
        assertEquals(0.25, body.path("temperature").asDouble());
        assertEquals("Use short answers", body.path("system").asString());
        assertEquals("user", body.path("messages").path(0).path("role").asString());
        assertEquals("Hello?", body.path("messages").path(0).path("content").asString());
        assertEquals("assistant", body.path("messages").path(1).path("role").asString());
        assertEquals("Hello.", body.path("messages").path(1).path("content").asString());
        assertFalse(body.has("response_format"));
        assertFalse(body.has("responseMimeType"));
        assertEquals("Hello world", result.text());
        assertEquals(12, result.inputTokens());
        assertEquals(3, result.outputTokens());
        assertEquals("end_turn", result.stopReason());
    }

    @Test
    void contextWindowUsesSafeDefaultForUnknownModelFamilies() {
        assertEquals(200_000, adapter.contextWindowTokens(new AiProviderTarget(
                "bedrock", "us-east-1", "anthropic.claude-3-sonnet-v1:0",
                null, null, null, null, false)));
        assertEquals(4_096, adapter.contextWindowTokens(new AiProviderTarget(
                "bedrock", "us-east-1", "third-party.unknown-model",
                null, null, null, null, false)));
    }

    @Test
    void complete_omitsBlankSystemPrompt() throws Exception {
        when(bedrockClient.invokeModel(eq(BedrockRegion.US_EAST_1), eq("anthropic.claude-3-sonnet-v1:0"),
                any(AiCredentials.class), anyString(), any(AiRequestDeadline.class)))
                .thenReturn("""
                        {
                          "content": [{ "type": "text", "text": "Done" }],
                          "usage": { "input_tokens": 1, "output_tokens": 1 },
                          "stop_reason": "end_turn"
                        }
                        """);

        adapter.complete(validRequest(" "));

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(bedrockClient).invokeModel(eq(BedrockRegion.US_EAST_1), eq("anthropic.claude-3-sonnet-v1:0"),
                any(AiCredentials.class), bodyCaptor.capture(), any(AiRequestDeadline.class));
        assertFalse(objectMapper.readTree(bodyCaptor.getValue()).has("system"));
    }

    @Test
    void complete_supportedClaudeModelSendsNativeJsonSchema() throws Exception {
        String modelId = "anthropic.claude-sonnet-4-5-20250929-v1:0";
        when(bedrockClient.invokeModel(
                eq(BedrockRegion.US_EAST_1), eq(modelId),
                any(AiCredentials.class), anyString(), any(AiRequestDeadline.class)))
                .thenReturn("""
                        {
                          "content": [{ "type": "text", "text": "{}" }],
                          "usage": { "input_tokens": 1, "output_tokens": 1 },
                          "stop_reason": "end_turn"
                        }
                        """);
        AiCompletionRequest request = new AiCompletionRequest(
                new AiProviderTarget("bedrock", "us-east-1", modelId,
                        null, null, null, null, false),
                credentials(),
                "Return JSON",
                List.of(new AiMessage("user", "Hello?")),
                List.of(),
                AiOutputMode.JSON,
                new AiResponseSchema("assistant_step",
                        objectMapper.readTree("{\"type\":\"object\"}")),
                64,
                0.25);

        AiCompletionResult result = adapter.complete(request);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(bedrockClient).invokeModel(
                eq(BedrockRegion.US_EAST_1), eq(modelId),
                any(AiCredentials.class), bodyCaptor.capture(), any(AiRequestDeadline.class));
        JsonNode format = objectMapper.readTree(bodyCaptor.getValue())
                .path("output_config").path("format");
        assertEquals("json_schema", format.path("type").asString());
        assertEquals("object", format.path("schema").path("type").asString());
        assertEquals(AiStructuredOutputEnforcement.JSON_SCHEMA,
                result.structuredOutputEnforcement());
    }

    @Test
    void complete_nativeReasoningUsesThinkingChannelAndParsesItSeparately() throws Exception {
        String modelId = "anthropic.claude-sonnet-4-5-20250929-v1:0";
        when(bedrockClient.invokeModel(
                eq(BedrockRegion.US_EAST_1), eq(modelId),
                any(AiCredentials.class), anyString(), any(AiRequestDeadline.class)))
                .thenReturn("""
                        {
                          "content": [
                            {"type":"thinking","thinking":"Compare the authorized signals."},
                            {"type":"text","text":"{\\\"final\\\":\\\"done\\\"}"}
                          ],
                          "usage": {"input_tokens":12,"output_tokens":33},
                          "stop_reason":"end_turn"
                        }
                        """);
        AiCompletionRequest request = new AiCompletionRequest(
                new AiProviderTarget("bedrock", "us-east-1", modelId,
                        null, null, null, null, false),
                credentials(),
                "Return JSON",
                List.of(new AiMessage("user", "Hello?")),
                List.of(),
                AiOutputMode.JSON,
                new AiResponseSchema("assistant_step",
                        objectMapper.readTree("{\"type\":\"object\"}")),
                AiReasoningMode.NATIVE,
                ooo.klae.connex.backend.ai.provider.AiProviderAttemptExecutor.DIRECT,
                2_048,
                0.25);

        AiCompletionResult result = adapter.complete(request);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(bedrockClient).invokeModel(
                eq(BedrockRegion.US_EAST_1), eq(modelId),
                any(AiCredentials.class), bodyCaptor.capture(), any(AiRequestDeadline.class));
        JsonNode body = objectMapper.readTree(bodyCaptor.getValue());
        assertEquals("enabled", body.path("thinking").path("type").asString());
        assertEquals(1_024, body.path("thinking").path("budget_tokens").asInt());
        assertFalse(body.has("temperature"));
        assertFalse(body.has("output_config"));
        assertEquals("Compare the authorized signals.", result.reasoning());
        assertEquals("{\"final\":\"done\"}", result.text());
        assertEquals(AiReasoningMode.NATIVE, result.reasoningMode());
        assertEquals(33, result.outputTokens());
    }

    @Test
    void complete_nonBedrockTargetRaisesProviderException() {
        AiCompletionRequest request = new AiCompletionRequest(
                new AiProviderTarget("azure_openai", null, "model",
                        "https://resource.openai.azure.com", "2025-01-01", "deployment", null, false),
                credentials(),
                null,
                List.of(new AiMessage("user", "Hello?")),
                List.of(),
                AiOutputMode.TEXT,
                64,
                0.25);

        assertThrows(AiProviderException.class, () -> adapter.complete(request));
        verifyNoInteractions(bedrockClient);
    }

    @Test
    void complete_malformedResponseRaisesProviderException() {
        when(bedrockClient.invokeModel(eq(BedrockRegion.US_EAST_1), eq("anthropic.claude-3-sonnet-v1:0"),
                any(AiCredentials.class), anyString(), any(AiRequestDeadline.class)))
                .thenReturn("{}");

        assertThrows(AiProviderException.class, () -> adapter.complete(validRequest(null)));
    }

    private static AiCompletionRequest validRequest(String systemPrompt) {
        return validRequest(systemPrompt, AiOutputMode.TEXT);
    }

    private static AiCompletionRequest validRequest(String systemPrompt, AiOutputMode outputMode) {
        return new AiCompletionRequest(
                new AiProviderTarget("bedrock", "us-east-1", "anthropic.claude-3-sonnet-v1:0",
                        null, null, null, null, false),
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

    private static AiCredentials credentials() {
        return AiCredentials.of(Map.of(
                "accessKeyId", "AKIDEXAMPLE",
                "secretAccessKey", "SECRET"));
    }
}
