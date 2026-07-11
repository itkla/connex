package ooo.klae.connex.backend.ai.provider.openai;

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
import java.util.List;
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

class OpenAiCompatibleClientTest {
    private static final URI PUBLIC_ENDPOINT = URI.create(
            "https://api.example.test:8443/v1/chat/completions");
    private static final URI HTTP_LOOPBACK_ENDPOINT = URI.create(
            "http://localhost:11434/v1/chat/completions");
    private static final URI HTTPS_LOOPBACK_ENDPOINT = URI.create(
            "https://localhost:11434/v1/chat/completions");
    private static final String API_KEY = "openai_compatible_api_key_secret";
    private static final String REQUEST_BODY =
            "{\"model\":\"llama3.3:70b\",\"messages\":[{\"role\":\"user\",\"content\":\"Hello?\"}]}";

    @Test
    void complete_sendsBearerHeaderAndBodyAfterEgressVetting() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleClient client = new OpenAiCompatibleClient(builder.build(), 1024);
        server.expect(requestTo(PUBLIC_ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + API_KEY))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json(REQUEST_BODY))
                .andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));

        try (MockedStatic<AiEgressGuard> guard = mockStatic(AiEgressGuard.class)) {
            String response = client.complete(PUBLIC_ENDPOINT, false, credentials(), REQUEST_BODY);

            assertEquals("{\"choices\":[]}", response);
            guard.verify(() -> AiEgressGuard.requireFetchableHost("api.example.test", false));
            server.verify();
        }
    }

    @Test
    void complete_withoutApiKeyOmitsBearerHeader() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleClient client = new OpenAiCompatibleClient(builder.build(), 1024);
        server.expect(requestTo(PUBLIC_ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> assertNull(request.getHeaders().getFirst("Authorization")))
                .andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));

        try (MockedStatic<AiEgressGuard> guard = mockStatic(AiEgressGuard.class)) {
            client.complete(PUBLIC_ENDPOINT, false, emptyCredentials(), REQUEST_BODY);

            guard.verify(() -> AiEgressGuard.requireFetchableHost("api.example.test", false));
            server.verify();
        }
    }

    @Test
    void complete_httpEndpointRequiresInternalAllowanceBeforeSend() {
        RestClient.Builder deniedBuilder = RestClient.builder();
        MockRestServiceServer deniedServer = MockRestServiceServer.bindTo(deniedBuilder).build();
        OpenAiCompatibleClient deniedClient = new OpenAiCompatibleClient(deniedBuilder.build(), 1024);

        AiProviderException denied = assertThrows(AiProviderException.class,
                () -> deniedClient.complete(
                        HTTP_LOOPBACK_ENDPOINT, false, emptyCredentials(), REQUEST_BODY));

        assertEquals("Invalid OpenAI-compatible endpoint", denied.getMessage());
        deniedServer.verify();

        RestClient.Builder allowedBuilder = RestClient.builder();
        MockRestServiceServer allowedServer = MockRestServiceServer.bindTo(allowedBuilder).build();
        OpenAiCompatibleClient allowedClient = new OpenAiCompatibleClient(allowedBuilder.build(), 1024);
        allowedServer.expect(requestTo(HTTP_LOOPBACK_ENDPOINT))
                .andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));

        String response = allowedClient.complete(
                HTTP_LOOPBACK_ENDPOINT, true, emptyCredentials(), REQUEST_BODY);

        assertEquals("{\"choices\":[]}", response);
        allowedServer.verify();
    }

    @Test
    void complete_loopbackHostIsBlockedUnlessInternalAllowanceIsClampedOn() {
        RestClient.Builder deniedBuilder = RestClient.builder();
        MockRestServiceServer deniedServer = MockRestServiceServer.bindTo(deniedBuilder).build();
        OpenAiCompatibleClient deniedClient = new OpenAiCompatibleClient(deniedBuilder.build(), 1024);

        AiProviderException denied = assertThrows(AiProviderException.class,
                () -> deniedClient.complete(
                        HTTPS_LOOPBACK_ENDPOINT, false, emptyCredentials(), REQUEST_BODY));

        assertEquals("AI provider egress host resolved to a blocked address", denied.getMessage());
        deniedServer.verify();

        RestClient.Builder allowedBuilder = RestClient.builder();
        MockRestServiceServer allowedServer = MockRestServiceServer.bindTo(allowedBuilder).build();
        OpenAiCompatibleClient allowedClient = new OpenAiCompatibleClient(allowedBuilder.build(), 1024);
        allowedServer.expect(requestTo(HTTPS_LOOPBACK_ENDPOINT))
                .andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));

        String response = allowedClient.complete(
                HTTPS_LOOPBACK_ENDPOINT, true, emptyCredentials(), REQUEST_BODY);

        assertEquals("{\"choices\":[]}", response);
        allowedServer.verify();
    }

    @Test
    void complete_nonSuccessStatusRaisesStatusOnlySanitizedException() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleClient client = new OpenAiCompatibleClient(builder.build(), 1024);
        server.expect(requestTo(PUBLIC_ENDPOINT))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("SENSITIVE_RESPONSE_BODY " + API_KEY));

        try (MockedStatic<AiEgressGuard> ignored = mockStatic(AiEgressGuard.class)) {
            AiProviderException exception = assertThrows(AiProviderException.class,
                    () -> client.complete(PUBLIC_ENDPOINT, false, credentials(), REQUEST_BODY));

            assertEquals("OpenAI-compatible invocation failed with status 401", exception.getMessage());
            assertFalse(String.valueOf(exception).contains(API_KEY));
            assertFalse(String.valueOf(exception).contains("SENSITIVE_RESPONSE_BODY"));
            assertFalse(client.toString().contains(API_KEY));
            assertNull(exception.getCause());
            server.verify();
        }
    }

    @Test
    void complete_oversizedResponseRaisesSanitizedException() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleClient client = new OpenAiCompatibleClient(builder.build(), 8);
        server.expect(requestTo(PUBLIC_ENDPOINT))
                .andRespond(withSuccess("SENSITIVE_RESPONSE_BODY", MediaType.APPLICATION_JSON));

        try (MockedStatic<AiEgressGuard> ignored = mockStatic(AiEgressGuard.class)) {
            AiProviderException exception = assertThrows(AiProviderException.class,
                    () -> client.complete(PUBLIC_ENDPOINT, false, credentials(), REQUEST_BODY));

            assertFalse(String.valueOf(exception).contains(API_KEY));
            assertFalse(String.valueOf(exception).contains("SENSITIVE_RESPONSE_BODY"));
            assertNull(exception.getCause());
            server.verify();
        }
    }

    @Test
    void complete_revalidatesEndpointAndRejectsHeaderInjectionBeforeSend() {
        OpenAiCompatibleClient client = new OpenAiCompatibleClient(RestClient.create(), 1024);

        for (URI endpoint : List.of(
                URI.create("ftp://api.example.test/v1/chat/completions"),
                URI.create("https://user@api.example.test/v1/chat/completions"),
                URI.create("https://api.example.test/v1/chat/completions#fragment"),
                URI.create("https:///v1/chat/completions"))) {
            assertThrows(AiProviderException.class,
                    () -> client.complete(endpoint, false, emptyCredentials(), REQUEST_BODY));
        }

        String injectedKey = API_KEY + "\r\nInjected: true";
        AiProviderException exception = assertThrows(AiProviderException.class,
                () -> client.complete(PUBLIC_ENDPOINT, false,
                        AiCredentials.of(Map.of("apiKey", injectedKey)), REQUEST_BODY));

        assertEquals("Invalid OpenAI-compatible credentials", exception.getMessage());
        assertFalse(String.valueOf(exception).contains(API_KEY));
        assertNull(exception.getCause());
    }

    private static AiCredentials credentials() {
        return AiCredentials.of(Map.of("apiKey", API_KEY));
    }

    private static AiCredentials emptyCredentials() {
        return AiCredentials.of(Map.of());
    }
}
