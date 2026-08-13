package ooo.klae.connex.backend.ai.provider.azure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
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

import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.egress.AiRequestDeadline;
import ooo.klae.connex.backend.ai.provider.AiCompletionRequest;
import ooo.klae.connex.backend.ai.provider.AiCompletionResult;
import ooo.klae.connex.backend.ai.provider.AiCredentials;
import ooo.klae.connex.backend.ai.provider.AiInputImage;
import ooo.klae.connex.backend.ai.provider.AiMessage;
import ooo.klae.connex.backend.ai.provider.AiOutputMode;
import ooo.klae.connex.backend.ai.provider.AiProviderAttemptExecutor;
import ooo.klae.connex.backend.ai.provider.AiProviderException;
import ooo.klae.connex.backend.ai.provider.AiProviderRequestRejectedException;
import ooo.klae.connex.backend.ai.provider.AiProviderTarget;
import ooo.klae.connex.backend.ai.provider.AiProviderStreamObserver;
import ooo.klae.connex.backend.ai.provider.openai.OpenAiSseAccumulator;
import ooo.klae.connex.backend.ai.provider.AiReasoningMode;
import ooo.klae.connex.backend.ai.provider.AiResponseSchema;
import ooo.klae.connex.backend.ai.provider.AiStructuredOutputEnforcement;
import ooo.klae.connex.backend.ai.provider.AiToolCallingMode;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class AzureOpenAiAdapterTest {
    private static final String API_KEY = "azure_api_key_1234";

    @Mock private AzureOpenAiClient azureOpenAiClient;

    private ObjectMapper objectMapper;
    private AzureOpenAiAdapter adapter;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        adapter = new AzureOpenAiAdapter(azureOpenAiClient, objectMapper, new AiProperties());
    }

    @Test
    void providerId_registersAzureOpenAiAdapter() {
        assertEquals("azure_openai", adapter.providerId());
        assertEquals(AiToolCallingMode.NONE, adapter.toolCallingCapability(null));
    }

    @Test
    void complete_buildsChatCompletionRequestAndParsesResponse() throws Exception {
        when(azureOpenAiClient.complete(any(URI.class), any(AiCredentials.class), anyString(), any(AiRequestDeadline.class)))
                .thenReturn("""
                        {
                          "choices": [{
                            "message": { "content": "Hello world" },
                            "finish_reason": "stop"
                          }],
                          "usage": { "prompt_tokens": 12, "completion_tokens": 3 }
                        }
                        """);

        AiCompletionResult result = adapter.complete(validRequest(
                "https://CONNEX.openai.azure.com/admin/path?api-version=ignored",
                "Use short answers",
                AiOutputMode.JSON));

        ArgumentCaptor<URI> endpointCaptor = ArgumentCaptor.forClass(URI.class);
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(azureOpenAiClient).complete(
                endpointCaptor.capture(), any(AiCredentials.class), bodyCaptor.capture(),
                any(AiRequestDeadline.class));
        assertEquals("https://connex.openai.azure.com/openai/deployments/contacts-prod/chat/completions"
                + "?api-version=2025-01-01-preview", endpointCaptor.getValue().toString());
        JsonNode body = objectMapper.readTree(bodyCaptor.getValue());
        assertEquals("system", body.path("messages").path(0).path("role").asString());
        assertEquals("Use short answers", body.path("messages").path(0).path("content").asString());
        assertEquals("user", body.path("messages").path(1).path("role").asString());
        assertEquals("Hello?", body.path("messages").path(1).path("content").asString());
        assertEquals("assistant", body.path("messages").path(2).path("role").asString());
        assertEquals("Hello.", body.path("messages").path(2).path("content").asString());
        assertEquals(64, body.path("max_completion_tokens").asInt());
        assertEquals(0.25, body.path("temperature").asDouble());
        assertEquals("json_object", body.path("response_format").path("type").asString());
        assertEquals("Hello world", result.text());
        assertEquals(12, result.inputTokens());
        assertEquals(3, result.outputTokens());
        assertEquals("stop", result.stopReason());
        assertEquals(AiStructuredOutputEnforcement.JSON_OBJECT,
                result.structuredOutputEnforcement());
    }

    @Test
    void complete_sendsStrictJsonSchemaResponseFormat() throws Exception {
        when(azureOpenAiClient.complete(any(URI.class), any(AiCredentials.class), anyString(), any(AiRequestDeadline.class)))
                .thenReturn(validResponse());
        AiCompletionRequest request = new AiCompletionRequest(
                new AiProviderTarget("azure_openai", null, "gpt-5.2",
                        "https://connex.openai.azure.com",
                        "2025-01-01-preview", "contacts-prod", null, false),
                credentials(),
                "Return one step",
                List.of(new AiMessage("user", "Hello?")),
                List.of(),
                AiOutputMode.JSON,
                new AiResponseSchema("assistant_step",
                        objectMapper.readTree("{\"type\":\"object\"}")),
                64,
                0.25);

        AiCompletionResult result = adapter.complete(request);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(azureOpenAiClient).complete(
                any(URI.class), any(AiCredentials.class), bodyCaptor.capture(),
                any(AiRequestDeadline.class));
        JsonNode responseFormat = objectMapper.readTree(bodyCaptor.getValue()).path("response_format");
        assertEquals("json_schema", responseFormat.path("type").asString());
        assertEquals("assistant_step", responseFormat.path("json_schema").path("name").asString());
        assertEquals(true, responseFormat.path("json_schema").path("strict").asBoolean());
        assertEquals("object", responseFormat.path("json_schema").path("schema")
                .path("type").asString());
        assertEquals(AiStructuredOutputEnforcement.JSON_SCHEMA,
                result.structuredOutputEnforcement());
    }

    @Test
    void completeStreamingRequestsUsageSseAndReturnsNormalizedResult() throws Exception {
        AiCompletionResult streamed = new AiCompletionResult(
                "{\"final\":{\"text\":\"Hello\"}}", 3, 2, "stop",
                AiStructuredOutputEnforcement.JSON_OBJECT);
        when(azureOpenAiClient.stream(
                any(URI.class), any(AiCredentials.class), anyString(),
                any(AiRequestDeadline.class), any(OpenAiSseAccumulator.class),
                any(AiProviderStreamObserver.class))).thenReturn(streamed);
        AiProviderStreamObserver observer = text -> {
        };

        AiCompletionResult result = adapter.completeStreaming(
                validRequest(
                        "https://connex.openai.azure.com", "system", AiOutputMode.JSON),
                observer);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(azureOpenAiClient).stream(
                any(URI.class), any(AiCredentials.class), bodyCaptor.capture(),
                any(AiRequestDeadline.class), any(OpenAiSseAccumulator.class),
                org.mockito.ArgumentMatchers.same(observer));
        JsonNode body = objectMapper.readTree(bodyCaptor.getValue());
        assertEquals(true, body.path("stream").asBoolean());
        assertEquals(true, body.path("stream_options").path("include_usage").asBoolean());
        assertSame(streamed, result);
    }

    @Test
    void complete_taggedReasoningHonestlyUsesPromptOnlyStructuredEnforcement() throws Exception {
        when(azureOpenAiClient.complete(
                any(URI.class), any(AiCredentials.class), anyString(), any(AiRequestDeadline.class)))
                .thenReturn(validResponse());
        AiProviderTarget target = new AiProviderTarget(
                "azure_openai", null, "gpt-5.2",
                "https://connex.openai.azure.com",
                "2025-01-01-preview", "contacts-prod", null, false);
        AiCompletionRequest request = new AiCompletionRequest(
                target,
                credentials(),
                "Return one step",
                List.of(new AiMessage("user", "Hello?")),
                List.of(),
                AiOutputMode.JSON,
                new AiResponseSchema("assistant_step",
                        objectMapper.readTree("{\"type\":\"object\"}")),
                AiReasoningMode.TAGGED,
                AiProviderAttemptExecutor.DIRECT,
                64,
                0.25);

        AiCompletionResult result = adapter.complete(request);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(azureOpenAiClient).complete(
                any(URI.class), any(AiCredentials.class), bodyCaptor.capture(),
                any(AiRequestDeadline.class));
        JsonNode body = objectMapper.readTree(bodyCaptor.getValue());
        assertFalse(body.has("response_format"));
        assertEquals(AiReasoningMode.TAGGED, adapter.reasoningCapability(target));
        assertEquals(AiReasoningMode.TAGGED, result.reasoningMode());
        assertEquals(AiStructuredOutputEnforcement.PROMPT_ONLY,
                result.structuredOutputEnforcement());
    }

    @Test
    void outputCapacityUsesDocumentedAzureModelFamilyLimits() {
        assertEquals(128_000, adapter.maxOutputTokens(target("gpt-5.2")));
        assertEquals(100_000, adapter.maxOutputTokens(target("o4-mini")));
        assertEquals(32_768, adapter.maxOutputTokens(target("gpt-4.1")));
        assertEquals(16_384, adapter.maxOutputTokens(target("gpt-4o")));
        assertEquals(4_096, adapter.maxOutputTokens(target("custom-deployment")));
    }

    @Test
    void complete_degradesRejectedSchemaToJsonObjectThenPromptOnly() throws Exception {
        when(azureOpenAiClient.complete(any(URI.class), any(AiCredentials.class), anyString(), any(AiRequestDeadline.class)))
                .thenThrow(new AiProviderRequestRejectedException("Azure OpenAI", 400))
                .thenThrow(new AiProviderRequestRejectedException("Azure OpenAI", 422))
                .thenReturn(validResponse());
        AiCompletionRequest request = new AiCompletionRequest(
                new AiProviderTarget("azure_openai", null, "gpt-5.2",
                        "https://connex.openai.azure.com",
                        "2025-01-01-preview", "contacts-prod", null, false),
                credentials(),
                "Return one step",
                List.of(new AiMessage("user", "Hello?")),
                List.of(),
                AiOutputMode.JSON,
                new AiResponseSchema("assistant_step",
                        objectMapper.readTree("{\"type\":\"object\"}")),
                64,
                0.25);

        AiCompletionResult result = adapter.complete(request);

        ArgumentCaptor<String> bodies = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<AiRequestDeadline> deadlines = ArgumentCaptor.forClass(AiRequestDeadline.class);
        verify(azureOpenAiClient, times(3)).complete(
                any(URI.class), any(AiCredentials.class), bodies.capture(), deadlines.capture());
        assertEquals("json_schema", objectMapper.readTree(bodies.getAllValues().get(0))
                .path("response_format").path("type").asString());
        assertEquals("json_object", objectMapper.readTree(bodies.getAllValues().get(1))
                .path("response_format").path("type").asString());
        assertFalse(objectMapper.readTree(bodies.getAllValues().get(2)).has("response_format"));
        assertSame(deadlines.getAllValues().get(0), deadlines.getAllValues().get(1));
        assertSame(deadlines.getAllValues().get(0), deadlines.getAllValues().get(2));
        assertEquals(AiStructuredOutputEnforcement.PROMPT_ONLY,
                result.structuredOutputEnforcement());
    }

    @Test
    void completeEmbedsImageBytesInTheFirstUserTurn() throws Exception {
        when(azureOpenAiClient.complete(any(URI.class), any(AiCredentials.class), anyString(), any(AiRequestDeadline.class)))
                .thenReturn(validResponse());
        AiCompletionRequest request = new AiCompletionRequest(
                new AiProviderTarget("azure_openai", null, "gpt-5.2",
                        "https://connex.openai.azure.com",
                        "2025-01-01-preview", "contacts-prod", null, false),
                credentials(),
                "Extract literal fields",
                List.of(new AiMessage("user", "Read the card")),
                List.of(image()),
                AiOutputMode.TEXT,
                64,
                0);

        adapter.complete(request);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(azureOpenAiClient).complete(
                any(URI.class), any(AiCredentials.class), bodyCaptor.capture(),
                any(AiRequestDeadline.class));
        JsonNode content = objectMapper.readTree(bodyCaptor.getValue())
                .path("messages").path(1).path("content");
        assertEquals("text", content.path(0).path("type").asString());
        assertEquals("Read the card", content.path(0).path("text").asString());
        assertEquals("image_url", content.path(1).path("type").asString());
        assertEquals("data:image/jpeg;base64,/9j/AQ==",
                content.path(1).path("image_url").path("url").asString());
        assertEquals("high", content.path(1).path("image_url").path("detail").asString());
        JsonNode body = objectMapper.readTree(bodyCaptor.getValue());
        assertEquals(64, body.path("max_completion_tokens").asInt());
        assertFalse(body.has("temperature"));
    }

    @Test
    void complete_omitsBlankSystemPrompt() throws Exception {
        when(azureOpenAiClient.complete(any(URI.class), any(AiCredentials.class), anyString(), any(AiRequestDeadline.class)))
                .thenReturn(validResponse());

        adapter.complete(validRequest("https://connex.openai.azure.com", " "));

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(azureOpenAiClient).complete(
                any(URI.class), any(AiCredentials.class), bodyCaptor.capture(),
                any(AiRequestDeadline.class));
        JsonNode messages = objectMapper.readTree(bodyCaptor.getValue()).path("messages");
        assertEquals(2, messages.size());
        assertEquals("user", messages.path(0).path("role").asString());
    }

    @Test
    void complete_malformedResponsesRaiseSanitizedException() {
        for (String responseBody : List.of(
                "{\"response\":\"SENSITIVE_RESPONSE_BODY\"",
                "{}",
                "{\"choices\":[]}",
                "{\"choices\":[{\"message\":{\"content\":3},\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}",
                "{\"choices\":[{\"message\":{\"content\":\"SENSITIVE_RESPONSE_BODY\"},"
                        + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":-1,"
                        + "\"completion_tokens\":1}}",
                "{\"choices\":[{\"message\":{\"content\":\"SENSITIVE_RESPONSE_BODY\"}}],"
                        + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}")) {
            when(azureOpenAiClient.complete(any(URI.class), any(AiCredentials.class), anyString(), any(AiRequestDeadline.class)))
                    .thenReturn(responseBody);

            AiProviderException exception = assertThrows(AiProviderException.class,
                    () -> adapter.complete(validRequest("https://connex.openai.azure.com", null)));

            assertEquals("Azure OpenAI response was invalid", exception.getMessage());
            assertFalse(String.valueOf(exception).contains("SENSITIVE_RESPONSE_BODY"));
            assertNull(exception.getCause());
        }
    }

    @Test
    void complete_rejectsNonAzureHostsBeforeSend() {
        for (String endpoint : List.of(
                "https://connex.openai.azure.com.evil.test",
                "https://openai.azure.com",
                "https://evil.test")) {
            assertThrows(AiProviderException.class, () -> adapter.complete(validRequest(endpoint, null)));
        }
        verifyNoInteractions(azureOpenAiClient);
    }

    @Test
    void complete_revalidatesDeploymentAndApiVersionBeforeSend() {
        AiCompletionRequest invalidDeployment = requestWithTarget(new AiProviderTarget(
                "azure_openai", null, "gpt-5.2", "https://connex.openai.azure.com",
                "2025-01-01-preview", "contacts/prod", null, false));
        AiCompletionRequest invalidApiVersion = requestWithTarget(new AiProviderTarget(
                "azure_openai", null, "gpt-5.2", "https://connex.openai.azure.com",
                "2025-01-01-preview&other=value", "contacts-prod", null, false));

        assertThrows(AiProviderException.class, () -> adapter.complete(invalidDeployment));
        assertThrows(AiProviderException.class, () -> adapter.complete(invalidApiVersion));
        verifyNoInteractions(azureOpenAiClient);
    }

    @Test
    void complete_neverExposesApiKeyInToStringOrException() {
        when(azureOpenAiClient.complete(any(URI.class), any(AiCredentials.class), anyString(), any(AiRequestDeadline.class)))
                .thenThrow(new IllegalStateException("transport rejected " + API_KEY));
        AiCompletionRequest request = validRequest("https://connex.openai.azure.com", null);

        AiProviderException exception = assertThrows(AiProviderException.class, () -> adapter.complete(request));

        assertFalse(request.credentials().toString().contains(API_KEY));
        assertFalse(request.toString().contains(API_KEY));
        assertFalse(String.valueOf(exception).contains(API_KEY));
        assertNull(exception.getCause());
    }

    @Test
    void complete_nonAzureTargetRaisesProviderExceptionBeforeSend() {
        AiCompletionRequest request = requestWithTarget(new AiProviderTarget(
                "bedrock", "us-east-1", "model", null, null, null, null, false));

        assertThrows(AiProviderException.class, () -> adapter.complete(request));
        verifyNoInteractions(azureOpenAiClient);
    }

    private static AiCompletionRequest validRequest(String endpoint, String systemPrompt) {
        return validRequest(endpoint, systemPrompt, AiOutputMode.TEXT);
    }

    private static AiCompletionRequest validRequest(
            String endpoint, String systemPrompt, AiOutputMode outputMode) {
        return new AiCompletionRequest(
                new AiProviderTarget("azure_openai", null, "gpt-4o", endpoint,
                        "2025-01-01-preview", "contacts-prod", null, false),
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

    private static AiProviderTarget target(String modelId) {
        return new AiProviderTarget(
                "azure_openai", null, modelId, "https://connex.openai.azure.com",
                "2025-01-01-preview", "contacts-prod", null, false);
    }

    private static AiInputImage image() {
        return new AiInputImage(
                "image/jpeg", new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1}, 100, 50);
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

    private static AiCredentials credentials() {
        return AiCredentials.of(Map.of("apiKey", API_KEY));
    }

    private static String validResponse() {
        return """
                {
                  "choices": [{
                    "message": { "content": "Done" },
                    "finish_reason": "stop"
                  }],
                  "usage": { "prompt_tokens": 1, "completion_tokens": 1 }
                }
                """;
    }
}
