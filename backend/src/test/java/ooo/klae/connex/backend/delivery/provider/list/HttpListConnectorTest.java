package ooo.klae.connex.backend.delivery.provider.list;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mockStatic;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;

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
                new AudienceMember("b@dest.test", null, null)), "campaign-export-71-attempt-1");
    }

    @Test
    void pushAudience_mapsSuccessResponseToReceiptCounts() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpListConnector connector = new HttpListConnector(builder.build(), 1024, objectMapper);
        server.expect(requestTo(ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + API_KEY))
                .andExpect(header("Idempotency-Key", "campaign-export-71-attempt-1"))
                .andExpect(jsonPath("$.listId").value("list-9"))
                .andExpect(jsonPath("$.members[0].email").value("a@dest.test"))
                .andExpect(jsonPath("$.members[0].firstName").value("Ada"))
                .andRespond(withSuccess("{\"added\":2,\"failed\":0}", MediaType.APPLICATION_JSON));

        try (MockedStatic<AiEgressGuard> guard = mockStatic(AiEgressGuard.class)) {
            AudiencePushResult result = connector.pushAudience(connectorTarget(), push());

            assertEquals(2, result.pushedCount());
            assertEquals(0, result.failedCount());
            assertEquals(AudiencePushResult.Outcome.CONFIRMED, result.outcome());
            guard.verify(() -> AiEgressGuard.resolveFetchableHost("lists.example.com", false));
            server.verify();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 400, 401, 403, 404, 422 })
    void pushAudience_classifiesGenericPostSend4xxResponsesAsAmbiguous(int statusCode) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpListConnector connector = new HttpListConnector(builder.build(), 1024, objectMapper);
        server.expect(requestTo(ENDPOINT))
                .andRespond(withStatus(HttpStatus.valueOf(statusCode))
                        .contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"nope\"}"));

        try (MockedStatic<AiEgressGuard> ignored = mockStatic(AiEgressGuard.class)) {
            AudiencePushResult result = connector.pushAudience(connectorTarget(), push());

            assertEquals(0, result.pushedCount());
            assertEquals(2, result.failedCount());
            assertEquals(AudiencePushResult.Outcome.AMBIGUOUS, result.outcome());
            server.verify();
        }
    }

    @Test
    void pushAudience_classifiesA4xxWithReportedAcceptanceAsAmbiguous() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpListConnector connector = new HttpListConnector(builder.build(), 1024, objectMapper);
        server.expect(requestTo(ENDPOINT))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"added\":1,\"failed\":1}"));

        try (MockedStatic<AiEgressGuard> ignored = mockStatic(AiEgressGuard.class)) {
            AudiencePushResult result = connector.pushAudience(connectorTarget(), push());

            assertEquals(AudiencePushResult.Outcome.AMBIGUOUS, result.outcome());
            server.verify();
        }
    }

    @Test
    void pushAudience_classifiesAPostSideEffect500AsAmbiguous() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpListConnector connector = new HttpListConnector(builder.build(), 1024, objectMapper);
        server.expect(requestTo(ENDPOINT))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"added\":2,\"failed\":0}"));

        try (MockedStatic<AiEgressGuard> ignored = mockStatic(AiEgressGuard.class)) {
            AudiencePushResult result = connector.pushAudience(connectorTarget(), push());

            assertEquals(AudiencePushResult.Outcome.AMBIGUOUS, result.outcome());
            server.verify();
        }
    }

    @Test
    void pushAudience_classifiesAProxy502AsAmbiguous() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpListConnector connector = new HttpListConnector(builder.build(), 1024, objectMapper);
        server.expect(requestTo(ENDPOINT))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY)
                        .contentType(MediaType.TEXT_PLAIN)
                        .body("upstream response unavailable"));

        try (MockedStatic<AiEgressGuard> ignored = mockStatic(AiEgressGuard.class)) {
            AudiencePushResult result = connector.pushAudience(connectorTarget(), push());

            assertEquals(AudiencePushResult.Outcome.AMBIGUOUS, result.outcome());
            server.verify();
        }
    }

    @Test
    void pushAudience_classifiesATimeoutAfterSendAsAmbiguous() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpListConnector connector = new HttpListConnector(builder.build(), 1024, objectMapper);
        server.expect(requestTo(ENDPOINT)).andRespond(request -> {
            throw new ResourceAccessException(
                    "Response timed out", new SocketTimeoutException("Read timed out"));
        });

        try (MockedStatic<AiEgressGuard> ignored = mockStatic(AiEgressGuard.class)) {
            AudiencePushResult result = connector.pushAudience(connectorTarget(), push());

            assertEquals(AudiencePushResult.Outcome.AMBIGUOUS, result.outcome());
            server.verify();
        }
    }

    @Test
    void pushAudience_classifiesAConnectionRefusalBeforeSendAsDefiniteNoSideEffect() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpListConnector connector = new HttpListConnector(builder.build(), 1024, objectMapper);
        server.expect(requestTo(ENDPOINT)).andRespond(request -> {
            throw new ResourceAccessException(
                    "Connection refused", new ConnectException("Connection refused"));
        });

        try (MockedStatic<AiEgressGuard> ignored = mockStatic(AiEgressGuard.class)) {
            AudiencePushResult result = connector.pushAudience(connectorTarget(), push());

            assertEquals(AudiencePushResult.Outcome.DEFINITE_NO_SIDE_EFFECT, result.outcome());
            server.verify();
        }
    }

    @Test
    void pushAudience_reportsAmbiguousOutcomeWhenA2xxBodyIsUnparseable() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpListConnector connector = new HttpListConnector(builder.build(), 1024, objectMapper);
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess("not json", MediaType.APPLICATION_JSON));

        try (MockedStatic<AiEgressGuard> ignored = mockStatic(AiEgressGuard.class)) {
            AudiencePushResult result = connector.pushAudience(connectorTarget(), push());

            assertEquals(0, result.pushedCount());
            assertEquals(2, result.failedCount());
            assertEquals(AudiencePushResult.Outcome.AMBIGUOUS, result.outcome());
            server.verify();
        }
    }

    @Test
    void pushAudience_classifiesA2xxResponseWithMissingCountersAsAmbiguous() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpListConnector connector = new HttpListConnector(builder.build(), 1024, objectMapper);
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess("{\"added\":2}", MediaType.APPLICATION_JSON));

        try (MockedStatic<AiEgressGuard> ignored = mockStatic(AiEgressGuard.class)) {
            AudiencePushResult result = connector.pushAudience(connectorTarget(), push());

            assertEquals(AudiencePushResult.Outcome.AMBIGUOUS, result.outcome());
            server.verify();
        }
    }

    @Test
    void pushAudience_classifiesA2xxResponseWithInconsistentCountersAsAmbiguous() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpListConnector connector = new HttpListConnector(builder.build(), 1024, objectMapper);
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess("{\"added\":2,\"failed\":1}", MediaType.APPLICATION_JSON));

        try (MockedStatic<AiEgressGuard> ignored = mockStatic(AiEgressGuard.class)) {
            AudiencePushResult result = connector.pushAudience(connectorTarget(), push());

            assertEquals(AudiencePushResult.Outcome.AMBIGUOUS, result.outcome());
            server.verify();
        }
    }

    @Test
    void pushAudience_classifiesA2xxResponseWithNegativeCountersAsAmbiguous() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpListConnector connector = new HttpListConnector(builder.build(), 1024, objectMapper);
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess("{\"added\":-1,\"failed\":3}", MediaType.APPLICATION_JSON));

        try (MockedStatic<AiEgressGuard> ignored = mockStatic(AiEgressGuard.class)) {
            AudiencePushResult result = connector.pushAudience(connectorTarget(), push());

            assertEquals(AudiencePushResult.Outcome.AMBIGUOUS, result.outcome());
            server.verify();
        }
    }

    @Test
    void pushAudience_classifiesA2xxResponseWithOverflowingCountersAsAmbiguous() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpListConnector connector = new HttpListConnector(builder.build(), 1024, objectMapper);
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess(
                        "{\"added\":2147483648,\"failed\":0}", MediaType.APPLICATION_JSON));

        try (MockedStatic<AiEgressGuard> ignored = mockStatic(AiEgressGuard.class)) {
            AudiencePushResult result = connector.pushAudience(connectorTarget(), push());

            assertEquals(AudiencePushResult.Outcome.AMBIGUOUS, result.outcome());
            server.verify();
        }
    }

    @Test
    void pushAudience_returnsAConfirmedZeroAcceptance() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpListConnector connector = new HttpListConnector(builder.build(), 1024, objectMapper);
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess("{\"added\":0,\"failed\":2}", MediaType.APPLICATION_JSON));

        try (MockedStatic<AiEgressGuard> ignored = mockStatic(AiEgressGuard.class)) {
            AudiencePushResult result = connector.pushAudience(connectorTarget(), push());

            assertEquals(0, result.pushedCount());
            assertEquals(2, result.failedCount());
            assertEquals(AudiencePushResult.Outcome.CONFIRMED, result.outcome());
            server.verify();
        }
    }

    @Test
    void pushAudience_classifiesAnUnexpectedTransportExceptionAsAmbiguous() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpListConnector connector = new HttpListConnector(builder.build(), 1024, objectMapper);
        server.expect(requestTo(ENDPOINT)).andRespond(request -> {
            throw new IllegalStateException("unexpected transport failure");
        });

        try (MockedStatic<AiEgressGuard> ignored = mockStatic(AiEgressGuard.class)) {
            AudiencePushResult result = connector.pushAudience(connectorTarget(), push());

            assertEquals(AudiencePushResult.Outcome.AMBIGUOUS, result.outcome());
            server.verify();
        }
    }

    @Test
    void pushAudience_classifiesAnOtherHttpStatusAsAmbiguous() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpListConnector connector = new HttpListConnector(builder.build(), 1024, objectMapper);
        server.expect(requestTo(ENDPOINT))
                .andRespond(withStatus(HttpStatus.FOUND)
                        .contentType(MediaType.TEXT_PLAIN).body("redirect refused"));

        try (MockedStatic<AiEgressGuard> ignored = mockStatic(AiEgressGuard.class)) {
            AudiencePushResult result = connector.pushAudience(connectorTarget(), push());

            assertEquals(AudiencePushResult.Outcome.AMBIGUOUS, result.outcome());
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
