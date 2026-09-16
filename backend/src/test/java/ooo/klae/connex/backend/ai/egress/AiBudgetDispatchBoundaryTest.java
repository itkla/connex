package ooo.klae.connex.backend.ai.egress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import ooo.klae.connex.backend.ai.AiBudgetControlAccess;
import ooo.klae.connex.backend.ai.AiBudgetControlOperations;
import ooo.klae.connex.backend.ai.AiFeature;
import ooo.klae.connex.backend.ai.AiFeatureGate;
import ooo.klae.connex.backend.ai.AiInvocation;
import ooo.klae.connex.backend.ai.AiInvocationAdmissionService;
import ooo.klae.connex.backend.ai.AiInvocationService;
import ooo.klae.connex.backend.ai.AiMediaAdmissionService;
import ooo.klae.connex.backend.ai.AiOrganizationBudgetCoordinator;
import ooo.klae.connex.backend.ai.AiPrivacyMode;
import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.ai.assistant.AiAssistantLoopException;
import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.ai.masking.PromptAssembly;
import ooo.klae.connex.backend.ai.provider.AiCredentials;
import ooo.klae.connex.backend.ai.provider.AiProvider;
import ooo.klae.connex.backend.ai.provider.AiProviderException;
import ooo.klae.connex.backend.ai.provider.AiProviderRouter;
import ooo.klae.connex.backend.ai.provider.AiProviderStreamObserver;
import ooo.klae.connex.backend.ai.provider.ResolvedAiProvider;
import ooo.klae.connex.backend.ai.provider.azure.AzureOpenAiAdapter;
import ooo.klae.connex.backend.ai.provider.azure.AzureOpenAiClient;
import ooo.klae.connex.backend.ai.provider.bedrock.BedrockAnthropicAdapter;
import ooo.klae.connex.backend.ai.provider.bedrock.BedrockClient;
import ooo.klae.connex.backend.ai.provider.openai.OpenAiCompatibleAdapter;
import ooo.klae.connex.backend.ai.provider.openai.OpenAiCompatibleClient;
import ooo.klae.connex.backend.ai.provider.vertex.GoogleAccessTokenClient;
import ooo.klae.connex.backend.ai.provider.vertex.VertexAdapter;
import ooo.klae.connex.backend.ai.provider.vertex.VertexClient;
import ooo.klae.connex.backend.exceptions.AiBudgetExhaustedException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.services.AiProviderConfigService;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.WorkspaceService;
import tools.jackson.databind.ObjectMapper;

class AiBudgetDispatchBoundaryTest {

    private static final String PEM_HEADER = "-----BEGIN PRIVATE KEY-----";
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-10T00:00:00Z"), ZoneOffset.UTC);
    private final AiProperties properties = new AiProperties();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AiBudgetControlOperations operations = mock(AiBudgetControlOperations.class);
    private final AiFeatureGate featureGate = mock(AiFeatureGate.class);

