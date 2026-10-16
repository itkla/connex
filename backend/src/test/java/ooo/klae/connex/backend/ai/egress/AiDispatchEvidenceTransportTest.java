package ooo.klae.connex.backend.ai.egress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static ooo.klae.connex.backend.ai.masking.OutboundSegment.Origin.PROVIDER_AUTHORED;
import static ooo.klae.connex.backend.ai.masking.OutboundSegment.Origin.SERVER_STATIC;
import static ooo.klae.connex.backend.ai.masking.OutboundSegment.Origin.TENANT_DATA;
import static ooo.klae.connex.backend.ai.masking.OutboundSegment.Origin.TENANT_TOOL_RESULT;

import java.net.InetAddress;
import java.net.URI;
import java.time.Clock;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.masking.OutboundSegment;
import ooo.klae.connex.backend.ai.provider.AiCompletionRequest;
import ooo.klae.connex.backend.ai.provider.AiCredentials;
import ooo.klae.connex.backend.ai.provider.AiInputImage;
import ooo.klae.connex.backend.ai.provider.AiMessage;
import ooo.klae.connex.backend.ai.provider.AiNativeToolRequest;
import ooo.klae.connex.backend.ai.provider.AiOutputMode;
import ooo.klae.connex.backend.ai.provider.AiProvider;
import ooo.klae.connex.backend.ai.provider.AiProviderAttemptExecutor;
import ooo.klae.connex.backend.ai.provider.AiProviderException;
import ooo.klae.connex.backend.ai.provider.AiProviderStreamObserver;
import ooo.klae.connex.backend.ai.provider.AiProviderTarget;
import ooo.klae.connex.backend.ai.provider.AiReasoningMode;
import ooo.klae.connex.backend.ai.provider.AiResponseSchema;
import ooo.klae.connex.backend.ai.provider.AiToolCall;
import ooo.klae.connex.backend.ai.provider.AiToolDefinition;
import ooo.klae.connex.backend.ai.provider.AiToolExchange;
import ooo.klae.connex.backend.ai.provider.azure.AzureOpenAiAdapter;
import ooo.klae.connex.backend.ai.provider.azure.AzureOpenAiClient;
import ooo.klae.connex.backend.ai.provider.bedrock.BedrockAnthropicAdapter;
import ooo.klae.connex.backend.ai.provider.bedrock.BedrockClient;
import ooo.klae.connex.backend.ai.provider.openai.OpenAiCompatibleAdapter;
import ooo.klae.connex.backend.ai.provider.openai.OpenAiCompatibleClient;
import ooo.klae.connex.backend.ai.provider.vertex.GoogleAccessTokenClient;
import ooo.klae.connex.backend.ai.provider.vertex.VertexAdapter;
import ooo.klae.connex.backend.ai.provider.vertex.VertexClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Exercises the production serializers and pinned clients at their actual HTTP entity boundary. */
class AiDispatchEvidenceTransportTest {
    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([A-Z][1-9][0-9]*)\\s*}}");
    private static final String PEM_HEADER = "-----BEGIN PRIVATE KEY-----";
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"openai", "azure", "bedrock", "gemini", "claude", "native"})
    void dispatchedEvidenceEqualsDecodedTenantFieldsInActualTransportBytes(String family) throws Exception {
        exercise(family, false, false, false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"openai", "azure", "gemini", "native"})
    void streamingPromotesBeforeFirstDeltaAndRebuildsFallbackEvidence(String family) throws Exception {
        exercise(family, true, false, false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"openai", "azure", "bedrock", "gemini", "claude", "native"})
    void everyAdapterRefusesSerializedBodyProvenanceMismatchBeforeTransport(String family) throws Exception {
        exercise(family, false, true, false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"openai", "azure", "bedrock", "gemini", "claude", "native"})
    void transportFailureBeforeResponseNeverPromotesStagedTokens(String family) throws Exception {
        exercise(family, false, false, true);
    }

    private void exercise(String family, boolean streaming, boolean corrupt, boolean failTransport) throws Exception {
        List<String> wireBodies = new ArrayList<>();
        List<Set<String>> promotions = new ArrayList<>();
        List<String> events = new ArrayList<>();
        boolean fallback = family.equals("openai") || family.equals("azure");
        var executor = new AiProviderAttemptExecutor() {
            @Override
            public String execute(Supplier<String> attempt) {
                events.add("attempt");
                return attempt.get();
            }

            @Override
            public void beforeSend() {
                events.add("send");
                if (!failTransport) {
                    assertEquals(wireBodies.size(), promotions.size());
                }
            }

            @Override
            public void onResponse(Set<String> tokens) {
                assertEquals(wireBodies.size() - 1, promotions.size());
                JsonNode body = mapper.readTree(wireBodies.getLast());
                JsonNode message = family.equals("gemini")
                        ? body.at("/contents/0/parts/1/text")
                        : body.at("/messages/1/content");
                if (message.isArray()) {
                    message = message.get(family.equals("bedrock") || family.equals("claude") ? 1 : 0).get("text");
                }
                if (family.equals("bedrock") || family.equals("claude")) {
                    message = body.at("/messages/0/content/1/text");
                }
                String decoded = message.asString();
                Set<String> expected = tokens(decoded.substring(decoded.indexOf("tenant:"), decoded.indexOf("provider:")));
                expected.addAll(tokens(decoded.substring(decoded.indexOf("tool:"))));
                if (family.equals("native")) {
                    expected.addAll(tokens(body.at("/messages/3/content").asString().split("static:")[0]));
                    assertTrue(body.at("/messages/2/tool_calls/0/function/arguments").asString().contains("{{M4}}"));
                    assertTrue(body.at("/messages/4/content").asString().contains("{{M6}}"));
                }
                assertEquals(expected, tokens);
                assertEquals(family.equals("native")
                        ? Set.of("{{M1}}", "{{P1}}", "{{M2}}", "{{M3}}")
                        : Set.of("{{M1}}", "{{P1}}", "{{M2}}"), tokens);
                assertThrows(UnsupportedOperationException.class, () -> tokens.add("{{M99}}"));
                promotions.add(tokens);
                events.add("response");
            }
        };
        CloseableHttpClient http = mock(CloseableHttpClient.class, call -> {
            if (!call.getMethod().getName().equals("execute")) {
                return RETURNS_DEFAULTS.answer(call);
            }
            ClassicHttpRequest request = call.getArgument(0);
            wireBodies.add(new String(request.getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8));
            assertEquals("send", events.getLast());
            if (failTransport) {
                throw new java.net.SocketTimeoutException("No response headers");
            }
            int status = fallback && wireBodies.size() < 3 ? 400 : 200;
            BasicClassicHttpResponse response = new BasicClassicHttpResponse(status);
            response.setEntity(new ByteArrayEntity(responseBody(family, streaming).getBytes(StandardCharsets.UTF_8),
                    streaming ? ContentType.TEXT_EVENT_STREAM : ContentType.APPLICATION_JSON));
            HttpClientResponseHandler<?> handler = call.getArgument(call.getArguments().length - 1);
            return handler.handleResponse(response);
        });
        HttpClientBuilder builder = mock(HttpClientBuilder.class, RETURNS_SELF);
        when(builder.build()).thenReturn(http);
        AiProperties properties = new AiProperties();
        var validator = mock(AiEndpointAddressValidator.class);
        when(validator.resolveFetchable(anyString(), anyBoolean())).thenReturn(InetAddress.getLoopbackAddress());
        FixedAiProviderClient fixed = new FixedAiProviderClient(properties, host -> InetAddress.getLoopbackAddress());
        OpenAiCompatibleClient openAi = new OpenAiCompatibleClient(properties, validator);
        ObjectMapper serializer = corrupt ? spy(mapper) : mapper;
        if (corrupt) {
            doAnswer(call -> ((String) call.callRealMethod()).replace("{{M1}}", "{{M77}}"))
                    .when(serializer).writeValueAsString(any());
        }
        var oauthTransport = mock(FixedAiProviderClient.class);
        when(oauthTransport.post(any(URI.class), anySet(), anyMap(), any(ContentType.class),
                any(byte[].class), any(AiRequestDeadline.class), anyString()))
                .thenReturn(new FixedAiProviderClient.Response(200,
                        "{\"access_token\":\"test-token\",\"expires_in\":3600}".getBytes(StandardCharsets.UTF_8)));
        var oauth = new GoogleAccessTokenClient(properties, oauthTransport, mapper, Clock.systemUTC());
        AiProvider provider = switch (family) {
            case "openai", "native" -> new OpenAiCompatibleAdapter(openAi, serializer, properties);
            case "azure" -> new AzureOpenAiAdapter(new AzureOpenAiClient(properties, fixed), serializer, properties);
            case "bedrock" -> new BedrockAnthropicAdapter(new BedrockClient(properties, fixed), serializer, properties);
            default -> new VertexAdapter(new VertexClient(properties, fixed), oauth, serializer, properties);
        };
        AiCompletionRequest request = request(family, executor);
        try (var clients = mockStatic(HttpClients.class)) {
            clients.when(HttpClients::custom).thenReturn(builder);
            if (corrupt) {
                assertEquals("AI provider body provenance mismatch",
                        assertThrows(AiProviderException.class, () -> provider.complete(request)).getMessage());
                assertTrue(wireBodies.isEmpty());
                assertTrue(promotions.isEmpty());
            } else if (failTransport) {
                assertThrows(AiProviderException.class, () -> provider.complete(request));
                assertFalse(wireBodies.isEmpty());
                assertTrue(promotions.isEmpty());
            } else {
                if (streaming) {
                    provider.completeStreaming(request, new AiProviderStreamObserver() {
                        @Override
                        public void onContentDelta(String text) {
                            assertEquals(wireBodies.size(), promotions.size());
                            assertEquals("response", events.getLast());
                        }
                    });
                } else {
                    assertEquals("ok", provider.complete(request).text());
                }
                assertEquals(fallback ? 3 : 1, wireBodies.size());
                assertEquals(wireBodies.size(), promotions.size());
                if (fallback) {
                    assertTrue(mapper.readTree(wireBodies.get(0)).has("response_format"));
                    assertEquals("json_object", mapper.readTree(wireBodies.get(1)).at("/response_format/type").asString());
                    assertFalse(mapper.readTree(wireBodies.get(2)).has("response_format"));
                }
            }
        } finally {
            fixed.shutdown();
            ReflectionTestUtils.invokeMethod(openAi, "shutdown");
        }
    }

    private AiCompletionRequest request(String family, AiProviderAttemptExecutor executor) throws Exception {
        String provider = switch (family) {
            case "openai", "native" -> "openai_compatible";
            case "azure" -> "azure_openai";
            case "bedrock" -> "bedrock";
            default -> "vertex";
        };
        String model = switch (family) {
            case "bedrock" -> "anthropic.claude-sonnet-4-5-v1:0";
            case "claude" -> "claude-sonnet-4-5@20250929";
            case "gemini" -> "gemini-2.5-flash";
            default -> "gpt-4o";
        };
        var target = new AiProviderTarget(provider, "us-east-1", model,
                family.equals("azure") ? "https://lane.openai.azure.com" : "https://lane.example.test/v1",
                "2024-10-21", "gpt-4o", "test-project", false);
        if (provider.equals("vertex")) {
            target = new AiProviderTarget(provider, family.equals("claude") ? "us-east5" : "us-central1",
                    model, null, null, null, "test-project", false);
        }
        var segments = List.of(new OutboundSegment("static:{{M9}} ", SERVER_STATIC),
                new OutboundSegment("tenant:quoted \"\\\n日本語 {{M1}} {{ P1 }} ", TENANT_DATA),
                new OutboundSegment("provider:{{M8}} ", PROVIDER_AUTHORED),
                new OutboundSegment("tool:{{M2}}", TENANT_TOOL_RESULT));
        String content = segments.stream().map(OutboundSegment::text).collect(java.util.stream.Collectors.joining());
        var schema = new AiResponseSchema("answer", mapper.readTree("{\"type\":\"object\",\"description\":\"{{M7}}\"}"));
        AiNativeToolRequest nativeTools = null;
        if (family.equals("native")) {
            nativeTools = new AiNativeToolRequest(
                    List.of(new AiToolDefinition("search", "{{M7}}", schema.schema())),
                    List.of(new AiToolExchange(new AiToolCall("call_{{M4}}", "search", "{\"q\":\"{{M4}}\"}", "{{M5}}"),
                            "{{M3}}static:{{M9}}", 1, 0, List.of(new OutboundSegment("{{M3}}", TENANT_TOOL_RESULT),
                                    new OutboundSegment("static:{{M9}}", SERVER_STATIC)))),
                    "repair:{{M6}}", false, 1, List.of(new OutboundSegment("repair:{{M6}}", PROVIDER_AUTHORED)));
        }
        Map<String, String> credentials = new java.util.HashMap<>(Map.of("apiKey", "test-key",
                "accessKeyId", "TEST_KEY", "secretAccessKey", "test-secret"));
        if (provider.equals("vertex")) {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            String privateKey = PEM_HEADER + "\n"
                    + Base64.getMimeEncoder(64, new byte[] {'\n'})
                            .encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
                    + "\n-----END PRIVATE KEY-----";
            credentials.put("serviceAccountJson", mapper.writeValueAsString(Map.of(
                    "type", "service_account", "client_email", "test@test-project.iam.gserviceaccount.com",
                    "private_key", privateKey, "token_uri", "https://oauth2.googleapis.com/token")));
        }
        return new AiCompletionRequest(target, AiCredentials.of(credentials),
                "System {{M7}}", List.of(new AiMessage("user", content, segments)),
                List.of(new AiInputImage("image/jpeg", new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff}, 1, 1)),
                AiOutputMode.JSON, schema, nativeTools, AiReasoningMode.NONE, executor, 64, 0.1);
    }

    private static Set<String> tokens(String text) {
        Set<String> result = new HashSet<>();
        var matcher = TOKEN.matcher(text);
        while (matcher.find()) {
            result.add("{{" + matcher.group(1) + "}}");
        }
        return result;
    }

    private static String responseBody(String family, boolean streaming) {
        if (streaming) {
            return family.equals("gemini")
                    ? "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]},\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"promptTokenCount\":1,\"candidatesTokenCount\":1}}\n\n"
                    : "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
        }
        return switch (family) {
            case "bedrock", "claude" -> "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"usage\":{\"input_tokens\":1,\"output_tokens\":1},\"stop_reason\":\"end_turn\"}";
            case "gemini" -> "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]},\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"promptTokenCount\":1,\"candidatesTokenCount\":1}}";
            default -> "{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}";
        };
    }
}
