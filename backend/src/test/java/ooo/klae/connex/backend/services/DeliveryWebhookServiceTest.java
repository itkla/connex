package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

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
import ooo.klae.connex.backend.util.OneTimeTokenDigest;
import ooo.klae.connex.backend.mappers.CampaignDeliveryMapper;
import ooo.klae.connex.backend.mappers.CampaignSendMapper;

/**
 * Unit tests for webhook ingestion: workspace/provider resolution from the token only, per-event
 * idempotency, hard-bounce/complaint suppression as the system actor, cross-tenant isolation, and
 * the workspace scope being entered before the transaction opens (#994).
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
    @Mock private TransactionTemplate transactionTemplate;

    private DeliveryWebhookService service() {
        return new DeliveryWebhookService(configService, router, campaignDeliveryMapper, campaignSendMapper,
                suppressionService, consentService, systemActor, automationExecutor, transactionTemplate);
    }

    private ResolvedDeliveryProvider target(int workspaceId) {
        return new ResolvedDeliveryProvider(PROVIDER, DeliveryChannel.EMAIL, workspaceId,
                "https://esp.example.com/send", "no-reply@sender.test", "Sender",
                DeliveryCredentials.of(Map.of("webhookSecret", "whsec")));
    }

    private void resolveTo(int workspaceId, List<DeliveryEvent> events) {
        resolveTokenTo(workspaceId);
        when(router.eventSourceFor(PROVIDER)).thenReturn(eventSource);
        when(eventSource.translate(any())).thenReturn(events);
    }

    private void resolveTokenTo(int workspaceId) {
        when(configService.resolveByWebhookToken(eq("tok"), any())).thenAnswer(invocation -> {
            BiFunction<Integer, Supplier<String>, String> secretReader = invocation.getArgument(1);
            assertEquals("whsec", secretReader.apply(workspaceId, () -> "whsec"));
            return target(workspaceId);
        });
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

    private void scopeInline() {
        User system = new User();
        system.setId(1);
        when(systemActor.user()).thenReturn(system);
        when(automationExecutor.runAs(anyInt(), any(), eq("system"),
                eq(AutomationScope.Origin.task(AutomationScope.Source.DELIVERY_WEBHOOK, "system")), any())).thenAnswer(
                invocation -> ((Supplier<?>) invocation.getArgument(4)).get());
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<?>) invocation.getArgument(0))
                        .doInTransaction(new SimpleTransactionStatus()));
    }

    @Test
    void ingest_entersTheWorkspaceScopeBeforeOpeningTheTransactionOrReadingTenantData() {
        resolveTo(WORKSPACE_A, List.of(new DeliveryEvent("m1", "e1", DeliveryEventType.DELIVERED, null, null)));
        when(campaignDeliveryMapper.findByProviderMessage(WORKSPACE_A, PROVIDER, "m1"))
                .thenReturn(delivery(WORKSPACE_A));
        scopeInline();

        assertEquals(1, service().ingest(PROVIDER, "tok", new byte[0], Map.of()));

        InOrder ordered = inOrder(automationExecutor, transactionTemplate, campaignDeliveryMapper);
        ordered.verify(automationExecutor, times(2)).runAs(eq(WORKSPACE_A), any(), eq("system"),
                eq(AutomationScope.Origin.task(AutomationScope.Source.DELIVERY_WEBHOOK, "system")), any());
        ordered.verify(transactionTemplate).execute(any());
        ordered.verify(campaignDeliveryMapper).findByProviderMessage(WORKSPACE_A, PROVIDER, "m1");
    }

    @Test
    void ingest_appliesADeliveredEventAndAdvancesStatus() {
        resolveTo(WORKSPACE_A, List.of(new DeliveryEvent("m1", "e1", DeliveryEventType.DELIVERED, null, null)));
        when(campaignDeliveryMapper.findByProviderMessage(WORKSPACE_A, PROVIDER, "m1"))
                .thenReturn(delivery(WORKSPACE_A));
        scopeInline();

        assertEquals(1, service().ingest(PROVIDER, "tok", new byte[0], Map.of()));

        verify(eventSource).verifySignature(any(), any(), any());
        verify(campaignDeliveryMapper).applyProviderStatus(
                WORKSPACE_A, 500, "delivered", List.of("dispatched", "failed"));
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
        scopeInline();

        assertEquals(1, service().ingest(PROVIDER, "tok", new byte[0], Map.of()));

        verify(campaignDeliveryMapper).applyProviderStatus(
                WORKSPACE_A, 500, "bounced", List.of("dispatched", "delivered", "failed"));
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
        scopeInline();

        assertEquals(0, service().ingest(PROVIDER, "tok", new byte[0], Map.of()));

        verify(campaignDeliveryMapper, never()).applyProviderStatus(anyInt(), anyInt(), any(), any());
    }

    @Test
    void ingest_nullEventIdBounceDeduplicatesOnEventTypeAndDoesNotResuppress() {
        resolveTo(WORKSPACE_A, List.of(new DeliveryEvent("m1", null, DeliveryEventType.BOUNCED, null, "bounced")));
        when(campaignDeliveryMapper.findByProviderMessage(WORKSPACE_A, PROVIDER, "m1"))
                .thenReturn(delivery(WORKSPACE_A));
        when(campaignDeliveryMapper.hasEvent(WORKSPACE_A, 500, "bounced")).thenReturn(true);
        scopeInline();

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
        scopeInline();

        assertEquals(1, service().ingest(PROVIDER, "tok", new byte[0], Map.of()));

        verify(campaignDeliveryMapper).insertEvent(any(CampaignDeliveryEvent.class));
        verify(campaignDeliveryMapper).applyProviderStatus(
                WORKSPACE_A, 500, "delivered", List.of("dispatched", "failed"));
    }

    @Test
    void ingest_resolvesWorkspaceFromTokenAndCannotTouchAnotherTenantsDelivery() {
        resolveTo(WORKSPACE_B, List.of(new DeliveryEvent("m1", "e1", DeliveryEventType.DELIVERED, null, null)));
        when(campaignDeliveryMapper.findByProviderMessage(WORKSPACE_B, PROVIDER, "m1")).thenReturn(null);
        scopeInline();

        assertEquals(0, service().ingest(PROVIDER, "tok", new byte[0], Map.of()));

        verify(automationExecutor, times(2)).runAs(eq(WORKSPACE_B), any(), eq("system"),
                eq(AutomationScope.Origin.task(AutomationScope.Source.DELIVERY_WEBHOOK, "system")), any());
        verify(automationExecutor, never()).runAs(eq(WORKSPACE_A), any(), any(), any(), any());
        verify(campaignDeliveryMapper).findByProviderMessage(WORKSPACE_B, PROVIDER, "m1");
        verify(campaignDeliveryMapper, never()).findByProviderMessage(eq(WORKSPACE_A), any(), any());
        verify(campaignDeliveryMapper, never()).insertEvent(any());
    }

    @Test
    void ingestCanonicalizesMessageAndEventIdsBeforeLookupAndDedupeAndScrubsDetail() {
        String bearer = "ab09".repeat(16);
        String digest = OneTimeTokenDigest.sha256(bearer);
        resolveTo(WORKSPACE_A, List.of(new DeliveryEvent("message:0" + "Ab09".repeat(16) + "f",
                "event:" + bearer.toUpperCase(java.util.Locale.ROOT), DeliveryEventType.DELIVERED,
                null, "reason " + bearer + " retained")));
        when(campaignDeliveryMapper.getUnsubscribeTokenDeliveryId(eq(WORKSPACE_A), any()))
                .thenAnswer(invocation -> digest.equals(invocation.getArgument(1)) ? Integer.valueOf(500) : null);
        when(campaignDeliveryMapper.findByProviderMessage(WORKSPACE_A, PROVIDER, "message:0" + digest + "f"))
                .thenReturn(delivery(WORKSPACE_A));
        org.mockito.Mockito.doAnswer(invocation -> {
            CampaignDeliveryEvent row = invocation.getArgument(0);
            assertEquals("event:" + digest, row.getProviderEventId());
            assertEquals("reason [redacted] retained", row.getDetail());
            return 1;
        }).doThrow(new DuplicateKeyException("duplicate canonical key"))
                .when(campaignDeliveryMapper).insertEvent(any(CampaignDeliveryEvent.class));
        scopeInline();

        assertEquals(1, service().ingest(PROVIDER, "tok", new byte[0], Map.of()));
        assertEquals(0, service().ingest(PROVIDER, "tok", new byte[0], Map.of()));
        verify(campaignDeliveryMapper, times(2)).findByProviderMessage(
                WORKSPACE_A, PROVIDER, "message:0" + digest + "f");
        verify(campaignDeliveryMapper).applyProviderStatus(WORKSPACE_A, 500, "delivered", List.of("dispatched", "failed"));
    }

    @Test
    void ingestDoesNotMatchAnotherWorkspacesDigestOrAlterUnrelatedHexIds() {
        String foreignBearer = "ab09".repeat(16);
        String unrelated = "CD".repeat(32);
        resolveTo(WORKSPACE_B, List.of(new DeliveryEvent("foreign:" + foreignBearer,
                unrelated, DeliveryEventType.DELIVERED, null, null)));
        when(campaignDeliveryMapper.getUnsubscribeTokenDeliveryId(eq(WORKSPACE_B), any())).thenReturn(null);
        when(campaignDeliveryMapper.findByProviderMessage(WORKSPACE_B, PROVIDER, "foreign:" + foreignBearer))
                .thenReturn(delivery(WORKSPACE_B));
        scopeInline();
        assertEquals(1, service().ingest(PROVIDER, "tok", new byte[0], Map.of()));
        verify(campaignDeliveryMapper).getUnsubscribeTokenDeliveryId(
                WORKSPACE_B, OneTimeTokenDigest.sha256(foreignBearer));
        verify(campaignDeliveryMapper, never()).getUnsubscribeTokenDeliveryId(eq(WORKSPACE_A), any());
        org.mockito.ArgumentCaptor<CampaignDeliveryEvent> row = org.mockito.ArgumentCaptor.forClass(CampaignDeliveryEvent.class);
        verify(campaignDeliveryMapper).insertEvent(row.capture());
        assertEquals(unrelated, row.getValue().getProviderEventId());
    }

    @Test
    void ingestShortIdentifiersDoNotProbeDigestHistory() {
        resolveTo(WORKSPACE_A, List.of(new DeliveryEvent("m1", "e1", DeliveryEventType.DELIVERED, null, null)));
        when(campaignDeliveryMapper.findByProviderMessage(WORKSPACE_A, PROVIDER, "m1"))
                .thenReturn(delivery(WORKSPACE_A));
        scopeInline();
        assertEquals(1, service().ingest(PROVIDER, "tok", new byte[0], Map.of()));
        verify(campaignDeliveryMapper, never()).getUnsubscribeTokenDeliveryId(anyInt(), any());
    }

    @Test
    void ingestIgnoresOversizedMessageIdsBeforeProbesOrWrites() {
        resolveTo(WORKSPACE_A, List.of(new DeliveryEvent("a".repeat(300), "event", DeliveryEventType.BOUNCED, null, null)));
        scopeInline();
        byte[] body = "signed webhook".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Map<String, String> headers = Map.of("signature", "verified-signature");
        assertEquals(0, service().ingest(PROVIDER, "tok", body, headers));
        verify(eventSource).verifySignature(target(WORKSPACE_A), body, headers);
        verifyNoInteractions(campaignDeliveryMapper, campaignSendMapper, suppressionService, consentService);
    }

    @Test
    void ingestRejectsOversizedEventIdsBeforeProbesOrWrites() {
        resolveTo(WORKSPACE_A, List.of(new DeliveryEvent("m1", "a".repeat(300), DeliveryEventType.BOUNCED, null, null)));
        when(campaignDeliveryMapper.findByProviderMessage(WORKSPACE_A, PROVIDER, "m1"))
                .thenReturn(delivery(WORKSPACE_A));
        scopeInline();
        assertThrows(IllegalArgumentException.class, () -> service().ingest(PROVIDER, "tok", new byte[0], Map.of()));
        verify(campaignDeliveryMapper, never()).getUnsubscribeTokenDeliveryId(anyInt(), any());
        verify(campaignDeliveryMapper, never()).insertEvent(any());
        verify(campaignDeliveryMapper, never()).applyProviderStatus(anyInt(), anyInt(), any(), any());
        verifyNoInteractions(campaignSendMapper, suppressionService, consentService);
    }

    @Test
    void ingestIsNotTransactional() throws NoSuchMethodException {
        String reason = "TenantWorkScope cannot change the pinned catalog inside an active "
                + "transaction, so the provider's workspace scope has to open first";
        assertNull(DeliveryWebhookService.class.getAnnotation(Transactional.class), reason);
        assertNull(DeliveryWebhookService.class
                .getMethod("ingest", String.class, String.class, byte[].class, Map.class)
                .getAnnotation(Transactional.class), reason);
    }

    @Test
    void ingest_rejectsAProviderThatDoesNotMatchTheToken() {
        resolveTokenTo(WORKSPACE_A);
        User system = new User();
        system.setId(1);
        when(systemActor.user()).thenReturn(system);
        when(automationExecutor.runAs(eq(WORKSPACE_A), eq(system), eq("system"),
                eq(AutomationScope.Origin.task(AutomationScope.Source.DELIVERY_WEBHOOK, "system")), any())).thenAnswer(
                invocation -> ((Supplier<?>) invocation.getArgument(4)).get());

        assertThrows(DeliveryProviderException.class,
                () -> service().ingest("other_esp", "tok", new byte[0], Map.of()));

        verifyNoInteractions(router);
    }
}
