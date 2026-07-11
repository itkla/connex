package ooo.klae.connex.backend.ai.provider.vertex;

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

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import ooo.klae.connex.backend.ai.egress.AiEgressGuard;
import ooo.klae.connex.backend.ai.provider.AiProviderException;

class VertexClientTest {
    private static final URI ENDPOINT = URI.create("https://us-central1-aiplatform.googleapis.com/v1/projects/"
            + "connex-prod1/locations/us-central1/publishers/google/models/gemini-2.5-flash:generateContent");
    private static final String ACCESS_TOKEN = "vertex_access_token_secret";
    private static final String REQUEST_BODY = "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"Hello?\"}]}]}";

    @Test
    void complete_sendsBearerTokenAndBodyAfterEgressVetting() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        VertexClient client = new VertexClient(builder.build(), 1024);
        server.expect(requestTo(ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + ACCESS_TOKEN))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json(REQUEST_BODY))
                .andRespond(withSuccess("{\"candidates\":[]}", MediaType.APPLICATION_JSON));

        try (MockedStatic<AiEgressGuard> guard = mockStatic(AiEgressGuard.class)) {
            String response = client.complete(ENDPOINT, ACCESS_TOKEN, REQUEST_BODY);

            assertEquals("{\"candidates\":[]}", response);
            guard.verify(() -> AiEgressGuard.requireFetchableHost(
                    "us-central1-aiplatform.googleapis.com", false));
            server.verify();
        }
    }

    @Test
    void complete_nonSuccessStatusRaisesSanitizedException() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        VertexClient client = new VertexClient(builder.build(), 1024);
        server.expect(requestTo(ENDPOINT))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("SENSITIVE_RESPONSE_BODY " + ACCESS_TOKEN));

        try (MockedStatic<AiEgressGuard> ignored = mockStatic(AiEgressGuard.class)) {
            AiProviderException exception = assertThrows(AiProviderException.class,
                    () -> client.complete(ENDPOINT, ACCESS_TOKEN, REQUEST_BODY));

            assertEquals("Vertex invocation failed with status 403", exception.getMessage());
            assertFalse(String.valueOf(exception).contains(ACCESS_TOKEN));
            assertFalse(String.valueOf(exception).contains("SENSITIVE_RESPONSE_BODY"));
            assertFalse(client.toString().contains(ACCESS_TOKEN));
            assertNull(exception.getCause());
            server.verify();
        }
    }

    @Test
    void complete_oversizedResponseRaisesSanitizedException() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        VertexClient client = new VertexClient(builder.build(), 8);
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess("SENSITIVE_RESPONSE_BODY", MediaType.APPLICATION_JSON));

        try (MockedStatic<AiEgressGuard> ignored = mockStatic(AiEgressGuard.class)) {
            AiProviderException exception = assertThrows(AiProviderException.class,
                    () -> client.complete(ENDPOINT, ACCESS_TOKEN, REQUEST_BODY));

            assertFalse(String.valueOf(exception).contains("SENSITIVE_RESPONSE_BODY"));
            assertFalse(String.valueOf(exception).contains(ACCESS_TOKEN));
            assertNull(exception.getCause());
            server.verify();
        }
    }

    @Test
    void complete_rejectsUnconstructedEndpointAndHeaderInjectionBeforeSend() {
        VertexClient client = new VertexClient(RestClient.create(), 1024);

        assertThrows(AiProviderException.class, () -> client.complete(
                URI.create("https://us-central1-aiplatform.googleapis.com.evil.test/v1"),
                ACCESS_TOKEN,
                REQUEST_BODY));
        assertThrows(AiProviderException.class, () -> client.complete(
                ENDPOINT,
                ACCESS_TOKEN + "\r\nInjected: true",
                REQUEST_BODY));
    }
}
