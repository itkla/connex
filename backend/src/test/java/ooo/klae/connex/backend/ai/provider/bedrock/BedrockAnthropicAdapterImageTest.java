package ooo.klae.connex.backend.ai.provider.bedrock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.ai.provider.AiCompletionRequest;
import ooo.klae.connex.backend.ai.provider.AiCredentials;
import ooo.klae.connex.backend.ai.provider.AiInputImage;
import ooo.klae.connex.backend.ai.provider.AiMessage;
import ooo.klae.connex.backend.ai.provider.AiOutputMode;
import ooo.klae.connex.backend.ai.provider.AiProviderTarget;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class BedrockAnthropicAdapterImageTest {
    private static final String MODEL_ID = "anthropic.claude-3-sonnet-v1:0";

    @Mock private BedrockClient bedrockClient;

    private ObjectMapper objectMapper;
    private BedrockAnthropicAdapter adapter;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        adapter = new BedrockAnthropicAdapter(bedrockClient, objectMapper);
    }

    @Test
    void completeEmbedsBase64ImageBlockBeforeUserText() throws Exception {
        when(bedrockClient.invokeModel(
                any(BedrockRegion.class), any(), any(AiCredentials.class), any()))
                .thenReturn("""
                        {"content":[{"type":"text","text":"{}"}],
                         "usage":{"input_tokens":1,"output_tokens":1},"stop_reason":"end_turn"}
                        """);

        adapter.complete(request());

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(bedrockClient).invokeModel(
                eq(BedrockRegion.US_EAST_1), eq(MODEL_ID), any(AiCredentials.class), bodyCaptor.capture());
        JsonNode content = objectMapper.readTree(bodyCaptor.getValue())
                .path("messages").path(0).path("content");
        assertEquals("image", content.path(0).path("type").asString());
        assertEquals("base64", content.path(0).path("source").path("type").asString());
        assertEquals("image/jpeg", content.path(0).path("source").path("media_type").asString());
        assertEquals("/9j/AQ==", content.path(0).path("source").path("data").asString());
        assertEquals("text", content.path(1).path("type").asString());
        assertEquals("Read the card", content.path(1).path("text").asString());
    }

    private static AiCompletionRequest request() {
        return new AiCompletionRequest(
                new AiProviderTarget(
                        "bedrock", "us-east-1", MODEL_ID, null, null, null, null, false),
                AiCredentials.of(Map.of()),
                "Extract literal fields",
                List.of(new AiMessage("user", "Read the card")),
                List.of(image()),
                AiOutputMode.TEXT,
                64,
                0);
    }

    private static AiInputImage image() {
        return new AiInputImage(
                "image/jpeg", new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1}, 100, 50);
    }
}
