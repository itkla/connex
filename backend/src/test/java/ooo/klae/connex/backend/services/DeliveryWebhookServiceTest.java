package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import ooo.klae.connex.backend.beans.CampaignDelivery;
import ooo.klae.connex.backend.beans.CampaignDeliveryEvent;
import ooo.klae.connex.backend.beans.CampaignSend;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.delivery.DeliveryChannel;
import ooo.klae.connex.backend.delivery.DeliveryCredentials;
import ooo.klae.connex.backend.delivery.DeliveryEvent;
import ooo.klae.connex.backend.delivery.DeliveryEventType;
import ooo.klae.connex.backend.delivery.DeliveryProviderConfigService;
import ooo.klae.connex.backend.delivery.DeliveryProviderException;
import ooo.klae.connex.backend.delivery.DeliveryProviderRouter;
import ooo.klae.connex.backend.delivery.ProviderEventSource;
import ooo.klae.connex.backend.delivery.ResolvedDeliveryProvider;
import ooo.klae.connex.backend.dto.SuppressionEntryRequest;
import ooo.klae.connex.backend.mappers.CampaignDeliveryMapper;
import ooo.klae.connex.backend.mappers.CampaignSendMapper;

/**
 * Unit tests for webhook ingestion: workspace/provider resolution from the token only, per-event
 * idempotency, hard-bounce/complaint suppression as the system actor, and cross-tenant isolation.
 */
@ExtendWith(MockitoExtension.class)
class DeliveryWebhookServiceTest {

    private static final String PROVIDER = "http_esp";
    private static final int WORKSPACE_A = 11;
    private static final int WORKSPACE_B = 22;

    @Mock private DeliveryProviderConfigService configService;
    @Mock private DeliveryProviderRouter router;
    @Mock private ProviderEventSource eventSource;
    @Mock private CampaignDeliveryMapper campaignDeliveryMapper;
    @Mock private CampaignSendMapper campaignSendMapper;
    @Mock private SuppressionService suppressionService;
    @Mock private ConsentService consentService;
    @Mock private SystemActor systemActor;
    @Mock private AutomationExecutor automationExecutor;

    private DeliveryWebhookService service() {
        return new DeliveryWebhookService(configService, router, campaignDeliveryMapper, campaignSendMapper,
                suppressionService, consentService, systemActor, automationExecutor);
    }

    private ResolvedDeliveryProvider target(int workspaceId) {
        return new ResolvedDeliveryProvider(PROVIDER, DeliveryChannel.EMAIL, workspaceId,
                "https://esp.example.com/send", "no-reply@sender.test", "Sender",
                DeliveryCredentials.of(Map.of("webhookSecret", "whsec")));
    }

    private void resolveTo(int workspaceId, List<DeliveryEvent> events) {
        when(configService.resolveByWebhookToken("tok")).thenReturn(target(workspaceId));
        when(router.eventSourceFor(PROVIDER)).thenReturn(eventSource);
        when(eventSource.translate(any())).thenReturn(events);
    }

    private CampaignDelivery delivery(int workspaceId) {
        CampaignDelivery delivery = new CampaignDelivery();
        delivery.setId(500);
        delivery.setWorkspaceId(workspaceId);
        delivery.setSendId(300);
        delivery.setAddress("recipient@dest.test");
        delivery.setPersonId(42);
        return delivery;
    }

    private void runAsInline() {
        User system = new User();
        system.setId(1);
        when(systemActor.user()).thenReturn(system);
        when(automationExecutor.runAs(anyInt(), any(), eq("system"), any())).thenAnswer(
                invocation -> ((Supplier<?>) invocation.getArgument(3)).get());
    }

    @Test
    void ingest_appliesADeliveredEventAndAdvancesStatus() {
        resolveTo(WORKSPACE_A, List.of(new DeliveryEvent("m1", "e1", DeliveryEventType.DELIVERED, null, null)));
        when(campaignDeliveryMapper.findByProviderMessage(WORKSPACE_A, PROVIDER, "m1"))
                .thenReturn(delivery(WORKSPACE_A));

        assertEquals(1, service().ingest(PROVIDER, "tok", new byte[0], Map.of()));

        verify(eventSource).verifySignature(any(), any(), any());
        verify(campaignDeliveryMapper).applyProviderStatus(WORKSPACE_A, 500, "delivered", List.of("dispatched"));
        verifyNoInteractions(suppressionService, consentService);
    }

