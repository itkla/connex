package ooo.klae.connex.backend.ai.provider.azure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import ooo.klae.connex.backend.ai.provider.AiInputImage;
import ooo.klae.connex.backend.ai.provider.AiMessage;
import ooo.klae.connex.backend.ai.provider.AiOutputMode;
import ooo.klae.connex.backend.ai.provider.AiProviderException;
import ooo.klae.connex.backend.ai.provider.AiProviderTarget;
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
        adapter = new AzureOpenAiAdapter(azureOpenAiClient, objectMapper);
    }

    @Test
    void providerId_registersAzureOpenAiAdapter() {
        assertEquals("azure_openai", adapter.providerId());
    }

    @Test
    void complete_buildsChatCompletionRequestAndParsesResponse() throws Exception {
        when(azureOpenAiClient.complete(any(URI.class), any(AiCredentials.class), anyString()))
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
        verify(azureOpenAiClient).complete(endpointCaptor.capture(), any(AiCredentials.class), bodyCaptor.capture());
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
        assertFalse(body.has("response_format"));
        assertEquals("Hello world", result.text());
        assertEquals(12, result.inputTokens());
        assertEquals(3, result.outputTokens());
        assertEquals("stop", result.stopReason());
    }

    @Test
    void completeEmbedsImageBytesInTheFirstUserTurn() throws Exception {
        when(azureOpenAiClient.complete(any(URI.class), any(AiCredentials.class), anyString()))
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
        verify(azureOpenAiClient).complete(any(URI.class), any(AiCredentials.class), bodyCaptor.capture());
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
        when(azureOpenAiClient.complete(any(URI.class), any(AiCredentials.class), anyString()))
                .thenReturn(validResponse());

        adapter.complete(validRequest("https://connex.openai.azure.com", " "));

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(azureOpenAiClient).complete(any(URI.class), any(AiCredentials.class), bodyCaptor.capture());
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
            when(azureOpenAiClient.complete(any(URI.class), any(AiCredentials.class), anyString()))
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
        when(azureOpenAiClient.complete(any(URI.class), any(AiCredentials.class), anyString()))
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
