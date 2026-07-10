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
class BedrockAnthropicAdapterTest {
    @Mock private BedrockClient bedrockClient;

    private ObjectMapper objectMapper;
    private BedrockAnthropicAdapter adapter;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        adapter = new BedrockAnthropicAdapter(bedrockClient, objectMapper);
    }

    @Test
    void complete_buildsAnthropicRequestAndParsesResponse() throws Exception {
        when(bedrockClient.invokeModel(eq(BedrockRegion.US_EAST_1), eq("anthropic.claude-3-sonnet-v1:0"),
                any(AiCredentials.class), anyString()))
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

        AiCompletionResult result = adapter.complete(validRequest("Use short answers"));

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(bedrockClient).invokeModel(eq(BedrockRegion.US_EAST_1), eq("anthropic.claude-3-sonnet-v1:0"),
                any(AiCredentials.class), bodyCaptor.capture());
        JsonNode body = objectMapper.readTree(bodyCaptor.getValue());
        assertEquals("bedrock-2023-05-31", body.path("anthropic_version").asString());
        assertEquals(64, body.path("max_tokens").asInt());
        assertEquals(0.25, body.path("temperature").asDouble());
        assertEquals("Use short answers", body.path("system").asString());
        assertEquals("user", body.path("messages").path(0).path("role").asString());
        assertEquals("Hello?", body.path("messages").path(0).path("content").asString());
        assertEquals("assistant", body.path("messages").path(1).path("role").asString());
        assertEquals("Hello.", body.path("messages").path(1).path("content").asString());
        assertEquals("Hello world", result.text());
        assertEquals(12, result.inputTokens());
        assertEquals(3, result.outputTokens());
        assertEquals("end_turn", result.stopReason());
    }

    @Test
    void complete_omitsBlankSystemPrompt() throws Exception {
        when(bedrockClient.invokeModel(eq(BedrockRegion.US_EAST_1), eq("anthropic.claude-3-sonnet-v1:0"),
                any(AiCredentials.class), anyString()))
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
                any(AiCredentials.class), bodyCaptor.capture());
        assertFalse(objectMapper.readTree(bodyCaptor.getValue()).has("system"));
    }

    @Test
    void complete_nonBedrockTargetRaisesProviderException() {
        AiCompletionRequest request = new AiCompletionRequest(
                new AiProviderTarget("azure_openai", "us-east-1", "model"),
                credentials(),
                null,
                List.of(new AiMessage("user", "Hello?")),
                64,
                0.25);

        assertThrows(AiProviderException.class, () -> adapter.complete(request));
        verifyNoInteractions(bedrockClient);
    }

    @Test
    void complete_malformedResponseRaisesProviderException() {
        when(bedrockClient.invokeModel(eq(BedrockRegion.US_EAST_1), eq("anthropic.claude-3-sonnet-v1:0"),
                any(AiCredentials.class), anyString()))
                .thenReturn("{}");

        assertThrows(AiProviderException.class, () -> adapter.complete(validRequest(null)));
    }

    private static AiCompletionRequest validRequest(String systemPrompt) {
        return new AiCompletionRequest(
                new AiProviderTarget("bedrock", "us-east-1", "anthropic.claude-3-sonnet-v1:0"),
                credentials(),
                systemPrompt,
                List.of(
                        new AiMessage("user", "Hello?"),
                        new AiMessage("assistant", "Hello.")),
                64,
                0.25);
    }

    private static AiCredentials credentials() {
        return new AiCredentials("AKIDEXAMPLE", "SECRET", null);
    }
}