    @Test
    void ingest_hardBounceSuppressesAndRevokesConsentAsSystemActor() {
        resolveTo(WORKSPACE_A, List.of(new DeliveryEvent("m1", "e1", DeliveryEventType.BOUNCED, null, "bounced")));
        when(campaignDeliveryMapper.findByProviderMessage(WORKSPACE_A, PROVIDER, "m1"))
                .thenReturn(delivery(WORKSPACE_A));
        CampaignSend send = new CampaignSend();
        send.setChannel("email");
        send.setPurpose("marketing");
        when(campaignSendMapper.getSend(WORKSPACE_A, 300)).thenReturn(send);
        runAsInline();

        assertEquals(1, service().ingest(PROVIDER, "tok", new byte[0], Map.of()));

        verify(campaignDeliveryMapper).applyProviderStatus(
                WORKSPACE_A, 500, "bounced", List.of("dispatched", "delivered"));
        org.mockito.ArgumentCaptor<SuppressionEntryRequest> captor =
                org.mockito.ArgumentCaptor.forClass(SuppressionEntryRequest.class);
        verify(suppressionService).add(captor.capture());
        assertEquals("hard_bounce", captor.getValue().reason());
        verify(consentService).setForPerson(eq(42), any());
    }

    @Test
    void ingest_replayedProviderEventIsInsertedOnceAndDoesNotAdvanceStatus() {
        resolveTo(WORKSPACE_A, List.of(new DeliveryEvent("m1", "e1", DeliveryEventType.DELIVERED, null, null)));
        when(campaignDeliveryMapper.findByProviderMessage(WORKSPACE_A, PROVIDER, "m1"))
                .thenReturn(delivery(WORKSPACE_A));
        org.mockito.Mockito.doThrow(new DuplicateKeyException("dup"))
                .when(campaignDeliveryMapper).insertEvent(any(CampaignDeliveryEvent.class));

        assertEquals(0, service().ingest(PROVIDER, "tok", new byte[0], Map.of()));

        verify(campaignDeliveryMapper, never()).applyProviderStatus(anyInt(), anyInt(), any(), any());
    }

    @Test
    void ingest_nullEventIdBounceDeduplicatesOnEventTypeAndDoesNotResuppress() {
        resolveTo(WORKSPACE_A, List.of(new DeliveryEvent("m1", null, DeliveryEventType.BOUNCED, null, "bounced")));
        when(campaignDeliveryMapper.findByProviderMessage(WORKSPACE_A, PROVIDER, "m1"))
                .thenReturn(delivery(WORKSPACE_A));
        when(campaignDeliveryMapper.hasEvent(WORKSPACE_A, 500, "bounced")).thenReturn(true);

        assertEquals(0, service().ingest(PROVIDER, "tok", new byte[0], Map.of()));

        verify(campaignDeliveryMapper, never()).insertEvent(any());
        verify(campaignDeliveryMapper, never()).applyProviderStatus(anyInt(), anyInt(), any(), any());
        verifyNoInteractions(suppressionService, consentService);
    }

    @Test
    void ingest_nullEventIdFirstDeliveryPassesTheEventTypeGateAndAdvancesStatus() {
        resolveTo(WORKSPACE_A, List.of(new DeliveryEvent("m1", null, DeliveryEventType.DELIVERED, null, null)));
        when(campaignDeliveryMapper.findByProviderMessage(WORKSPACE_A, PROVIDER, "m1"))
                .thenReturn(delivery(WORKSPACE_A));
        when(campaignDeliveryMapper.hasEvent(WORKSPACE_A, 500, "delivered")).thenReturn(false);

        assertEquals(1, service().ingest(PROVIDER, "tok", new byte[0], Map.of()));

        verify(campaignDeliveryMapper).insertEvent(any(CampaignDeliveryEvent.class));
        verify(campaignDeliveryMapper).applyProviderStatus(WORKSPACE_A, 500, "delivered", List.of("dispatched"));
    }

    @Test
    void ingest_resolvesWorkspaceFromTokenAndCannotTouchAnotherTenantsDelivery() {
        resolveTo(WORKSPACE_B, List.of(new DeliveryEvent("m1", "e1", DeliveryEventType.DELIVERED, null, null)));
        when(campaignDeliveryMapper.findByProviderMessage(WORKSPACE_B, PROVIDER, "m1")).thenReturn(null);

        assertEquals(0, service().ingest(PROVIDER, "tok", new byte[0], Map.of()));

        verify(campaignDeliveryMapper).findByProviderMessage(WORKSPACE_B, PROVIDER, "m1");
        verify(campaignDeliveryMapper, never()).findByProviderMessage(eq(WORKSPACE_A), any(), any());
        verify(campaignDeliveryMapper, never()).insertEvent(any());
    }

    @Test
    void ingest_rejectsAProviderThatDoesNotMatchTheToken() {
        when(configService.resolveByWebhookToken("tok")).thenReturn(target(WORKSPACE_A));

        assertThrows(DeliveryProviderException.class,
                () -> service().ingest("other_esp", "tok", new byte[0], Map.of()));

        verifyNoInteractions(router);
    }
}
