package ooo.klae.connex.backend.delivery.provider.sms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import ooo.klae.connex.backend.ai.egress.AiEgressGuard;
import ooo.klae.connex.backend.delivery.DeliveryChannel;
import ooo.klae.connex.backend.delivery.DeliveryCredentials;
import ooo.klae.connex.backend.delivery.DeliveryRequest;
import ooo.klae.connex.backend.delivery.DispatchReceipt;
import ooo.klae.connex.backend.delivery.DispatchStatus;
import ooo.klae.connex.backend.delivery.RenderedMessage;
import ooo.klae.connex.backend.delivery.ResolvedDeliveryProvider;

import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for the SMS gateway adapter: the send request/response mapping over a stubbed HTTP layer,
 * the text-only payload contract, and the fail-closed rejection paths. No test touches the network.
 */
class SmsHttpDeliveryProviderTest {

    private static final String ENDPOINT = "https://sms.example.com/v1/messages";
    private static final String API_KEY = "sms_api_key_123456";
    private static final String SENDER_ID = "Connex";
    private static final String RECIPIENT = "+815012345678";
    private final ObjectMapper objectMapper = new ObjectMapper();

    private ResolvedDeliveryProvider smsTarget(String endpoint, Map<String, String> credentials) {
        return new ResolvedDeliveryProvider(
                SmsHttpDeliveryProvider.PROVIDER_ID, DeliveryChannel.SMS, 7, endpoint,
                SENDER_ID, "Connex", DeliveryCredentials.of(credentials));
    }

    private ResolvedDeliveryProvider smsTarget() {
        return smsTarget(ENDPOINT, Map.of("apiKey", API_KEY));
    }

    private DeliveryRequest request() {
        return new DeliveryRequest(DeliveryChannel.SMS, RECIPIENT,
                new RenderedMessage("Ignored subject", "<p>Ignored html</p>", "Hi there"), 42, "send:1:2");
    }

    @Test
    void genericAdapterDoesNotPromiseEndpointIdempotency() {
        SmsHttpDeliveryProvider provider =
                new SmsHttpDeliveryProvider(RestClient.create(), 1024, objectMapper);
        try {
            assertFalse(provider.capabilities().idempotentSubmission());
        } finally {
            provider.shutdown();
        }
    }

    @Test
    void dispatch_mapsSuccessResponseToSentReceiptWithProviderMessageId() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SmsHttpDeliveryProvider provider = new SmsHttpDeliveryProvider(builder.build(), 1024, objectMapper);
        server.expect(requestTo(ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + API_KEY))
                .andExpect(header("Idempotency-Key", "send:1:2"))
                .andRespond(withSuccess("{\"messageId\":\"sms-777\"}", MediaType.APPLICATION_JSON));

        try (MockedStatic<AiEgressGuard> guard = mockStatic(AiEgressGuard.class)) {
            DispatchReceipt receipt = provider.dispatch(smsTarget(), request());

            assertEquals(DispatchStatus.SENT, receipt.status());
            assertEquals("sms-777", receipt.providerMessageId());
            guard.verify(() -> AiEgressGuard.resolveFetchableHost("sms.example.com", false));
            server.verify();
        }
    }

    @Test
    void dispatch_sendsOnlyTheTextBodyAndNeverTheSubjectOrHtml() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SmsHttpDeliveryProvider provider = new SmsHttpDeliveryProvider(builder.build(), 1024, objectMapper);
        server.expect(requestTo(ENDPOINT))
                .andExpect(jsonPath("$.to").value(RECIPIENT))
                .andExpect(jsonPath("$.from").value(SENDER_ID))
                .andExpect(jsonPath("$.text").value("Hi there"))
                .andExpect(jsonPath("$.subject").doesNotExist())
                .andExpect(jsonPath("$.html").doesNotExist())
                .andRespond(withSuccess("{\"messageId\":\"sms-1\"}", MediaType.APPLICATION_JSON));

        try (MockedStatic<AiEgressGuard> ignored = mockStatic(AiEgressGuard.class)) {
            assertEquals(DispatchStatus.SENT, provider.dispatch(smsTarget(), request()).status());
            server.verify();
        }
    }

    @Test
    void dispatch_mapsNonSuccessResponseToRejectedReceipt() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SmsHttpDeliveryProvider provider = new SmsHttpDeliveryProvider(builder.build(), 1024, objectMapper);
        server.expect(requestTo(ENDPOINT))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"nope\"}"));

        try (MockedStatic<AiEgressGuard> ignored = mockStatic(AiEgressGuard.class)) {
            DispatchReceipt receipt = provider.dispatch(smsTarget(), request());

            assertEquals(DispatchStatus.REJECTED, receipt.status());
            assertNull(receipt.providerMessageId());
            server.verify();
        }
    }

    @Test
    void dispatch_mapsASuccessWithoutAMessageIdToSentWithNoProviderId() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SmsHttpDeliveryProvider provider = new SmsHttpDeliveryProvider(builder.build(), 1024, objectMapper);
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess("{\"accepted\":true}", MediaType.APPLICATION_JSON));

        try (MockedStatic<AiEgressGuard> ignored = mockStatic(AiEgressGuard.class)) {
            DispatchReceipt receipt = provider.dispatch(smsTarget(), request());

            assertEquals(DispatchStatus.SENT, receipt.status());
            assertNull(receipt.providerMessageId());
            server.verify();
        }
    }

    @Test
    void dispatch_rejectsWithoutCallingTheGatewayWhenTheTargetIsUnusable() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SmsHttpDeliveryProvider provider = new SmsHttpDeliveryProvider(builder.build(), 1024, objectMapper);

        try (MockedStatic<AiEgressGuard> ignored = mockStatic(AiEgressGuard.class)) {
            assertEquals(DispatchStatus.REJECTED,
                    provider.dispatch(smsTarget(null, Map.of("apiKey", API_KEY)), request()).status());
            assertEquals(DispatchStatus.REJECTED, provider.dispatch(
                    smsTarget("http://sms.example.com/v1/messages", Map.of("apiKey", API_KEY)), request()).status());
            assertEquals(DispatchStatus.REJECTED, provider.dispatch(smsTarget(ENDPOINT, Map.of()), request()).status());
            assertEquals(DispatchStatus.REJECTED, provider.dispatch(smsTarget(), new DeliveryRequest(
                    DeliveryChannel.SMS, RECIPIENT,
                    new RenderedMessage("Subject", "<p>Html</p>", null), 42, "send:1:2")).status());
            server.verify();
        }
    }

    @Test
    void providerRegistersOnlyTheSmsChannel() {
        SmsHttpDeliveryProvider provider = new SmsHttpDeliveryProvider((RestClient) null, 1024, objectMapper);

        assertEquals("sms_http", provider.providerId());
        assertEquals(java.util.Set.of(DeliveryChannel.SMS), provider.channels());
    }
}