    @Test
    void failedOAuthReleasesTheReservationWithoutModelConsumption() throws Exception {
        FixedAiProviderClient oauth = mock(FixedAiProviderClient.class);
        when(oauth.post(any(URI.class), anySet(), anyMap(), any(ContentType.class),
                any(byte[].class), any(AiRequestDeadline.class), anyString()))
                .thenReturn(new FixedAiProviderClient.Response(401, new byte[0]));
        AtomicInteger modelResolutions = new AtomicInteger();
        FixedAiProviderClient model = new FixedAiProviderClient(properties, host -> {
            modelResolutions.incrementAndGet();
            throw new AiProviderException("Model DNS must not be reached");
        });
        try {
            AiInvocationService service = vertexService(oauth, model);

            assertThrows(AiProviderException.class, () -> service.complete(invocation()));

            assertEquals(0, modelResolutions.get());
            assertReleasedWithoutConsumption();
            verify(oauth).post(any(URI.class), anySet(), anyMap(), any(ContentType.class),
                    any(byte[].class), any(AiRequestDeadline.class), anyString());
        } finally {
            model.shutdown();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void vertexSecondCheckpointReleasesAfterSuccessfulOAuth(boolean streaming) throws Exception {
        FixedAiProviderClient oauth = mock(FixedAiProviderClient.class);
        AtomicBoolean authenticated = new AtomicBoolean();
        when(oauth.post(any(URI.class), anySet(), anyMap(), any(ContentType.class),
                any(byte[].class), any(AiRequestDeadline.class), anyString()))
                .thenAnswer(call -> {
                    authenticated.set(true);
                    return new FixedAiProviderClient.Response(200,
                            "{\"access_token\":\"test-token\",\"expires_in\":3600}"
                                    .getBytes(StandardCharsets.UTF_8));
                });
        doAnswer(call -> {
            if (authenticated.get()) {
                throw new ForbiddenException("AI permission was revoked");
            }
            return null;
        }).when(featureGate).requireAiUsable(AiFeature.DEAL_BRIEF);
        AtomicInteger modelResolutions = new AtomicInteger();
        FixedAiProviderClient model = new FixedAiProviderClient(properties, host -> {
            modelResolutions.incrementAndGet();
            throw new AiProviderException("Model DNS must not be reached");
        });
        try {
            AiInvocationService service = vertexService(oauth, model);
            AiInvocation request = streaming
                    ? invocation().withStreamObserver(text -> {
                        throw new AssertionError("Refused invocation emitted output");
                    })
                    : invocation();

            assertThrows(ForbiddenException.class, () -> service.complete(request));

            assertEquals(0, modelResolutions.get());
            assertReleasedWithoutConsumption();
        } finally {
            model.shutdown();
        }
    }

    @Test
    void failedModelDnsReleasesTheReservationWithoutConsumption() {
        AtomicInteger resolutions = new AtomicInteger();
        FixedAiProviderClient transport = new FixedAiProviderClient(properties, host -> {
            resolutions.incrementAndGet();
            throw new AiProviderException("DNS resolution failed");
        });
        try {
            AiInvocationService service = azureService(transport);

            assertThrows(AiProviderException.class, () -> service.complete(invocation()));

            assertEquals(1, resolutions.get());
            assertReleasedWithoutConsumption();
        } finally {
            transport.shutdown();
        }
    }

    @Test
    void cancellationOnTransportRegistrationReleasesBeforeModelSend() {
        AtomicInteger registrations = new AtomicInteger();
        FixedAiProviderClient transport = new FixedAiProviderClient(
                properties, host -> InetAddress.getLoopbackAddress());
        try {
            AiInvocationService service = azureService(transport);
            AiInvocation request = invocation().withStreamObserver(new AiProviderStreamObserver() {
                @Override
                public void onTransportOpen(Runnable abort) {
                    registrations.incrementAndGet();
                    abort.run();
                }

                @Override
                public void onContentDelta(String text) {
                    throw new AssertionError("Cancelled transport emitted output");
                }
            });

            assertThrows(AiProviderException.class, () -> service.complete(request));

            assertEquals(1, registrations.get());
            assertReleasedWithoutConsumption();
        } finally {
            transport.shutdown();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"openai_compatible", "azure_openai", "vertex", "bedrock"})
    void bufferedClientsPreservePreSendRefusalsAndReleaseReservations(String provider) throws Exception {
        assertPreSendRefusals(provider, false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"openai_compatible", "azure_openai"})
    void streamingAdaptersPreservePreSendRefusalsAndReleaseReservations(String provider) throws Exception {
        assertPreSendRefusals(provider, true);
    }

    private void assertPreSendRefusals(String provider, boolean streaming) throws Exception {
        AiProperties.ModelOverride streamingOverride = new AiProperties.ModelOverride();
        streamingOverride.setProvider("openai_compatible");
        streamingOverride.setModelId("gpt-4o");
        streamingOverride.setEndpoint("https://lane.example.test/v1");
        streamingOverride.setStreaming(true);
        properties.setModelOverrides(List.of(streamingOverride));
        AtomicBoolean resolved = new AtomicBoolean();
        AtomicInteger dispatches = new AtomicInteger();
        AtomicReference<RuntimeException> transportFailure = new AtomicReference<>();
        CloseableHttpClient http = mock(CloseableHttpClient.class, call -> {
            if (call.getMethod().getName().equals("execute")) {
                dispatches.incrementAndGet();
                RuntimeException failure = transportFailure.get();
                if (failure != null) {
                    throw failure;
                }
                throw new AssertionError("Refused invocation reached model transport");
            }
            return RETURNS_DEFAULTS.answer(call);
        });
        HttpClientBuilder builder = mock(HttpClientBuilder.class, RETURNS_SELF);
        when(builder.build()).thenReturn(http);
        FixedAiProviderClient fixed = new FixedAiProviderClient(properties, host -> {
            resolved.set(true);
            return InetAddress.getLoopbackAddress();
        });
        AiEndpointAddressValidator validator = mock(AiEndpointAddressValidator.class);
        when(validator.resolveFetchable(anyString(), anyBoolean()))
                .thenAnswer(call -> {
                    resolved.set(true);
                    return InetAddress.getLoopbackAddress();
                });
        OpenAiCompatibleClient openAi = new OpenAiCompatibleClient(properties, validator);
        try (MockedStatic<HttpClients> clients = mockStatic(HttpClients.class)) {
            clients.when(HttpClients::custom).thenReturn(builder);
            AiInvocationService service = switch (provider) {
                case "azure_openai" -> azureService(fixed);
                case "vertex" -> vertexService(successfulOAuth(), fixed);
                case "bedrock" -> service(new ResolvedAiProvider(
                        "bedrock", "us-east-1", "anthropic.claude-3-sonnet-v1:0",
                        null, null, null, null, false, false, AiPrivacyMode.UNMASKED,
                        AiCredentials.of(Map.of("accessKeyId", "TEST_KEY", "secretAccessKey", "test-secret"))),
                        new BedrockAnthropicAdapter(new BedrockClient(properties, fixed), objectMapper, properties));
                case "openai_compatible" -> service(new ResolvedAiProvider(
                        "openai_compatible", null, "gpt-4o", "https://lane.example.test/v1",
                        null, null, null, false, false, AiPrivacyMode.UNMASKED,
                        AiCredentials.of(Map.of("apiKey", "test-key"))),
                        new OpenAiCompatibleAdapter(openAi, objectMapper, properties));
                default -> throw new AssertionError("Unknown test provider");
            };
            RuntimeException restriction = restrictionEpochRefusal();
            for (RuntimeException refusal : List.of(
                    new ForbiddenException("AI permission was revoked"), restriction,
                    new AiBudgetExhaustedException(),
                    new AiAssistantLoopException("cancelled", "cancelled"),
                    new ConflictException("Assistant turn is no longer active"),
                    new ResourceNotFoundException("Assistant session is no longer accessible"))) {
                resolved.set(false);
                clearInvocations(operations);
                doAnswer(call -> {
                    if (resolved.get()) {
                        throw refusal;
                    }
                    return null;
                }).when(featureGate).requireAiUsable(AiFeature.DEAL_BRIEF);
                AiInvocation request = streaming
                        ? invocation().withStreamObserver(text -> {
                            throw new AssertionError("Refused invocation emitted output");
                        })
                        : invocation();

                RuntimeException failure = assertThrows(RuntimeException.class, () -> service.complete(request));

                if (refusal == restriction) {
                    assertEquals(IllegalStateException.class, failure.getClass());
                    assertEquals("AI restrictions changed before provider egress", failure.getMessage());
                } else {
                    assertSame(refusal, failure);
                }
                assertTrue(resolved.get());
                assertEquals(0, dispatches.get());
                assertReleasedWithoutConsumption();
            }
            resolved.set(false);
            clearInvocations(operations);
            doNothing().when(featureGate).requireAiUsable(AiFeature.DEAL_BRIEF);
            transportFailure.set(new IllegalStateException("private transport detail"));

            AiInvocation request = streaming
                    ? invocation().withStreamObserver(text -> {
                        throw new AssertionError("Failed transport emitted output");
                    })
                    : invocation();
            AiProviderException failure = assertThrows(AiProviderException.class,
                    () -> service.complete(request));

            assertFalse(failure.getMessage().contains("private transport detail"));
            assertNull(failure.getCause());
            assertEquals(1, dispatches.get());
            verify(operations).markDispatched(anyString(), any(LocalDateTime.class));
            verify(operations).settle("reservation", 100L);
            verify(operations, never()).release(anyString());
        } finally {
            fixed.shutdown();
            ReflectionTestUtils.invokeMethod(openAi, "shutdown");
        }
    }

    private FixedAiProviderClient successfulOAuth() {
        FixedAiProviderClient oauth = mock(FixedAiProviderClient.class);
        when(oauth.post(any(URI.class), anySet(), anyMap(), any(ContentType.class),
                any(byte[].class), any(AiRequestDeadline.class), anyString()))
                .thenReturn(new FixedAiProviderClient.Response(200,
                        "{\"access_token\":\"test-token\",\"expires_in\":3600}"
                                .getBytes(StandardCharsets.UTF_8)));
        return oauth;
    }

    private RuntimeException restrictionEpochRefusal() {
        AiRestrictionEpoch epoch = new AiRestrictionEpoch();
        long expected = epoch.current(7);
        epoch.bump(7);
        Supplier<Boolean> provider = () -> Boolean.TRUE;
        Runnable checkpoint = () -> ReflectionTestUtils.invokeMethod(epoch, "invokeAtEgress", 7, provider);
        RuntimeException refusal = assertThrows(RuntimeException.class,
                () -> ReflectionTestUtils.invokeMethod(epoch, "runWithExpectedEgressEpoch", 7, expected, checkpoint));
        assertEquals("EgressRejectedException", refusal.getClass().getSimpleName());
        return refusal;
    }

    private AiInvocationService vertexService(
            FixedAiProviderClient oauth, FixedAiProviderClient model) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String privateKey = PEM_HEADER + "\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'})
                        .encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";
        String serviceAccount = objectMapper.writeValueAsString(Map.of(
                "type", "service_account", "client_email", "lane@test-project.iam.gserviceaccount.com",
                "private_key", privateKey, "token_uri", "https://oauth2.googleapis.com/token"));
        ResolvedAiProvider resolved = new ResolvedAiProvider(
                "vertex", "us-central1", "gemini-2.5-flash", null, null, null, "test-project",
                false, false, AiPrivacyMode.UNMASKED,
                AiCredentials.of(Map.of("serviceAccountJson", serviceAccount)));
        VertexAdapter adapter = new VertexAdapter(new VertexClient(properties, model),
                new GoogleAccessTokenClient(properties, oauth, objectMapper, CLOCK),
                objectMapper, properties);
        return service(resolved, adapter);
    }

    private AiInvocationService azureService(FixedAiProviderClient transport) {
        ResolvedAiProvider resolved = new ResolvedAiProvider(
                "azure_openai", null, "gpt-4o", "https://lane.openai.azure.com",
                "2024-10-21", "gpt-4o", null, false, false, AiPrivacyMode.UNMASKED,
                AiCredentials.of(Map.of("apiKey", "test-key")));
        return service(resolved, new AzureOpenAiAdapter(
                new AzureOpenAiClient(properties, transport), objectMapper, properties));
    }

    private AiInvocationService service(ResolvedAiProvider resolved, AiProvider adapter) {
        AiBudgetControlAccess access = mock(AiBudgetControlAccess.class);
        doAnswer(call -> {
            Supplier<?> work = call.getArgument(0);
            return work.get();
        }).when(access).execute(any());
        when(operations.reserve(anyInt(), any(LocalDate.class), anyLong(), anyString(),
                any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(new AiBudgetControlOperations.Reservation(
                        "reservation", 3, LocalDate.of(2026, 8, 10), 100, true));
        AiOrganizationBudgetCoordinator coordinator = new AiOrganizationBudgetCoordinator(
                operations, access, CLOCK, properties);
        WorkspaceService workspace = mock(WorkspaceService.class);
        when(workspace.getCurrentWorkspaceId()).thenReturn(7);
        when(workspace.getCurrentOrgId()).thenReturn(3);
        when(workspace.getCurrentUserId()).thenReturn(11);
        AiProviderConfigService config = mock(AiProviderConfigService.class);
        when(config.resolveForOrg(3, 11)).thenReturn(resolved);
        AiProviderRouter router = mock(AiProviderRouter.class);
        when(router.adapterFor(resolved.provider())).thenReturn(adapter);
        return new AiInvocationService(featureGate, mock(AiInvocationAdmissionService.class),
                mock(AiMediaAdmissionService.class), config, router, new AiRestrictionEpoch(),
                workspace, mock(AuditService.class), objectMapper, coordinator, CLOCK);
    }

    private void assertReleasedWithoutConsumption() {
        verify(operations).release("reservation");
        verify(operations, never()).markDispatched(anyString(), any(LocalDateTime.class));
        verify(operations, never()).settle(anyString(), anyLong());
    }

    private static AiInvocation invocation() {
        return new AiInvocation(AiFeature.DEAL_BRIEF, new MaskingContext(AiPrivacyMode.UNMASKED),
                PromptAssembly.builder().system("Respond concisely").userTurn("Summarize").build(),
                64, 0.1);
    }
}
