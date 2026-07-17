package ooo.klae.connex.backend.delivery.provider.list;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mockStatic;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

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
import ooo.klae.connex.backend.delivery.AudienceMember;
import ooo.klae.connex.backend.delivery.AudiencePush;
import ooo.klae.connex.backend.delivery.AudiencePushResult;
import ooo.klae.connex.backend.delivery.DeliveryChannel;
import ooo.klae.connex.backend.delivery.DeliveryCredentials;
import ooo.klae.connex.backend.delivery.ResolvedDeliveryProvider;

import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for the HTTP list connector: the push request/response mapping over a stubbed HTTP layer.
 * No test touches the network.
 */
class HttpListConnectorTest {

    private static final String ENDPOINT = "https://lists.example.com/v1/lists/add";
    private static final String API_KEY = "list_api_key_123456";
    private final ObjectMapper objectMapper = new ObjectMapper();

    private ResolvedDeliveryProvider connectorTarget() {
        return new ResolvedDeliveryProvider(
                HttpListConnector.PROVIDER_ID, DeliveryChannel.EMAIL, 7, ENDPOINT, null, null,
                DeliveryCredentials.of(Map.of("apiKey", API_KEY)));
    }

    private AudiencePush push() {
        return new AudiencePush("list-9", List.of(
                new AudienceMember("a@dest.test", "Ada", "Lovelace"),
                new AudienceMember("b@dest.test", null, null)));
    }

    @Test
    void pushAudience_mapsSuccessResponseToReceiptCounts() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpListConnector connector = new HttpListConnector(builder.build(), 1024, objectMapper);
        server.expect(requestTo(ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + API_KEY))
                .andExpect(jsonPath("$.listId").value("list-9"))
                .andExpect(jsonPath("$.members[0].email").value("a@dest.test"))
                .andExpect(jsonPath("$.members[0].firstName").value("Ada"))
                .andRespond(withSuccess("{\"added\":2,\"failed\":0}", MediaType.APPLICATION_JSON));

        try (MockedStatic<AiEgressGuard> guard = mockStatic(AiEgressGuard.class)) {
            AudiencePushResult result = connector.pushAudience(connectorTarget(), push());

            assertEquals(2, result.pushedCount());
            assertEquals(0, result.failedCount());
            guard.verify(() -> AiEgressGuard.resolveFetchableHost("lists.example.com", false));
            server.verify();
        }
    }

    @Test
    void pushAudience_mapsNonSuccessResponseToAllFailed() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpListConnector connector = new HttpListConnector(builder.build(), 1024, objectMapper);
        server.expect(requestTo(ENDPOINT))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"nope\"}"));

        try (MockedStatic<AiEgressGuard> ignored = mockStatic(AiEgressGuard.class)) {
            AudiencePushResult result = connector.pushAudience(connectorTarget(), push());

            assertEquals(0, result.pushedCount());
            assertEquals(2, result.failedCount());
            server.verify();
        }
    }

    @Test
    void pushAudience_recordsFailedWhenA2xxBodyIsUnparseable() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpListConnector connector = new HttpListConnector(builder.build(), 1024, objectMapper);
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess("not json", MediaType.APPLICATION_JSON));

        try (MockedStatic<AiEgressGuard> ignored = mockStatic(AiEgressGuard.class)) {
            AudiencePushResult result = connector.pushAudience(connectorTarget(), push());

            assertEquals(0, result.pushedCount());
            assertEquals(2, result.failedCount());
            server.verify();
        }
    }

    @Test
    void pushAudience_reportsPartialFailuresFromTheResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpListConnector connector = new HttpListConnector(builder.build(), 1024, objectMapper);
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess("{\"added\":1,\"failed\":1}", MediaType.APPLICATION_JSON));

        try (MockedStatic<AiEgressGuard> ignored = mockStatic(AiEgressGuard.class)) {
            AudiencePushResult result = connector.pushAudience(connectorTarget(), push());

            assertEquals(1, result.pushedCount());
            assertEquals(1, result.failedCount());
            server.verify();
        }
    }
}
