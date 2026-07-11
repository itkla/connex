package ooo.klae.connex.backend.ai.provider.azure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mockStatic;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URI;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import ooo.klae.connex.backend.ai.egress.AiEgressGuard;
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
