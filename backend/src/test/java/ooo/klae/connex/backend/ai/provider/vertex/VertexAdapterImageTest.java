package ooo.klae.connex.backend.ai.provider.vertex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
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
import ooo.klae.connex.backend.ai.provider.AiCredentials;
import ooo.klae.connex.backend.ai.provider.AiInputImage;
import ooo.klae.connex.backend.ai.provider.AiMessage;
import ooo.klae.connex.backend.ai.provider.AiProviderException;
import ooo.klae.connex.backend.ai.provider.AiProviderTarget;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class VertexAdapterImageTest {
    @Mock private VertexClient vertexClient;
    @Mock private GoogleAccessTokenClient googleAccessTokenClient;

    private ObjectMapper objectMapper;
    private VertexAdapter adapter;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        adapter = new VertexAdapter(vertexClient, googleAccessTokenClient, objectMapper, new AiProperties());
    }

    @Test
    void geminiEmbedsInlineImageDataBeforeText() throws Exception {
        stubAccessToken();
        when(vertexClient.complete(
                any(URI.class), any(), any(), any(AiRequestDeadline.class))).thenReturn("""
                {"candidates":[{"content":{"parts":[{"text":"{}"}]},"finishReason":"STOP"}],
                 "usageMetadata":{"promptTokenCount":1,"candidatesTokenCount":1}}
                """);

        adapter.complete(request("us-central1", "gemini-2.5-pro"));

        JsonNode parts = capturedBody().path("contents").path(0).path("parts");
        assertEquals("image/jpeg", parts.path(0).path("inlineData").path("mimeType").asString());
        assertEquals("/9j/AQ==", parts.path(0).path("inlineData").path("data").asString());
        assertEquals("Read the card", parts.path(1).path("text").asString());
    }

    @Test
    void claudeEmbedsAnthropicImageBlockBeforeText() throws Exception {
        stubAccessToken();
        when(vertexClient.complete(
                any(URI.class), any(), any(), any(AiRequestDeadline.class))).thenReturn("""
                {"content":[{"type":"text","text":"{}"}],
                 "usage":{"input_tokens":1,"output_tokens":1},"stop_reason":"end_turn"}
                """);

        adapter.complete(request("us-east5", "claude-sonnet-4@20250514"));

        JsonNode content = capturedBody().path("messages").path(0).path("content");
        assertEquals("image", content.path(0).path("type").asString());
        assertEquals("image/jpeg", content.path(0).path("source").path("media_type").asString());
        assertEquals("/9j/AQ==", content.path(0).path("source").path("data").asString());
        assertEquals("Read the card", content.path(1).path("text").asString());
    }

    @Test
    void rejectsIncompatibleModelLocationBeforeCredentialsOrProviderEgress() {
        AiProviderException exception = assertThrows(AiProviderException.class,
                () -> adapter.complete(request("us-central1", "claude-sonnet-4@20250514")));

        assertEquals("Vertex model does not support image input in this region", exception.getMessage());
        verifyNoInteractions(googleAccessTokenClient, vertexClient);
    }

    @Test
    void rejectsRetiredImageModelBeforeCredentialsOrProviderEgress() {
        AiProviderException exception = assertThrows(AiProviderException.class,
                () -> adapter.complete(request("us-east5", "claude-3-5-haiku@20241022")));

        assertEquals("Vertex model does not support image input in this region", exception.getMessage());
        verifyNoInteractions(googleAccessTokenClient, vertexClient);
    }

    private JsonNode capturedBody() throws Exception {
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(vertexClient).complete(
                any(URI.class), any(), bodyCaptor.capture(), any(AiRequestDeadline.class));
        return objectMapper.readTree(bodyCaptor.getValue());
    }

    private void stubAccessToken() {
        when(googleAccessTokenClient.accessToken(
                any(AiCredentials.class), any(AiRequestDeadline.class))).thenReturn("access-token");
    }

    private static AiCompletionRequest request(String region, String modelId) {
        return new AiCompletionRequest(
                new AiProviderTarget(
                        "vertex", region, modelId, null, null, null, "connex1", false),
                AiCredentials.of(Map.of()),
                "Extract literal fields",
                List.of(new AiMessage("user", "Read the card")),
                List.of(image()),
                64,
                0);
    }

    private static AiInputImage image() {
        return new AiInputImage(
                "image/jpeg", new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1}, 100, 50);
    }
}
