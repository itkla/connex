package ooo.klae.connex.backend.ai.provider.bedrock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.hc.core5.http.ContentType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.egress.AiRequestDeadline;
import ooo.klae.connex.backend.ai.egress.FixedAiProviderClient;
import ooo.klae.connex.backend.ai.provider.AiCredentials;
import ooo.klae.connex.backend.ai.provider.AiProviderException;

class BedrockClientTest {
    private static final String MODEL_ID = "anthropic.claude-3-5-sonnet-20240620-v1:0";
    private static final String REQUEST_BODY = "{\"messages\":[]}";
    private static final AiCredentials CREDENTIALS = AiCredentials.of(Map.of(
            "accessKeyId", "AKIDEXAMPLE",
            "secretAccessKey", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"));

    @Test
    void invokeModelUsesPinnedTransportAndSharesOneDeadlineAcrossStatusRetry() {
        FixedAiProviderClient providerClient = mock(FixedAiProviderClient.class);
        when(providerClient.post(
                any(URI.class), anySet(), anyMap(), any(ContentType.class), any(byte[].class),
                any(AiRequestDeadline.class), any()))
                .thenReturn(
                        new FixedAiProviderClient.Response(503, new byte[0]),
                        new FixedAiProviderClient.Response(
                                200, "{\"content\":[]}".getBytes(StandardCharsets.UTF_8)));
        BedrockClient client = client(providerClient);

        String response = client.invokeModel(
                BedrockRegion.US_EAST_1, MODEL_ID, CREDENTIALS, REQUEST_BODY);

        assertEquals("{\"content\":[]}", response);
        ArgumentCaptor<AiRequestDeadline> deadlines = ArgumentCaptor.forClass(AiRequestDeadline.class);
        verify(providerClient, times(2)).post(
                eq(URI.create("https://bedrock-runtime.us-east-1.amazonaws.com/model/"
                        + "anthropic.claude-3-5-sonnet-20240620-v1%3A0/invoke")),
                argThat(hosts -> hosts.contains("bedrock-runtime.us-east-1.amazonaws.com")),
                argThat(headers -> headers.get("Authorization").startsWith("AWS4-HMAC-SHA256 ")),
                eq(ContentType.APPLICATION_JSON),
                aryEq(REQUEST_BODY.getBytes(StandardCharsets.UTF_8)),
                deadlines.capture(),
                eq("Bedrock invocation"));
        List<AiRequestDeadline> captured = deadlines.getAllValues();
        assertSame(captured.getFirst(), captured.getLast());
    }

    @Test
    void invokeModelRetriesOneTransientConnectionFailureUnderTheSameDeadline() {
        FixedAiProviderClient providerClient = mock(FixedAiProviderClient.class);
        when(providerClient.post(
                any(URI.class), anySet(), anyMap(), any(ContentType.class), any(byte[].class),
                any(AiRequestDeadline.class), any()))
                .thenThrow(new FixedAiProviderClient.RetryableTransportException(
                        "Bedrock invocation failed during transport"))
                .thenReturn(new FixedAiProviderClient.Response(
                        200, "{\"content\":[]}".getBytes(StandardCharsets.UTF_8)));
        BedrockClient client = client(providerClient);

        String response = client.invokeModel(
                BedrockRegion.US_EAST_1, MODEL_ID, CREDENTIALS, REQUEST_BODY);

        assertEquals("{\"content\":[]}", response);
        ArgumentCaptor<AiRequestDeadline> deadlines = ArgumentCaptor.forClass(AiRequestDeadline.class);
        verify(providerClient, times(2)).post(
                any(URI.class), anySet(), anyMap(), any(ContentType.class), any(byte[].class),
                deadlines.capture(), eq("Bedrock invocation"));
        assertSame(deadlines.getAllValues().getFirst(), deadlines.getAllValues().getLast());
    }

    @Test
    void invokeModelBacksOffForEveryDocumentedRetryableStatus() {
        Map<Integer, Long> expectedDelays = Map.of(
                500, TimeUnit.MILLISECONDS.toNanos(25),
                503, TimeUnit.MILLISECONDS.toNanos(25),
                429, TimeUnit.MILLISECONDS.toNanos(500));
        for (Map.Entry<Integer, Long> scenario : expectedDelays.entrySet()) {
            FixedAiProviderClient providerClient = mock(FixedAiProviderClient.class);
            when(providerClient.post(
                    any(URI.class), anySet(), anyMap(), any(ContentType.class), any(byte[].class),
                    any(AiRequestDeadline.class), any()))
                    .thenReturn(
                            new FixedAiProviderClient.Response(scenario.getKey(), new byte[0]),
                            new FixedAiProviderClient.Response(
                                    200, "{\"content\":[]}".getBytes(StandardCharsets.UTF_8)));
            AtomicLong delayNanos = new AtomicLong(-1);
            BedrockClient client = new BedrockClient(
                    new AiProperties(), providerClient, delayNanos::set, maximum -> maximum / 2);

            String response = client.invokeModel(
                    BedrockRegion.US_EAST_1, MODEL_ID, CREDENTIALS, REQUEST_BODY);

            assertEquals("{\"content\":[]}", response);
            assertEquals(scenario.getValue().longValue(), delayNanos.get());
            verify(providerClient, times(2)).post(
                    any(URI.class), anySet(), anyMap(), any(ContentType.class), any(byte[].class),
                    any(AiRequestDeadline.class), eq("Bedrock invocation"));
        }
    }

    @Test
    void invokeModelBoundsRetryableFailuresToOneRetry() {
        FixedAiProviderClient providerClient = mock(FixedAiProviderClient.class);
        when(providerClient.post(
                any(URI.class), anySet(), anyMap(), any(ContentType.class), any(byte[].class),
                any(AiRequestDeadline.class), any()))
                .thenReturn(new FixedAiProviderClient.Response(500, new byte[0]));
        AtomicInteger sleeps = new AtomicInteger();
        BedrockClient client = new BedrockClient(
                new AiProperties(), providerClient, delay -> sleeps.incrementAndGet(), maximum -> 0);

        AiProviderException exception = assertThrows(AiProviderException.class, () -> client.invokeModel(
                BedrockRegion.US_EAST_1, MODEL_ID, CREDENTIALS, REQUEST_BODY));

        assertEquals("Bedrock invocation failed with status 500", exception.getMessage());
        assertEquals(1, sleeps.get());
        verify(providerClient, times(2)).post(
                any(URI.class), anySet(), anyMap(), any(ContentType.class), any(byte[].class),
                any(AiRequestDeadline.class), eq("Bedrock invocation"));
    }

    @Test
    void invokeModelDoesNotRetryPermanentStatuses() {
        FixedAiProviderClient providerClient = mock(FixedAiProviderClient.class);
        when(providerClient.post(
                any(URI.class), anySet(), anyMap(), any(ContentType.class), any(byte[].class),
                any(AiRequestDeadline.class), any()))
                .thenReturn(new FixedAiProviderClient.Response(400, new byte[0]));
        AtomicInteger sleeps = new AtomicInteger();
        BedrockClient client = new BedrockClient(
                new AiProperties(), providerClient, delay -> sleeps.incrementAndGet(), maximum -> 0);

        assertThrows(AiProviderException.class, () -> client.invokeModel(
                BedrockRegion.US_EAST_1, MODEL_ID, CREDENTIALS, REQUEST_BODY));

        assertEquals(0, sleeps.get());
        verify(providerClient, times(1)).post(
                any(URI.class), anySet(), anyMap(), any(ContentType.class), any(byte[].class),
                any(AiRequestDeadline.class), eq("Bedrock invocation"));
    }

    @Test
    void invokeModelDoesNotRetryWhenJitterCannotFitWithinDeadline() {
        FixedAiProviderClient providerClient = mock(FixedAiProviderClient.class);
        when(providerClient.post(
                any(URI.class), anySet(), anyMap(), any(ContentType.class), any(byte[].class),
                any(AiRequestDeadline.class), any()))
                .thenReturn(new FixedAiProviderClient.Response(429, new byte[0]));
        AiProperties properties = new AiProperties();
        properties.setRequestTimeoutMs(1);
        AtomicInteger sleeps = new AtomicInteger();
        BedrockClient client = new BedrockClient(
                properties, providerClient, delay -> sleeps.incrementAndGet(), maximum -> maximum);

        assertThrows(AiProviderException.class, () -> client.invokeModel(
                BedrockRegion.US_EAST_1, MODEL_ID, CREDENTIALS, REQUEST_BODY));

        assertEquals(0, sleeps.get());
        verify(providerClient, times(1)).post(
                any(URI.class), anySet(), anyMap(), any(ContentType.class), any(byte[].class),
                any(AiRequestDeadline.class), eq("Bedrock invocation"));
    }

    @Test
    void invokeModelRestoresInterruptWhenRetryBackoffIsInterrupted() {
        FixedAiProviderClient providerClient = mock(FixedAiProviderClient.class);
        when(providerClient.post(
                any(URI.class), anySet(), anyMap(), any(ContentType.class), any(byte[].class),
                any(AiRequestDeadline.class), any()))
                .thenReturn(new FixedAiProviderClient.Response(503, new byte[0]));
        BedrockClient client = new BedrockClient(
                new AiProperties(),
                providerClient,
                delay -> {
                    throw new InterruptedException("interrupted");
                },
                maximum -> 1);

        try {
            AiProviderException exception = assertThrows(AiProviderException.class, () -> client.invokeModel(
                    BedrockRegion.US_EAST_1, MODEL_ID, CREDENTIALS, REQUEST_BODY));

            assertEquals("Bedrock invocation was interrupted", exception.getMessage());
            assertTrue(Thread.currentThread().isInterrupted());
            verify(providerClient, times(1)).post(
                    any(URI.class), anySet(), anyMap(), any(ContentType.class), any(byte[].class),
                    any(AiRequestDeadline.class), eq("Bedrock invocation"));
        } finally {
            Thread.interrupted();
        }
    }

    private static BedrockClient client(FixedAiProviderClient providerClient) {
        return new BedrockClient(new AiProperties(), providerClient, delay -> { }, maximum -> 0);
    }
}
