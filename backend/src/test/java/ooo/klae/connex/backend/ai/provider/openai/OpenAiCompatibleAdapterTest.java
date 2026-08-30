package ooo.klae.connex.backend.ai.provider.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.egress.AiEndpointAddressValidator;
import ooo.klae.connex.backend.ai.egress.AiRequestDeadline;
import ooo.klae.connex.backend.ai.provider.AiCompletionRequest;
import ooo.klae.connex.backend.ai.provider.AiCompletionResult;
import ooo.klae.connex.backend.ai.provider.AiCredentials;
import ooo.klae.connex.backend.ai.provider.AiInputImage;
import ooo.klae.connex.backend.ai.provider.AiMessage;
import ooo.klae.connex.backend.ai.provider.AiNativeToolRequest;
import ooo.klae.connex.backend.ai.provider.AiOutputMode;
import ooo.klae.connex.backend.ai.provider.AiProviderException;
import ooo.klae.connex.backend.ai.provider.AiProviderRequestRejectedException;
import ooo.klae.connex.backend.ai.provider.AiProviderTarget;
import ooo.klae.connex.backend.ai.provider.AiReasoningMode;
import ooo.klae.connex.backend.ai.provider.AiResponseSchema;
import ooo.klae.connex.backend.ai.provider.AiStructuredOutputEnforcement;
import ooo.klae.connex.backend.ai.provider.AiToolCall;
import ooo.klae.connex.backend.ai.provider.AiToolCallingMode;
import ooo.klae.connex.backend.ai.provider.AiToolDefinition;
import ooo.klae.connex.backend.ai.provider.AiToolExchange;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class OpenAiCompatibleAdapterTest {
    private static final String API_KEY = "openai_compatible_api_key_secret";
    private static final String PROMPT = "PROMPT_TEXT_SECRET";

    @Mock private OpenAiCompatibleClient openAiCompatibleClient;

    private ObjectMapper objectMapper;
    private AiProperties aiProperties;
    private OpenAiCompatibleAdapter adapter;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        aiProperties = new AiProperties();
        adapter = new OpenAiCompatibleAdapter(openAiCompatibleClient, objectMapper, aiProperties);
    }

    @Test
    void providerId_registersOpenAiCompatibleAdapter() {
        assertEquals("openai_compatible", adapter.providerId());
        assertEquals(AiReasoningMode.TAGGED, adapter.reasoningCapability(null));
        assertEquals(AiReasoningMode.NONE,
                adapter.nativeToolReasoningCapability(null));
        assertEquals(AiToolCallingMode.NATIVE_FUNCTIONS,
                adapter.toolCallingCapability(null));
        assertEquals(4_096, adapter.contextWindowTokens(null));
        assertEquals(128_000, adapter.contextWindowTokens(
                target("https://api.example.test/v1", false, "gemma-4-31b-it")));
        assertEquals(128_000, adapter.contextWindowTokens(
                target("https://api.example.test/v1", false, "google/gemma-4-31b-it")));
        assertEquals(128_000, adapter.contextWindowTokens(
                target("https://api.example.test/v1", false, "gemma-3-27b-it")));
        assertEquals(32_768, adapter.contextWindowTokens(
                target("https://api.example.test/v1", false, "gemma-3-1b-it")));
        assertEquals(32_768, adapter.contextWindowTokens(
                target("https://api.example.test/v1", false, "gemma-3-270m-it")));
        assertEquals(32_768, adapter.contextWindowTokens(
                target("https://api.example.test/v1", false, "gemma-3n-e4b-it")));
        assertEquals(128_000, adapter.maxOutputTokens(
                target("https://api.example.test/v1", false, "google/gemma-4-31b-it")));
        assertEquals(4_096, adapter.maxOutputTokens(
                target("https://api.example.test/v1", false, "unknown-modern-model")));
        assertEquals(4_096, adapter.contextWindowTokens(
                target("https://api.example.test/v1", false, "gemini-2.5-flash-image")));
        assertEquals(4_096, adapter.maxOutputTokens(
                target("https://api.example.test/v1", false, "gemini-2.5-flash-image")));
        assertEquals(4_096, adapter.contextWindowTokens(
                target("https://api.example.test/v1", false, "gemini-2.5-flash-preview-tts")));
        assertEquals(2_097_152, adapter.contextWindowTokens(
                target("https://api.example.test/v1", false, "gemini-1.5-pro")));
        assertEquals(1_048_576, adapter.contextWindowTokens(
                target("https://api.example.test/v1", false, "gemini-2.0-flash")));
        assertEquals(8_192, adapter.maxOutputTokens(
                target("https://api.example.test/v1", false, "gemini-1.5-flash-8b")));
        assertEquals(200_000, adapter.contextWindowTokens(
                target("https://api.example.test/v1", false, "o3")));
        assertEquals(100_000, adapter.maxOutputTokens(
                target("https://api.example.test/v1", false, "o4-mini")));
        assertEquals(128_000, adapter.contextWindowTokens(
                target("https://api.example.test/v1", false, "gpt-4.5-preview")));
        assertEquals(16_384, adapter.maxOutputTokens(
                target("https://api.example.test/v1", false, "gpt-4.5-preview")));
    }

    @Test
    void recognizedVendorFamiliesUseDocumentedTokenLimits() {
        assertTokenLimits("google/gemini-3.6-flash", 1_048_576, 65_536);
        assertTokenLimits("gemini-2.5-pro-preview-06-05", 1_048_576, 65_536);
        assertTokenLimits("openai/gpt-4o-2024-11-20", 128_000, 16_384);
        assertTokenLimits("gpt-4.1-mini", 1_047_576, 32_768);
        assertTokenLimits("gpt-5.2", 400_000, 128_000);
        assertTokenLimits("gpt-5-chat-latest", 128_000, 16_384);
        assertTokenLimits("gpt-5.2-chat-latest", 128_000, 16_384);
        assertTokenLimits("gpt-5.4", 1_050_000, 128_000);
        assertTokenLimits("gpt-5.4-mini-2026-03-17", 400_000, 128_000);
        assertTokenLimits("gpt-5.6-terra", 1_050_000, 128_000);
        assertTokenLimits("anthropic/claude-3-5-sonnet-20241022", 200_000, 8_192);
    }

    /**
     * Claude's published output ceiling is 128,000 tokens; the adapter previously asked for
     * 131,072, which is 2,072 tokens past what the API accepts, and reported 65,536 for Sonnet 4.6
     * where the first-party API also publishes 128,000.
     */
    @Test
    void currentClaudeModelsUseTheirPublishedOutputCeiling() {
        assertTokenLimits("claude-opus-5", 1_000_000, 128_000);
        assertTokenLimits("claude-fable-5", 1_000_000, 128_000);
        assertTokenLimits("claude-mythos-5", 1_000_000, 128_000);
        assertTokenLimits("claude-sonnet-5", 1_000_000, 128_000);
        assertTokenLimits("claude-sonnet-4-6", 1_000_000, 128_000);
        assertTokenLimits("claude-opus-4-8", 1_000_000, 128_000);
        assertTokenLimits("claude-opus-4-7", 1_000_000, 128_000);
        assertTokenLimits("claude-opus-4-6", 1_000_000, 128_000);
        assertTokenLimits("claude-haiku-4-5", 200_000, 64_000);
        assertTokenLimits("claude-opus-4-5", 200_000, 65_536);
        assertTokenLimits("claude-sonnet-4-5-20250929", 200_000, 65_536);
    }

    @Test
    void anOperatorOverrideDeclaresASelfHostedModelsRealLimits() {
        AiProperties.ModelOverride override = new AiProperties.ModelOverride();
        override.setProvider("openai_compatible");
        override.setModelId("llama3.3:70b");
        override.setContextWindowTokens(131_072);
        override.setMaxOutputTokens(8_192);
        aiProperties.setModelOverrides(List.of(override));

        assertTokenLimits("llama3.3:70b", 131_072, 8_192);
        assertTokenLimits("mistral-large-latest", 4_096, 4_096);
    }

    /**
     * An OpenAI-compatible endpoint serves any model under any name, and one that rejects the
     * streamed request fails the turn outright rather than falling back — which is exactly how
     * assuming this capability took every Ask Connex turn down. It is now declared, not assumed.
     */
    private static AiProperties.ModelOverride streamingOverride(
            String modelId, String endpoint, Boolean streaming) {
        AiProperties.ModelOverride override = new AiProperties.ModelOverride();
        override.setProvider("openai_compatible");
        override.setModelId(modelId);
        override.setEndpoint(endpoint);
        override.setStreaming(streaming);
        return override;
    }

    /**
     * An OpenAI-compatible endpoint serves any model under any name, and one that rejects the
     * streamed request fails the turn outright rather than falling back — which is exactly how
     * assuming this capability took every Ask Connex turn down. It is now declared, not assumed.
     */
    @Test
    void streamingIsDeclaredByAnOperatorRatherThanAssumed() {
        String endpoint = "https://api.example.test/v1";
        AiProviderTarget target = target(endpoint, false, "gemini-3.6-flash");

        assertFalse(adapter.supportsStreaming(target));

        aiProperties.setModelOverrides(List.of(
                streamingOverride("gemini-3.6-flash", endpoint, true)));

        assertTrue(adapter.supportsStreaming(target));
        assertFalse(adapter.supportsStreaming(
                target(endpoint, false, "some-other-model")));
    }

    /**
     * The same model id behind two gateways is two different answers to whether streaming works,
     * so verifying one endpoint must never speak for the other.
     */
    @Test
    void aStreamingDeclarationNeverEscapesTheEndpointItNames() {
        aiProperties.setModelOverrides(List.of(
                streamingOverride("gemini-3.6-flash", "https://verified.example.test/v1", true)));

        assertTrue(adapter.supportsStreaming(
                target("https://verified.example.test/v1", false, "gemini-3.6-flash")));
        assertFalse(adapter.supportsStreaming(
                target("https://other.example.test/v1", false, "gemini-3.6-flash")));
    }

    /** Declarations resolve exactly as every other override does: nulls skipped, last one wins. */
    @Test
    void streamingDeclarationsResolveLikeEveryOtherOverride() {
        String endpoint = "https://api.example.test/v1";
        AiProviderTarget target = target(endpoint, false, "gemini-3.6-flash");

        List<AiProperties.ModelOverride> overrides = new java.util.ArrayList<>();
        overrides.add(null);
        overrides.add(streamingOverride("gemini-3.6-flash", endpoint, true));
        overrides.add(streamingOverride("gemini-3.6-flash", endpoint, false));
        aiProperties.setModelOverrides(overrides);

        assertFalse(adapter.supportsStreaming(target));
    }

    /**
     * URI paths are case-sensitive, so an endpoint differing only in path case is a different
     * route nobody verified. The declaration stays with the exact string it names.
     */
    @Test
    void aDeclarationDoesNotCoverAnEndpointDifferingOnlyInCase() {
        aiProperties.setModelOverrides(List.of(
                streamingOverride(
                        "gemini-3.6-flash", "https://api.example.test/v1beta/openai", true)));

        assertFalse(adapter.supportsStreaming(
                target("https://api.example.test/v1beta/OPENAI", false, "gemini-3.6-flash")));
    }

    /** A vendor-prefixed model id is normalized before matching, as the token overrides are. */
    @Test
    void aVendorPrefixedModelIdStillMatchesItsDeclaration() {
        String endpoint = "https://api.example.test/v1";
        aiProperties.setModelOverrides(List.of(
                streamingOverride("gemini-3.6-flash", endpoint, true)));

        assertTrue(adapter.supportsStreaming(
                target(endpoint, false, "google/gemini-3.6-flash")));
    }

    private static AiProperties.ModelOverride thoughtsOverride(
            String modelId, String endpoint, Boolean thoughts) {
        AiProperties.ModelOverride override = new AiProperties.ModelOverride();
        override.setProvider("openai_compatible");
        override.setModelId(modelId);
        override.setEndpoint(endpoint);
        override.setThoughts(thoughts);
        return override;
    }

    /**
     * The request parameter that asks for thoughts is rejected outright by an endpoint that does
     * not know it, so an unverified endpoint must be asked for nothing — declared, not assumed.
     */
    @Test
    void thoughtSummariesAreDeclaredByAnOperatorRatherThanAssumed() {
        String endpoint = "https://api.example.test/v1";
        AiProviderTarget target = target(endpoint, false, "gemini-3.6-flash");

        assertEquals(AiReasoningMode.NONE, adapter.nativeToolReasoningCapability(target));

        aiProperties.setModelOverrides(List.of(
                thoughtsOverride("gemini-3.6-flash", endpoint, true)));

        assertEquals(AiReasoningMode.NATIVE, adapter.nativeToolReasoningCapability(target));
        assertEquals(AiReasoningMode.NONE, adapter.nativeToolReasoningCapability(
                target("https://other.example.test/v1", false, "gemini-3.6-flash")));
    }

    /** A native reasoning request carries the one verified parameter, and no other request does. */
    @Test
    void onlyANativeReasoningRequestAsksForThoughts() throws Exception {
        when(openAiCompatibleClient.complete(
                any(URI.class), anyBoolean(), any(AiCredentials.class), anyString(),
                any(AiRequestDeadline.class)))
                .thenReturn(validResponse());
        adapter.complete(withReasoningMode(schemaRequest(), AiReasoningMode.NATIVE));
        adapter.complete(withReasoningMode(schemaRequest(), AiReasoningMode.NONE));

        ArgumentCaptor<String> bodies = ArgumentCaptor.forClass(String.class);
        verify(openAiCompatibleClient, org.mockito.Mockito.times(2)).complete(
                any(URI.class), anyBoolean(), any(AiCredentials.class), bodies.capture(),
                any(AiRequestDeadline.class));
        assertTrue(objectMapper.readTree(bodies.getAllValues().get(0))
                .path("extra_body").path("google").path("thinking_config")
                .path("include_thoughts").asBoolean(false));
        assertFalse(objectMapper.readTree(bodies.getAllValues().get(1)).has("extra_body"));
    }

    /**
     * Thought summaries arrive inline in the content field, and everything left in the text
     * reaches the answer — so the thought leaves the text at this boundary or not at all.
     */
    @Test
    void aThoughtSummaryLeavesTheAnswerBeforeAnyChannelReadsIt() throws Exception {
        when(openAiCompatibleClient.complete(
                any(URI.class), anyBoolean(), any(AiCredentials.class), anyString(),
                any(AiRequestDeadline.class)))
                .thenReturn("""
                        {
                          "choices": [{
                            "message": {"content": "<thought>Compare the two deals. </thought>{\\"text\\":\\"Done\\"}"},
                            "finish_reason": "stop"
                          }],
                          "usage": {"prompt_tokens": 1, "completion_tokens": 1}
                        }
                        """);

        AiCompletionResult result = adapter.complete(
                withReasoningMode(schemaRequest(), AiReasoningMode.NATIVE));

        assertEquals("{\"text\":\"Done\"}", result.text());
        assertEquals("Compare the two deals. ", result.reasoning());
    }

    /**
     * Beside a native tool call the content field is read as narration, which is durable —
     * whereas reasoning is deliberately ephemeral. A tool-call turn whose whole content is the
     * thought block must therefore leave the text empty, or the model's private reasoning would
     * be persisted into the wrong channel.
     */
    @Test
    void aToolCallTurnWhoseContentIsOnlyThoughtLeavesNarrationEmpty() throws Exception {
        when(openAiCompatibleClient.complete(
                any(URI.class), anyBoolean(), any(AiCredentials.class), anyString(),
                any(AiRequestDeadline.class)))
                .thenReturn("""
                        {
                          "choices": [{
                            "message": {
                              "content": "<thought>Fetch the weather first.</thought>",
                              "extra_content": {"google": {"thought": true}},
                              "tool_calls": [{"id": "call_1", "type": "function",
                                "function": {"name": "get_weather", "arguments": "{}"}}]
                            },
                            "finish_reason": "tool_calls"
                          }],
                          "usage": {"prompt_tokens": 1, "completion_tokens": 1}
                        }
                        """);

        AiCompletionResult result = adapter.complete(
                withReasoningMode(schemaRequest(), AiReasoningMode.NATIVE));

        assertEquals("", result.text());
        assertEquals("Fetch the weather first.", result.reasoning());
        assertEquals("get_weather", result.toolCalls().getFirst().name());
    }

    /**
     * A thought that opens and never closes yields no answer rather than passing the thought
     * through: failing the step as malformed is recoverable, thought text persisted into the
     * answer is not.
     */
    @Test
    void anUnclosedThoughtYieldsNoAnswerRatherThanLeakingIt() throws Exception {
        when(openAiCompatibleClient.complete(
                any(URI.class), anyBoolean(), any(AiCredentials.class), anyString(),
                any(AiRequestDeadline.class)))
                .thenReturn("""
                        {
                          "choices": [{
                            "message": {
                              "content": "<thought>Unfinished",
                              "tool_calls": [{"id": "call_1", "type": "function",
                                "function": {"name": "get_weather", "arguments": "{}"}}]
                            },
                            "finish_reason": "tool_calls"
                          }],
                          "usage": {"prompt_tokens": 1, "completion_tokens": 1}
                        }
                        """);

        AiCompletionResult result = adapter.complete(
                withReasoningMode(schemaRequest(), AiReasoningMode.NATIVE));

        assertEquals("", result.text());
        assertEquals("Unfinished", result.reasoning());
    }

    /**
     * The wire's two thought signals agree today; a message the flag calls thought but the tags
     * do not is resolved toward the ephemeral channel, because text beside a tool call becomes
     * durable narration and guessing the other way would persist private reasoning.
     */
    @Test
    void aFlaggedMessageWithoutTagsIsStillKeptOutOfTheAnswer() throws Exception {
        when(openAiCompatibleClient.complete(
                any(URI.class), anyBoolean(), any(AiCredentials.class), anyString(),
                any(AiRequestDeadline.class)))
                .thenReturn("""
                        {
                          "choices": [{
                            "message": {
                              "content": "Untagged private reasoning.",
                              "extra_content": {"google": {"thought": true}},
                              "tool_calls": [{"id": "call_1", "type": "function",
                                "function": {"name": "get_weather", "arguments": "{}"}}]
                            },
                            "finish_reason": "tool_calls"
                          }],
                          "usage": {"prompt_tokens": 1, "completion_tokens": 1}
                        }
                        """);

        AiCompletionResult result = adapter.complete(
                withReasoningMode(schemaRequest(), AiReasoningMode.NATIVE));

        assertEquals("", result.text());
        assertEquals("Untagged private reasoning.", result.reasoning());
    }

    /** One override may verify both endpoint capabilities at once; each resolves on its own. */
    @Test
    void oneOverrideCanDeclareStreamingAndThoughtsTogether() {
        String endpoint = "https://api.example.test/v1";
        AiProviderTarget target = target(endpoint, false, "gemini-3.6-flash");
        AiProperties.ModelOverride override = thoughtsOverride("gemini-3.6-flash", endpoint, true);
        override.setStreaming(true);
        aiProperties.setModelOverrides(List.of(override));

        assertTrue(adapter.supportsStreaming(target));
        assertEquals(AiReasoningMode.NATIVE, adapter.nativeToolReasoningCapability(target));
    }

    /** The streamed request builder shares the reasoning gate, so streamed turns ask too. */
    @Test
    void aStreamedNativeReasoningRequestAlsoAsksForThoughts() throws Exception {
        when(openAiCompatibleClient.stream(
                any(URI.class), anyBoolean(), any(AiCredentials.class), anyString(),
                any(AiRequestDeadline.class), any(OpenAiSseAccumulator.class)))
                .thenReturn(new AiCompletionResult("Done", 4, 1, "stop"));

        adapter.completeStreaming(
                withReasoningMode(schemaRequest(), AiReasoningMode.NATIVE), text -> {});

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(openAiCompatibleClient).stream(
                any(URI.class), anyBoolean(), any(AiCredentials.class), body.capture(),
                any(AiRequestDeadline.class), any(OpenAiSseAccumulator.class));
        assertTrue(objectMapper.readTree(body.getValue())
                .path("extra_body").path("google").path("thinking_config")
                .path("include_thoughts").asBoolean(false));
    }

    private AiCompletionRequest withReasoningMode(
            AiCompletionRequest base, AiReasoningMode reasoningMode) {
        return new AiCompletionRequest(
                base.target(),
                base.credentials(),
                base.systemPrompt(),
                base.messages(),
                base.images(),
                base.outputMode(),
                base.responseSchema(),
                reasoningMode,
                base.providerAttemptExecutor(),
                base.maxTokens(),
                base.temperature());
    }

    @Test
    void openWeightAndUnknownFamiliesRetainConservativeFallback() {
        for (String modelId : List.of(
                "llama3.3:70b",
                "mistral-large-latest",
                "qwen3-235b",
                "deepseek-v3.1",
                "gpt-4o-audio-preview",
                "gpt-4.1-custom",
                "gpt-5-custom",
                "unknown-modern-model")) {
            assertTokenLimits(modelId, 4_096, 4_096);
        }
    }

    @Test
    void nonGemmaModelRetainsUsableCapabilitiesAndCompletesAStructuredTurn()
            throws Exception {
        when(openAiCompatibleClient.complete(
                any(URI.class), anyBoolean(), any(AiCredentials.class), anyString(),
                any(AiRequestDeadline.class)))
                .thenReturn(validResponse());
        AiProviderTarget llama = target(
                "https://api.example.test/v1", false, "llama3.3:70b");

        AiCompletionResult result = adapter.complete(schemaRequest());

        assertEquals(4_096, adapter.contextWindowTokens(llama));
        assertEquals("Done", result.text());
        assertEquals(
                AiStructuredOutputEnforcement.JSON_SCHEMA,
                result.structuredOutputEnforcement());
    }

    @Test
    void complete_joinsBasePathsAndPreservesExplicitPort() {
        when(openAiCompatibleClient.complete(any(URI.class), anyBoolean(), any(AiCredentials.class), anyString(), any(AiRequestDeadline.class)))
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
                endpoints.capture(), anyBoolean(), any(AiCredentials.class), anyString(), any(AiRequestDeadline.class));
        assertEquals(List.of(
                URI.create("https://api.example.test/v1/chat/completions"),
                URI.create("https://api.example.test/v1/chat/completions"),
                URI.create("https://api.example.test/chat/completions"),
                URI.create("https://api.example.test:8443/v1/chat/completions")),
                endpoints.getAllValues());
    }

    @Test
    void complete_buildsChatCompletionBodyAndParsesResponse() throws Exception {
        when(openAiCompatibleClient.complete(any(URI.class), anyBoolean(), any(AiCredentials.class), anyString(), any(AiRequestDeadline.class)))
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
                "https://api.example.test/v1", false, "Use short answers", credentials(), AiOutputMode.JSON));

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(openAiCompatibleClient).complete(any(URI.class), anyBoolean(),
                any(AiCredentials.class), bodyCaptor.capture(), any(AiRequestDeadline.class));
        assertEquals(
                "{\"model\":\"llama3.3:70b\",\"messages\":["
                        + "{\"role\":\"system\",\"content\":\"Use short answers\"},"
                        + "{\"role\":\"user\",\"content\":\"Hello?\"},"
                        + "{\"role\":\"assistant\",\"content\":\"Hello.\"}],"
                        + "\"max_tokens\":64,\"temperature\":0.25,"
                        + "\"response_format\":{\"type\":\"json_object\"}}",
                bodyCaptor.getValue());
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
        assertEquals("json_object", body.path("response_format").path("type").asString());
        assertEquals(0.25, body.path("temperature").asDouble());
        assertEquals("Hello world", result.text());
        assertEquals(12, result.inputTokens());
        assertEquals(3, result.outputTokens());
        assertEquals("stop", result.stopReason());
        assertEquals(AiStructuredOutputEnforcement.JSON_OBJECT,
                result.structuredOutputEnforcement());
        assertFalse(result.toString().contains("Hello world"));
    }

    @Test
    void completeStreamingAddsSseFlagsToExistingStructuredRequest() throws Exception {
        when(openAiCompatibleClient.stream(
                any(URI.class), anyBoolean(), any(AiCredentials.class), anyString(),
                any(AiRequestDeadline.class), any(OpenAiSseAccumulator.class)))
                .thenReturn(new AiCompletionResult("Done", 4, 1, "stop"));

        AiCompletionResult result = adapter.completeStreaming(schemaRequest(), text -> {});

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(openAiCompatibleClient).stream(
                eq(URI.create("https://api.example.test/v1/chat/completions")), eq(false),
                eq(credentials()), bodyCaptor.capture(), any(AiRequestDeadline.class),
                any(OpenAiSseAccumulator.class));
        JsonNode body = objectMapper.readTree(bodyCaptor.getValue());
        assertTrue(body.path("stream").asBoolean());
        assertTrue(body.path("stream_options").path("include_usage").asBoolean());
        assertEquals("json_schema", body.path("response_format").path("type").asString());
        assertEquals("Done", result.text());
    }

    @Test
    void complete_translatesNativeToolsExchangesAndToolCalls() throws Exception {
        String firstSignature = "signed replay token /+==\nline two";
        String secondSignature = "response token one /+==";
        String thirdSignature = "response token two \u65e5\u672c\u8a9e";
        when(openAiCompatibleClient.complete(
                any(URI.class), anyBoolean(), any(AiCredentials.class), anyString(),
                any(AiRequestDeadline.class)))
                .thenReturn("""
                        {
                          "choices": [{
                            "message": {
                              "content": null,
                              "reasoning_content": "Use the retrieved record.",
                              "tool_calls": [
                                {
                                  "id": "call_2",
                                  "type": "function",
                                  "extra_content": {
                                    "google": {
                                      "thought_signature": "response token one /+=="
                                    }
                                  },
                                  "function": {
                                    "name": "get_record",
                                    "arguments": "{\\\"handle\\\":\\\"r1\\\"}"
                                  }
                                },
                                {
                                  "id": "call_3",
                                  "type": "function",
                                  "extra_content": {
                                    "google": {
                                      "thought_signature": "response token two \u65e5\u672c\u8a9e"
                                    }
                                  },
                                  "function": {
                                    "name": "get_record",
                                    "arguments": "{\\\"handle\\\":\\\"r2\\\"}"
                                  }
                                }
                              ]
                            },
                            "finish_reason": "tool_calls"
                          }],
                          "usage": {"prompt_tokens": 12, "completion_tokens": 3}
                        }
                        """);
        JsonNode parameters = objectMapper.readTree("""
                {"type":"object","properties":{"handle":{"type":"string"}},
                 "required":["handle"],"additionalProperties":false}
                """);
        AiToolDefinition definition = new AiToolDefinition(
                "get_record", "Load one visible CRM record.", parameters);
        AiToolCall firstCall = new AiToolCall(
                "call_1", "get_record", "{\"handle\":\"r1\"}", firstSignature);
        AiToolCall unsignedCall = new AiToolCall(
                "call_0", "get_record", "{\"handle\":\"r0\"}");
        AiNativeToolRequest nativeTools = new AiNativeToolRequest(
                List.of(definition),
                List.of(
                        new AiToolExchange(
                                firstCall,
                                "CRM_DATA_BEGIN\n{\"kind\":\"tool_result\"}\nCRM_DATA_END"),
                        new AiToolExchange(
                                unsignedCall,
                                "CRM_DATA_BEGIN\n{\"kind\":\"tool_result\"}\nCRM_DATA_END")),
                "Return one corrected JSON final answer only.");
        AiCompletionRequest base = schemaRequest();
        AiCompletionRequest request = new AiCompletionRequest(
                base.target(),
                base.credentials(),
                base.systemPrompt(),
                base.messages(),
                base.images(),
                base.outputMode(),
                base.responseSchema(),
                nativeTools,
                AiReasoningMode.NATIVE,
                base.providerAttemptExecutor(),
                base.maxTokens(),
                base.temperature());

        AiCompletionResult result = adapter.complete(request);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(openAiCompatibleClient).complete(
                any(URI.class), anyBoolean(), any(AiCredentials.class), bodyCaptor.capture(),
                any(AiRequestDeadline.class));
        JsonNode body = objectMapper.readTree(bodyCaptor.getValue());
        JsonNode tool = body.path("tools").path(0).path("function");
        assertEquals("get_record", tool.path("name").asString());
        assertTrue(tool.path("strict").asBoolean());
        assertEquals(parameters, tool.path("parameters"));
        assertEquals("auto", body.path("tool_choice").asString());
        assertEquals("json_schema", body.path("response_format").path("type").asString());
        assertTrue(body.has("parallel_tool_calls"));
        assertFalse(body.path("parallel_tool_calls").asBoolean());
        JsonNode assistant = body.path("messages").path(2);
        assertEquals("assistant", assistant.path("role").asString());
        assertTrue(assistant.path("content").isNull());
        assertEquals("call_1", assistant.path("tool_calls").path(0).path("id").asString());
        assertEquals(firstSignature, assistant.path("tool_calls").path(0)
                .path("extra_content").path("google")
                .path("thought_signature").asString());
        JsonNode toolResult = body.path("messages").path(3);
        assertEquals("tool", toolResult.path("role").asString());
        assertEquals("call_1", toolResult.path("tool_call_id").asString());
        assertTrue(toolResult.path("content").asString().contains("CRM_DATA_BEGIN"));
        JsonNode unsignedAssistant = body.path("messages").path(4);
        assertFalse(unsignedAssistant.path("tool_calls").path(0).has("extra_content"));
        assertEquals("user", body.path("messages").path(6).path("role").asString());
        assertEquals(List.of(
                new AiToolCall(
                        "call_2", "get_record", "{\"handle\":\"r1\"}",
                        secondSignature),
                new AiToolCall(
                        "call_3", "get_record", "{\"handle\":\"r2\"}",
                        thirdSignature)), result.toolCalls());
        assertEquals("Use the retrieved record.", result.reasoning());
        assertEquals("", result.text());
        assertEquals("tool_calls", result.stopReason());
    }

    /**
     * A closing-step request keeps its tool definitions so replayed exchanges resolve, but the
     * provider is told it may not call them: the closing step must produce an answer.
     */
    @Test
    void complete_finalOnlyNativeRequestSendsToolChoiceNone() throws Exception {
        when(openAiCompatibleClient.complete(any(URI.class), anyBoolean(), any(AiCredentials.class), anyString(), any(AiRequestDeadline.class)))
                .thenReturn("""
                        {
                          "choices": [{
                            "message": {"content": "{\\"text\\":\\"Done.\\"}"},
                            "finish_reason": "stop"
                          }],
                          "usage": {"prompt_tokens": 5, "completion_tokens": 2}
                        }
                        """);
        JsonNode parameters = objectMapper.readTree("""
                {"type":"object","properties":{"handle":{"type":"string"}},
                 "required":["handle"],"additionalProperties":false}
                """);
        AiNativeToolRequest nativeTools = new AiNativeToolRequest(
                List.of(new AiToolDefinition(
                        "get_record", "Load one visible CRM record.", parameters)),
                List.of(),
                null,
                true);
        AiCompletionRequest base = schemaRequest();
        AiCompletionRequest request = new AiCompletionRequest(
                base.target(),
                base.credentials(),
                base.systemPrompt(),
                base.messages(),
                base.images(),
                base.outputMode(),
                base.responseSchema(),
                nativeTools,
                AiReasoningMode.NATIVE,
                base.providerAttemptExecutor(),
                base.maxTokens(),
                base.temperature());

        adapter.complete(request);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(openAiCompatibleClient).complete(
                any(URI.class), anyBoolean(), any(AiCredentials.class), bodyCaptor.capture(),
                any(AiRequestDeadline.class));
        JsonNode body = objectMapper.readTree(bodyCaptor.getValue());
        assertEquals("none", body.path("tool_choice").asString());
        assertEquals("get_record",
                body.path("tools").path(0).path("function").path("name").asString());
    }

    @Test
    void complete_leavesAbsentThoughtSignatureNull() throws Exception {
        when(openAiCompatibleClient.complete(
                any(URI.class), anyBoolean(), any(AiCredentials.class), anyString(),
                any(AiRequestDeadline.class)))
                .thenReturn("""
                        {
                          "choices": [{
                            "message": {
                              "content": null,
                              "tool_calls": [{
                                "id": "call_unsigned",
                                "type": "function",
                                "function": {
                                  "name": "get_record",
                                  "arguments": "{\\\"handle\\\":\\\"r1\\\"}"
                                }
                              }]
                            },
                            "finish_reason": "tool_calls"
                          }]
                        }
                        """);

        AiCompletionResult result = adapter.complete(schemaRequest());

        assertNull(result.toolCalls().getFirst().thoughtSignature());
    }

    @Test
    void complete_sendsStrictJsonSchemaAndDegradesOnARejectedCapability() throws Exception {
        when(openAiCompatibleClient.complete(
                any(URI.class), anyBoolean(), any(AiCredentials.class), anyString(), any(AiRequestDeadline.class)))
                .thenThrow(new AiProviderRequestRejectedException("OpenAI-compatible", 400))
                .thenThrow(new AiProviderRequestRejectedException("OpenAI-compatible", 422))
                .thenReturn(validResponse());

        AiCompletionResult result = adapter.complete(schemaRequest());

        ArgumentCaptor<String> bodies = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<AiRequestDeadline> deadlines = ArgumentCaptor.forClass(AiRequestDeadline.class);
        verify(openAiCompatibleClient, times(3)).complete(
                any(URI.class), anyBoolean(), any(AiCredentials.class), bodies.capture(), deadlines.capture());
        JsonNode schemaBody = objectMapper.readTree(bodies.getAllValues().get(0));
        assertEquals("json_schema", schemaBody.path("response_format").path("type").asString());
        assertEquals("assistant_step",
                schemaBody.path("response_format").path("json_schema").path("name").asString());
        assertEquals(true,
                schemaBody.path("response_format").path("json_schema").path("strict").asBoolean());
        assertEquals("object", schemaBody.path("response_format").path("json_schema")
                .path("schema").path("type").asString());
        JsonNode fallbackBody = objectMapper.readTree(bodies.getAllValues().get(1));
        assertEquals("json_object", fallbackBody.path("response_format").path("type").asString());
        JsonNode promptOnlyBody = objectMapper.readTree(bodies.getAllValues().get(2));
        assertFalse(promptOnlyBody.has("response_format"));
        assertSame(deadlines.getAllValues().get(0), deadlines.getAllValues().get(1));
        assertSame(deadlines.getAllValues().get(0), deadlines.getAllValues().get(2));
        assertEquals(AiStructuredOutputEnforcement.PROMPT_ONLY,
                result.structuredOutputEnforcement());
    }

    @Test
    void complete_nativeStructuredFallbackKeepsToolsAndOneDeadline() throws Exception {
        when(openAiCompatibleClient.complete(
                any(URI.class), anyBoolean(), any(AiCredentials.class), anyString(),
                any(AiRequestDeadline.class)))
                .thenThrow(new AiProviderRequestRejectedException("OpenAI-compatible", 400))
                .thenReturn(validResponse());
        AiCompletionRequest base = schemaRequest();
        AiNativeToolRequest nativeTools = new AiNativeToolRequest(
                List.of(new AiToolDefinition(
                        "get_record",
                        "Load one visible CRM record.",
                        objectMapper.readTree(
                                "{\"type\":\"object\",\"properties\":{},"
                                        + "\"required\":[],"
                                        + "\"additionalProperties\":false}"))),
                List.of());
        AiCompletionRequest request = new AiCompletionRequest(
                base.target(),
                base.credentials(),
                base.systemPrompt(),
                base.messages(),
                base.images(),
                base.outputMode(),
                base.responseSchema(),
                nativeTools,
                AiReasoningMode.NATIVE,
                base.providerAttemptExecutor(),
                base.maxTokens(),
                base.temperature());

        AiCompletionResult result = adapter.complete(request);

        ArgumentCaptor<String> bodies = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<AiRequestDeadline> deadlines =
                ArgumentCaptor.forClass(AiRequestDeadline.class);
        verify(openAiCompatibleClient, times(2)).complete(
                any(URI.class), anyBoolean(), any(AiCredentials.class), bodies.capture(),
                deadlines.capture());
        JsonNode strict = objectMapper.readTree(bodies.getAllValues().getFirst());
        JsonNode fallback = objectMapper.readTree(bodies.getAllValues().getLast());
        assertEquals("json_schema", strict.path("response_format").path("type").asString());
        assertEquals("json_object", fallback.path("response_format").path("type").asString());
        assertEquals("get_record", strict.path("tools").path(0)
                .path("function").path("name").asString());
        assertEquals(strict.path("tools"), fallback.path("tools"));
        assertSame(deadlines.getAllValues().getFirst(), deadlines.getAllValues().getLast());
        assertEquals(AiStructuredOutputEnforcement.JSON_OBJECT,
                result.structuredOutputEnforcement());
    }

    @Test
    void complete_taggedReasoningHonestlyUsesPromptOnlyStructuredEnforcement() throws Exception {
        when(openAiCompatibleClient.complete(
                any(URI.class), anyBoolean(), any(AiCredentials.class), anyString(),
                any(AiRequestDeadline.class)))
                .thenReturn(validResponse());
        AiCompletionRequest base = schemaRequest();
        AiCompletionRequest request = new AiCompletionRequest(
                base.target(),
                base.credentials(),
                base.systemPrompt(),
                base.messages(),
                base.images(),
                base.outputMode(),
                base.responseSchema(),
                AiReasoningMode.TAGGED,
                base.providerAttemptExecutor(),
                base.maxTokens(),
                base.temperature());

        AiCompletionResult result = adapter.complete(request);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(openAiCompatibleClient).complete(
                any(URI.class), anyBoolean(), any(AiCredentials.class), body.capture(),
                any(AiRequestDeadline.class));
        assertFalse(objectMapper.readTree(body.getValue()).has("response_format"));
        assertEquals(AiStructuredOutputEnforcement.PROMPT_ONLY,
                result.structuredOutputEnforcement());
        assertEquals(AiReasoningMode.TAGGED, result.reasoningMode());
    }

    @Test
    void completeBoundsStructuredFallbackChainToOneWallClockBudget() throws Exception {
        HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService serverExecutor = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().daemon().name("openai-fallback-budget-test-", 0).factory());
        server.setExecutor(serverExecutor);
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                Thread.sleep(450);
                exchange.sendResponseHeaders(422, -1);
            } catch (IOException ignored) {
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        AiEndpointAddressValidator validator = org.mockito.Mockito.mock(AiEndpointAddressValidator.class);
        when(validator.resolveFetchable("127.0.0.1", true))
                .thenReturn(InetAddress.getByName("127.0.0.1"));
        AiProperties properties = new AiProperties();
        properties.setConnectTimeoutMs(500);
        properties.setRequestTimeoutMs(500);
        OpenAiCompatibleClient client = new OpenAiCompatibleClient(properties, validator);
        OpenAiCompatibleAdapter boundedAdapter = new OpenAiCompatibleAdapter(
                client, objectMapper, properties);
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        long started = System.nanoTime();
        try {
            AiProviderException exception = assertThrows(
                    AiProviderException.class,
                    () -> boundedAdapter.complete(schemaRequest(endpoint, true)));

            long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
            assertEquals("OpenAI-compatible invocation exceeded its deadline", exception.getMessage());
            assertTrue(elapsedMillis < 1_000, () -> "elapsed milliseconds: " + elapsedMillis);
        } finally {
            client.shutdown();
            server.stop(0);
            serverExecutor.shutdownNow();
        }
    }

    @Test
    void completeEmbedsImageBytesInTheFirstUserTurn() throws Exception {
        when(openAiCompatibleClient.complete(any(URI.class), anyBoolean(), any(AiCredentials.class), anyString(), any(AiRequestDeadline.class)))
                .thenReturn(validResponse());
        AiCompletionRequest request = new AiCompletionRequest(
                target("https://api.example.test/v1", false, "openai/gpt-5.2"),
                credentials(),
                "Extract literal fields",
                List.of(new AiMessage("user", "Read the card")),
                List.of(image()),
                AiOutputMode.TEXT,
                64,
                0);

        adapter.complete(request);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(openAiCompatibleClient).complete(any(URI.class), anyBoolean(),
                any(AiCredentials.class), bodyCaptor.capture(), any(AiRequestDeadline.class));
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
        assertFalse(body.has("max_tokens"));
        assertFalse(body.has("temperature"));
    }

    @Test
    void complete_missingUsageReturnsZeroTokenCounts() {
        when(openAiCompatibleClient.complete(any(URI.class), anyBoolean(), any(AiCredentials.class), anyString(), any(AiRequestDeadline.class)))
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

        when(openAiCompatibleClient.complete(any(URI.class), anyBoolean(), any(AiCredentials.class), anyString(), any(AiRequestDeadline.class)))
                .thenReturn(validResponse());
        adapter.complete(request("http://localhost:11434/v1", true, null, emptyCredentials()));

        verify(openAiCompatibleClient).complete(
                eq(URI.create("http://localhost:11434/v1/chat/completions")), eq(true),
                eq(emptyCredentials()), anyString(), any(AiRequestDeadline.class));
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
                    any(URI.class), anyBoolean(), any(AiCredentials.class), anyString(), any(AiRequestDeadline.class)))
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
        when(openAiCompatibleClient.complete(any(URI.class), anyBoolean(), any(AiCredentials.class), anyString(), any(AiRequestDeadline.class)))
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
        when(openAiCompatibleClient.complete(any(URI.class), anyBoolean(), any(AiCredentials.class), anyString(), any(AiRequestDeadline.class)))
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
        when(openAiCompatibleClient.complete(any(URI.class), anyBoolean(), any(AiCredentials.class), anyString(), any(AiRequestDeadline.class)))
                .thenThrow(new IllegalStateException(API_KEY + PROMPT + "SENSITIVE_RESPONSE_BODY"));
        AiCompletionRequest request = new AiCompletionRequest(
                target("https://api.example.test/v1", false),
                credentials(),
                PROMPT,
                List.of(new AiMessage("user", PROMPT)),
                List.of(),
                AiOutputMode.TEXT,
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
        return request(endpoint, allowInternalEndpoint, systemPrompt, credentials, AiOutputMode.TEXT);
    }

    private static AiCompletionRequest request(
            String endpoint,
            boolean allowInternalEndpoint,
            String systemPrompt,
            AiCredentials credentials,
            AiOutputMode outputMode) {
        return new AiCompletionRequest(
                target(endpoint, allowInternalEndpoint),
                credentials,
                systemPrompt,
                List.of(
                        new AiMessage("user", "Hello?"),
                        new AiMessage("assistant", "Hello.")),
                List.of(),
                outputMode,
                64,
                0.25);
    }

    private static AiProviderTarget target(String endpoint, boolean allowInternalEndpoint) {
        return target(endpoint, allowInternalEndpoint, "llama3.3:70b");
    }

    private static AiProviderTarget target(String endpoint, boolean allowInternalEndpoint, String modelId) {
        return new AiProviderTarget(
                "openai_compatible", null, modelId, endpoint,
                null, null, null, allowInternalEndpoint);
    }

    private void assertTokenLimits(
            String modelId,
            int expectedContextWindowTokens,
            int expectedMaxOutputTokens) {
        AiProviderTarget target = target(
                "https://api.example.test/v1", false, modelId);
        assertEquals(expectedContextWindowTokens, adapter.contextWindowTokens(target));
        assertEquals(expectedMaxOutputTokens, adapter.maxOutputTokens(target));
    }

    private AiCompletionRequest schemaRequest() throws Exception {
        return schemaRequest("https://api.example.test/v1", false);
    }

    private AiCompletionRequest schemaRequest(String endpoint, boolean allowInternalEndpoint) throws Exception {
        return new AiCompletionRequest(
                target(endpoint, allowInternalEndpoint),
                credentials(),
                "Return one step",
                List.of(new AiMessage("user", "Hello?")),
                List.of(),
                AiOutputMode.JSON,
                new AiResponseSchema("assistant_step",
                        objectMapper.readTree("{\"type\":\"object\"}")),
                64,
                0.25);
    }

    private static AiCredentials credentials() {
        return AiCredentials.of(Map.of("apiKey", API_KEY));
    }

    private static AiCredentials emptyCredentials() {
        return AiCredentials.of(Map.of());
    }

    private static AiInputImage image() {
        return new AiInputImage(
                "image/jpeg", new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1}, 100, 50);
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
