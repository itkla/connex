package ooo.klae.connex.backend.ai.provider.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

import ooo.klae.connex.backend.ai.provider.AiCompletionRequest;
import ooo.klae.connex.backend.ai.provider.AiCompletionResult;
import ooo.klae.connex.backend.ai.provider.AiCredentials;
import ooo.klae.connex.backend.ai.provider.AiMessage;
import ooo.klae.connex.backend.ai.provider.AiProviderException;
import ooo.klae.connex.backend.ai.provider.AiProviderTarget;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class OpenAiCompatibleAdapterTest {
    private static final String API_KEY = "openai_compatible_api_key_secret";
    private static final String PROMPT = "PROMPT_TEXT_SECRET";

    @Mock private OpenAiCompatibleClient openAiCompatibleClient;

    private ObjectMapper objectMapper;
    private OpenAiCompatibleAdapter adapter;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        adapter = new OpenAiCompatibleAdapter(openAiCompatibleClient, objectMapper);
    }

    @Test
    void providerId_registersOpenAiCompatibleAdapter() {
        assertEquals("openai_compatible", adapter.providerId());
    }

    @Test
    void complete_joinsBasePathsAndPreservesExplicitPort() {
        when(openAiCompatibleClient.complete(any(URI.class), anyBoolean(), any(AiCredentials.class), anyString()))
                .thenReturn(validResponse());

        for (String base : List.of(
                "https://api.example.test/v1",
                "https://api.example.test/v1/",
                "https://api.example.test",
                "https://api.example.test:8443/v1/")) {
            adapter.complete(request(base, false, null, credentials()));
        }

        ArgumentCaptor<URI> endpoints = ArgumentCaptor.forClass(URI.class);
        verify(openAiCompatibleClient, times(4)).complete(
                endpoints.capture(), anyBoolean(), any(AiCredentials.class), anyString());
        assertEquals(List.of(
                URI.create("https://api.example.test/v1/chat/completions"),
                URI.create("https://api.example.test/v1/chat/completions"),
                URI.create("https://api.example.test/chat/completions"),
                URI.create("https://api.example.test:8443/v1/chat/completions")),
                endpoints.getAllValues());
    }

    @Test
    void complete_buildsChatCompletionBodyAndParsesResponse() throws Exception {
        when(openAiCompatibleClient.complete(any(URI.class), anyBoolean(), any(AiCredentials.class), anyString()))
                .thenReturn("""
                        {
                          "choices": [{
                            "message": {"content": "Hello world"},
                            "finish_reason": "stop"
                          }],
                          "usage": {"prompt_tokens": 12, "completion_tokens": 3}
                        }
                        """);

        AiCompletionResult result = adapter.complete(request(
                "https://api.example.test/v1", false, "Use short answers", credentials()));

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(openAiCompatibleClient).complete(any(URI.class), anyBoolean(),
                any(AiCredentials.class), bodyCaptor.capture());
        JsonNode body = objectMapper.readTree(bodyCaptor.getValue());
        assertEquals("llama3.3:70b", body.path("model").asString());
        assertEquals("system", body.path("messages").path(0).path("role").asString());
        assertEquals("Use short answers", body.path("messages").path(0).path("content").asString());
        assertEquals("user", body.path("messages").path(1).path("role").asString());
        assertEquals("Hello?", body.path("messages").path(1).path("content").asString());
        assertEquals("assistant", body.path("messages").path(2).path("role").asString());
        assertEquals("Hello.", body.path("messages").path(2).path("content").asString());
        assertEquals(64, body.path("max_tokens").asInt());
        assertFalse(body.has("max_completion_tokens"));
        assertEquals(0.25, body.path("temperature").asDouble());
        assertEquals("Hello world", result.text());
        assertEquals(12, result.inputTokens());
        assertEquals(3, result.outputTokens());
        assertEquals("stop", result.stopReason());
        assertFalse(result.toString().contains("Hello world"));
    }

    @Test
    void complete_missingUsageReturnsZeroTokenCounts() {
        when(openAiCompatibleClient.complete(any(URI.class), anyBoolean(), any(AiCredentials.class), anyString()))
                .thenReturn("""
                        {
                          "choices": [{
                            "message": {"content": "Local result"},
                            "finish_reason": "stop"
                          }]
                        }
                        """);

        AiCompletionResult result = adapter.complete(request(
                "https://api.example.test/v1", false, null, credentials()));

        assertEquals("Local result", result.text());
        assertEquals(0, result.inputTokens());
        assertEquals(0, result.outputTokens());
        assertEquals("stop", result.stopReason());
    }

    @Test
    void complete_httpRequiresClampedInternalAllowanceBeforeClientCall() {
        AiCompletionRequest denied = request("http://localhost:11434/v1", false, null, emptyCredentials());

        assertThrows(AiProviderException.class, () -> adapter.complete(denied));
        verifyNoInteractions(openAiCompatibleClient);

        when(openAiCompatibleClient.complete(any(URI.class), anyBoolean(), any(AiCredentials.class), anyString()))
                .thenReturn(validResponse());
        adapter.complete(request("http://localhost:11434/v1", true, null, emptyCredentials()));

        verify(openAiCompatibleClient).complete(
                eq(URI.create("http://localhost:11434/v1/chat/completions")), eq(true),
                eq(emptyCredentials()), anyString());
    }

    @Test
    void complete_rejectsMalformedEndpointComponentsBeforeClientCall() {
        for (String endpoint : List.of(
                "ftp://api.example.test/v1",
                "https://user@api.example.test/v1",
                "https://api.example.test/v1#fragment",
                "https:///v1")) {
            assertThrows(AiProviderException.class,
                    () -> adapter.complete(request(endpoint, false, null, credentials())));
        }
        verifyNoInteractions(openAiCompatibleClient);
    }

    @Test
    void complete_malformedResponsesRaiseSanitizedException() {
        for (String responseBody : List.of(
                "{\"response\":\"SENSITIVE_RESPONSE_BODY\"",
                "{}",
                "{\"choices\":[]}",
                "{\"choices\":[{\"message\":{\"content\":3},\"finish_reason\":\"stop\"}]}",
                "{\"choices\":[{\"message\":{\"content\":\"SENSITIVE_RESPONSE_BODY\"}}]}")) {
            when(openAiCompatibleClient.complete(
                    any(URI.class), anyBoolean(), any(AiCredentials.class), anyString()))
                    .thenReturn(responseBody);

            AiProviderException exception = assertThrows(AiProviderException.class,
                    () -> adapter.complete(request(
                            "https://api.example.test/v1", false, null, credentials())));

            assertEquals("OpenAI-compatible response was invalid", exception.getMessage());
            assertFalse(String.valueOf(exception).contains("SENSITIVE_RESPONSE_BODY"));
            assertNull(exception.getCause());
        }
    }

    @Test
    void complete_arbitraryFinishReasonIsNormalizedToOther() {
        when(openAiCompatibleClient.complete(any(URI.class), anyBoolean(), any(AiCredentials.class), anyString()))
                .thenReturn("{\"choices\":[{\"message\":{\"content\":\"Local result\"},"
                        + "\"finish_reason\":\"sk-live-leaked-credential\"}],"
                        + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}");

        AiCompletionResult result = adapter.complete(request(
                "https://api.example.test/v1", false, null, credentials()));

        assertEquals("other", result.stopReason());
        assertFalse(result.toString().contains("sk-live-leaked-credential"));
    }

    @Test
    void complete_partialOrNegativeUsageDefaultsTokensToZero() {
        when(openAiCompatibleClient.complete(any(URI.class), anyBoolean(), any(AiCredentials.class), anyString()))
                .thenReturn("{\"choices\":[{\"message\":{\"content\":\"Local result\"},"
                        + "\"finish_reason\":\"stop\"}],\"usage\":{\"total_tokens\":9,\"prompt_tokens\":-1}}");

        AiCompletionResult result = adapter.complete(request(
                "https://api.example.test/v1", false, null, credentials()));

        assertEquals("Local result", result.text());
        assertEquals(0, result.inputTokens());
        assertEquals(0, result.outputTokens());
    }

    @Test
    void complete_neverExposesApiKeyPromptOrResponseInExceptionsOrToString() {
        when(openAiCompatibleClient.complete(any(URI.class), anyBoolean(), any(AiCredentials.class), anyString()))
                .thenThrow(new IllegalStateException(API_KEY + PROMPT + "SENSITIVE_RESPONSE_BODY"));
        AiCompletionRequest request = new AiCompletionRequest(
                target("https://api.example.test/v1", false),
                credentials(),
                PROMPT,
                List.of(new AiMessage("user", PROMPT)),
                64,
                0.25);

        AiProviderException exception = assertThrows(AiProviderException.class, () -> adapter.complete(request));

        assertEquals("OpenAI-compatible adapter failed", exception.getMessage());
        for (String secret : List.of(API_KEY, PROMPT, "SENSITIVE_RESPONSE_BODY")) {
            assertFalse(String.valueOf(exception).contains(secret));
            assertFalse(request.toString().contains(secret));
            assertFalse(request.credentials().toString().contains(secret));
            assertFalse(request.messages().get(0).toString().contains(secret));
        }
        assertNull(exception.getCause());
    }

    private static AiCompletionRequest request(String endpoint, boolean allowInternalEndpoint,
            String systemPrompt, AiCredentials credentials) {
        return new AiCompletionRequest(
                target(endpoint, allowInternalEndpoint),
                credentials,
                systemPrompt,
                List.of(
                        new AiMessage("user", "Hello?"),
                        new AiMessage("assistant", "Hello.")),
                64,
                0.25);
    }

    private static AiProviderTarget target(String endpoint, boolean allowInternalEndpoint) {
        return new AiProviderTarget(
                "openai_compatible", null, "llama3.3:70b", endpoint,
                null, null, null, allowInternalEndpoint);
    }

    private static AiCredentials credentials() {
        return AiCredentials.of(Map.of("apiKey", API_KEY));
    }

    private static AiCredentials emptyCredentials() {
        return AiCredentials.of(Map.of());
    }

    private static String validResponse() {
        return """
                {
                  "choices": [{
                    "message": {"content": "Done"},
                    "finish_reason": "stop"
                  }],
                  "usage": {"prompt_tokens": 1, "completion_tokens": 1}
                }
                """;
    }
}
