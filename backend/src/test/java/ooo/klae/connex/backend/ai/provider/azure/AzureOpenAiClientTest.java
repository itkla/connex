package ooo.klae.connex.backend.ai.provider.azure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import org.apache.hc.core5.http.ContentType;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.egress.AiEgressGuard;
import ooo.klae.connex.backend.ai.egress.AiRequestDeadline;
import ooo.klae.connex.backend.ai.egress.FixedAiProviderClient;
import ooo.klae.connex.backend.ai.provider.AiCredentials;
import ooo.klae.connex.backend.ai.provider.AiProviderException;

class AzureOpenAiClientTest {
    private static final URI ENDPOINT = URI.create("https://connex.openai.azure.com/openai/deployments/contacts-prod"
            + "/chat/completions?api-version=2025-01-01-preview");
    private static final String API_KEY = "azure_api_key_1234";
    private static final String REQUEST_BODY = "{\"messages\":[{\"role\":\"user\",\"content\":\"Hello?\"}]}";

    @Test
    void complete_sendsApiKeyAndBodyAfterEgressVetting() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AzureOpenAiClient client = new AzureOpenAiClient(builder.build(), 1024);
        server.expect(requestTo(ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("api-key", API_KEY))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json(REQUEST_BODY))
                .andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));

        try (MockedStatic<AiEgressGuard> guard = mockStatic(AiEgressGuard.class)) {
            String response = client.complete(ENDPOINT, credentials(), REQUEST_BODY);

            assertEquals("{\"choices\":[]}", response);
            guard.verify(() -> AiEgressGuard.requireFetchableHost("connex.openai.azure.com", false));
            server.verify();
        }
    }

    @Test
    void complete_usesPinnedTransportWithTheCallersAbsoluteDeadline() {
        AiProperties properties = new AiProperties();
        FixedAiProviderClient providerClient = mock(FixedAiProviderClient.class);
        AiRequestDeadline deadline = AiRequestDeadline.afterMillis(5_000);
        when(providerClient.post(
                eq(ENDPOINT),
                eq(Set.of("connex.openai.azure.com")),
                argThat(headers -> API_KEY.equals(headers.get("api-key"))),
                eq(ContentType.APPLICATION_JSON),
                any(byte[].class),
                same(deadline),
                eq("Azure OpenAI invocation")))
                .thenReturn(new FixedAiProviderClient.Response(
                        200, "{\"choices\":[]}".getBytes(StandardCharsets.UTF_8)));
        AzureOpenAiClient client = new AzureOpenAiClient(properties, providerClient);

        String response = client.complete(ENDPOINT, credentials(), REQUEST_BODY, deadline);

        assertEquals("{\"choices\":[]}", response);
        verify(providerClient).post(
                eq(ENDPOINT),
                eq(Set.of("connex.openai.azure.com")),
                argThat(headers -> API_KEY.equals(headers.get("api-key"))),
                eq(ContentType.APPLICATION_JSON),
                any(byte[].class),
                same(deadline),
                eq("Azure OpenAI invocation"));
    }

    @Test
    void complete_nonSuccessStatusRaisesSanitizedException() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AzureOpenAiClient client = new AzureOpenAiClient(builder.build(), 1024);
        server.expect(requestTo(ENDPOINT))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("SENSITIVE_RESPONSE_BODY " + API_KEY));

        try (MockedStatic<AiEgressGuard> ignored = mockStatic(AiEgressGuard.class)) {
            AiProviderException exception = assertThrows(AiProviderException.class,
                    () -> client.complete(ENDPOINT, credentials(), REQUEST_BODY));

            assertEquals("Azure OpenAI invocation failed with status 401", exception.getMessage());
            assertFalse(String.valueOf(exception).contains(API_KEY));
            assertFalse(String.valueOf(exception).contains("SENSITIVE_RESPONSE_BODY"));
            assertNull(exception.getCause());
            server.verify();
        }
    }

    @Test
    void complete_oversizedResponseRaisesSanitizedException() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AzureOpenAiClient client = new AzureOpenAiClient(builder.build(), 8);
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess("SENSITIVE_RESPONSE_BODY", MediaType.APPLICATION_JSON));

        try (MockedStatic<AiEgressGuard> ignored = mockStatic(AiEgressGuard.class)) {
            AiProviderException exception = assertThrows(AiProviderException.class,
                    () -> client.complete(ENDPOINT, credentials(), REQUEST_BODY));

            assertFalse(String.valueOf(exception).contains("SENSITIVE_RESPONSE_BODY"));
            assertNull(exception.getCause());
            server.verify();
        }
    }

    private static AiCredentials credentials() {
        return AiCredentials.of(Map.of("apiKey", API_KEY));
    }
}
