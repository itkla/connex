package ooo.klae.connex.backend.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.CampaignDelivery;
import ooo.klae.connex.backend.beans.CampaignMessageRevision;
import ooo.klae.connex.backend.beans.CampaignSend;
import ooo.klae.connex.backend.capability.Capability;
import ooo.klae.connex.backend.capability.CapabilityRegistry;
import ooo.klae.connex.backend.mappers.CampaignDeliveryMapper;
import ooo.klae.connex.backend.mappers.CampaignMessageMapper;
import ooo.klae.connex.backend.mappers.CampaignSendMapper;
import ooo.klae.connex.backend.mappers.WorkflowRunMapper;
import ooo.klae.connex.backend.services.AudienceEligibilityService;
import ooo.klae.connex.backend.services.WorkflowTriggeredSendGate;

class CampaignDispatchServiceTest {

    @Test
    void providerDetailsMapToTheBoundedRecipientReasonVocabulary() {
        assertEquals("provider_timeout",
                CampaignDeliveryFailureReason.classify("read timed out", false).token());
        assertEquals("provider_rejected",
                CampaignDeliveryFailureReason.classify("provider returned status 429", false).token());
        assertEquals("deadline_ambiguous",
                CampaignDeliveryFailureReason.classify("deadline after DATA", true).token());
        assertEquals("relay_error",
                CampaignDeliveryFailureReason.classify("private relay diagnostic", true).token());
    }

    @Test
    void staleClaimOwnerCannotRewriteWorkflowFrequencyCapOutcome() {
        CampaignSendMapper sendMapper = mock(CampaignSendMapper.class);
        CampaignDeliveryMapper deliveryMapper = mock(CampaignDeliveryMapper.class);
        CampaignMessageMapper messageMapper = mock(CampaignMessageMapper.class);
        AudienceEligibilityService eligibilityService = mock(AudienceEligibilityService.class);
        DeliveryProviderConfigService providerConfigService = mock(DeliveryProviderConfigService.class);
        DeliveryProviderRouter providerRouter = mock(DeliveryProviderRouter.class);
        CapabilityRegistry capabilityRegistry = mock(CapabilityRegistry.class);
        WorkflowRunMapper workflowRunMapper = mock(WorkflowRunMapper.class);
        WorkflowTriggeredSendGate gate = mock(WorkflowTriggeredSendGate.class);
        CampaignDispatchClaimBoundary boundary = mock(CampaignDispatchClaimBoundary.class);
        CampaignSend send = triggeredSend();
        CampaignMessageRevision revision = revision();
        CampaignDelivery delivery = delivery();
        ResolvedDeliveryProvider target = ResolvedDeliveryProvider.of(
                "smtp", DeliveryChannel.EMAIL, 7, DeliveryCredentials.of(java.util.Map.of()));
        when(capabilityRegistry.isAvailable(Capability.CAMPAIGN_DELIVERY)).thenReturn(true);
        when(gate.enabled()).thenReturn(true);
        when(gate.dispatchPageSize()).thenReturn(200);
        when(sendMapper.getSend(7, 11)).thenReturn(send);
        when(messageMapper.getRevision(7, 12, 3)).thenReturn(revision);
        when(providerConfigService.resolveForWorkspace(7, DeliveryChannel.EMAIL)).thenReturn(target);
        when(providerRouter.dispatcherFor("smtp")).thenReturn(mock(MessageDispatcher.class));
        when(deliveryMapper.pendingDeliveryIdsPage(7, 11, 200)).thenReturn(List.of(13));
        when(deliveryMapper.claimTriggered(
                eq(7), eq(13), anyString(), anyLong(), eq("smtp"), anyString()))
                .thenReturn(1);
        when(deliveryMapper.renewTriggeredClaim(
                eq(7), eq(13), anyString(), anyLong())).thenReturn(1);
        when(deliveryMapper.getDeliveryIdentity(7, 13)).thenReturn(delivery);
        when(deliveryMapper.getDelivery(7, 13)).thenReturn(delivery);
        when(eligibilityService.restrictedIds(7, List.of(17))).thenReturn(Set.of());
        when(eligibilityService.suppressedAddresses(eq(7), eq("email"), any())).thenReturn(Set.of());
        when(eligibilityService.suppressedPersonRefIds(7, List.of(17), "email"))
                .thenReturn(Set.of());
        when(eligibilityService.consentBlocks(7, 17, "email", "marketing"))
                .thenReturn(false);
        when(deliveryMapper.recentDispatchCount(
                eq(7), eq(17), eq("email"), eq(11), any())).thenReturn(1);
        when(deliveryMapper.markTriggeredSkipped(
                eq(7), eq(13), anyString(), eq("frequency_capped"))).thenReturn(0);
        CampaignDispatchService service = new CampaignDispatchService(
                sendMapper,
                deliveryMapper,
                messageMapper,
                eligibilityService,
                providerConfigService,
                providerRouter,
                new DeliveryProperties(),
                capabilityRegistry,
                gate,
                workflowRunMapper,
                boundary);

        assertTrue(service.processSend(7, 11));

        verify(deliveryMapper).markTriggeredSkipped(
                eq(7), eq(13), anyString(), eq("frequency_capped"));
        verify(workflowRunMapper, never()).markActionDeliveryCapped(anyInt(), anyInt());
    }

    @Test
    void transientAmbiguousPersistenceFailureRetriesWithoutDowngradingToDefinitiveFailure() {
        CampaignSendMapper sendMapper = mock(CampaignSendMapper.class);
        CampaignDeliveryMapper deliveryMapper = mock(CampaignDeliveryMapper.class);
        CampaignMessageMapper messageMapper = mock(CampaignMessageMapper.class);
        AudienceEligibilityService eligibilityService = mock(AudienceEligibilityService.class);
        DeliveryProviderConfigService providerConfigService = mock(DeliveryProviderConfigService.class);
        DeliveryProviderRouter providerRouter = mock(DeliveryProviderRouter.class);
        CapabilityRegistry capabilityRegistry = mock(CapabilityRegistry.class);
        WorkflowRunMapper workflowRunMapper = mock(WorkflowRunMapper.class);
        WorkflowTriggeredSendGate gate = mock(WorkflowTriggeredSendGate.class);
        CampaignDispatchClaimBoundary boundary = mock(CampaignDispatchClaimBoundary.class);
        MessageDispatcher dispatcher = mock(MessageDispatcher.class);
        CampaignSend send = triggeredSend();
        CampaignDelivery delivery = delivery();
        ResolvedDeliveryProvider target = ResolvedDeliveryProvider.of(
                "smtp", DeliveryChannel.EMAIL, 7, DeliveryCredentials.of(java.util.Map.of()));
        when(capabilityRegistry.isAvailable(Capability.CAMPAIGN_DELIVERY)).thenReturn(true);
        when(gate.enabled()).thenReturn(true);
        when(gate.dispatchPageSize()).thenReturn(200);
        when(sendMapper.getSend(7, 11)).thenReturn(send);
        when(messageMapper.getRevision(7, 12, 3)).thenReturn(revision());
        when(providerConfigService.resolveForWorkspace(7, DeliveryChannel.EMAIL)).thenReturn(target);
        when(providerRouter.dispatcherFor("smtp")).thenReturn(dispatcher);
        when(deliveryMapper.pendingDeliveryIdsPage(7, 11, 200)).thenReturn(List.of(13));
        when(deliveryMapper.claimTriggered(
                eq(7), eq(13), anyString(), anyLong(), eq("smtp"), anyString())).thenReturn(1);
        when(deliveryMapper.renewTriggeredClaim(
                eq(7), eq(13), anyString(), anyLong())).thenReturn(1);
        when(deliveryMapper.getDeliveryIdentity(7, 13)).thenReturn(delivery);
        when(deliveryMapper.getDelivery(7, 13)).thenReturn(delivery);
        when(eligibilityService.restrictedIds(7, List.of(17))).thenReturn(Set.of());
        when(eligibilityService.suppressedAddresses(eq(7), eq("email"), any())).thenReturn(Set.of());
        when(eligibilityService.suppressedPersonRefIds(7, List.of(17), "email"))
                .thenReturn(Set.of());
        when(eligibilityService.consentBlocks(7, 17, "email", "marketing"))
                .thenReturn(false);
        when(deliveryMapper.recentDispatchCount(
                eq(7), eq(17), eq("email"), eq(11), any())).thenReturn(0);
        when(dispatcher.dispatch(eq(target), any())).thenReturn(
                DispatchReceipt.ambiguous("provider result is unknown"));
        when(deliveryMapper.markTriggeredAmbiguous(
                eq(7), eq(13), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("transient database failure"))
                .thenReturn(1);
        CampaignDispatchService service = new CampaignDispatchService(
                sendMapper,
                deliveryMapper,
                messageMapper,
                eligibilityService,
                providerConfigService,
                providerRouter,
                new DeliveryProperties(),
                capabilityRegistry,
                gate,
                workflowRunMapper,
                boundary);

        assertTrue(service.processSend(7, 11));

        verify(deliveryMapper, times(2)).markTriggeredAmbiguous(
                eq(7), eq(13), anyString(), anyString(), anyString());
        verify(deliveryMapper, never()).markTriggeredFailed(
                anyInt(), anyInt(), anyString(), anyString(), anyString());
        verify(deliveryMapper, never()).markFailed(
                anyInt(), anyInt(), anyString(), anyString());
    }

    @Test
    void changedConnectorBetweenClaimAndRecoveryBecomesAmbiguousWithoutReplay() {
        CampaignSendMapper sendMapper = mock(CampaignSendMapper.class);
        CampaignDeliveryMapper deliveryMapper = mock(CampaignDeliveryMapper.class);
        CampaignMessageMapper messageMapper = mock(CampaignMessageMapper.class);
        AudienceEligibilityService eligibilityService = mock(AudienceEligibilityService.class);
        DeliveryProviderConfigService providerConfigService = mock(DeliveryProviderConfigService.class);
        DeliveryProviderRouter providerRouter = mock(DeliveryProviderRouter.class);
        CapabilityRegistry capabilityRegistry = mock(CapabilityRegistry.class);
        WorkflowRunMapper workflowRunMapper = mock(WorkflowRunMapper.class);
        WorkflowTriggeredSendGate gate = mock(WorkflowTriggeredSendGate.class);
        CampaignDispatchClaimBoundary boundary = mock(CampaignDispatchClaimBoundary.class);
        MessageDispatcher dispatcher = mock(MessageDispatcher.class);
        ResolvedDeliveryProvider attempted = target(
                "https://account-a.example.test/send", "account-a");
        ResolvedDeliveryProvider current = target(
                "https://account-b.example.test/send", "account-b");
        CampaignDelivery expired = new CampaignDelivery();
        expired.setId(13);
        expired.setProviderId(attempted.providerId());
        expired.setAttemptTargetFingerprint(attempted.attemptTargetFingerprint());
        expired.setChannel("email");
        when(capabilityRegistry.isAvailable(Capability.CAMPAIGN_DELIVERY)).thenReturn(true);
        when(gate.dispatchPageSize()).thenReturn(200);
        when(deliveryMapper.expiredTriggeredClaimsPage(7, 200)).thenReturn(List.of(expired));
        when(providerConfigService.resolveForWorkspace(7, DeliveryChannel.EMAIL))
                .thenReturn(current);
        when(providerRouter.adapterFor(current.providerId())).thenReturn(dispatcher);
        when(deliveryMapper.markExpiredTriggeredClaimAmbiguous(
                eq(7), eq(13), anyString(),
                eq(CampaignDeliveryFailureReason.DELIVERY_TARGET_CHANGED.token())))
                .thenReturn(1);
        CampaignDispatchService service = new CampaignDispatchService(
                sendMapper,
                deliveryMapper,
                messageMapper,
                eligibilityService,
                providerConfigService,
                providerRouter,
                new DeliveryProperties(),
                capabilityRegistry,
                gate,
                workflowRunMapper,
                boundary);

        assertTrue(service.processSend(7, 11));

        verify(deliveryMapper).markExpiredTriggeredClaimAmbiguous(
                eq(7), eq(13), anyString(),
                eq(CampaignDeliveryFailureReason.DELIVERY_TARGET_CHANGED.token()));
        verify(deliveryMapper, never()).recoverExpiredTriggeredClaim(anyInt(), anyInt(), anyString());
        verifyNoInteractions(messageMapper);
        verify(dispatcher, never()).dispatch(any(), any());
    }

    @Test
    void unchangedConnectorRecoversOnlyWhenItsConfigurationExplicitlyPromisesIdempotency() {
        CampaignSendMapper sendMapper = mock(CampaignSendMapper.class);
        CampaignDeliveryMapper deliveryMapper = mock(CampaignDeliveryMapper.class);
        DeliveryProviderConfigService providerConfigService = mock(DeliveryProviderConfigService.class);
        WorkflowTriggeredSendGate gate = mock(WorkflowTriggeredSendGate.class);
        ResolvedDeliveryProvider current = target(
                "https://account-a.example.test/send", "account-a", true);
        CampaignDelivery expired = expiredClaim(current);
        when(gate.dispatchPageSize()).thenReturn(200);
        when(deliveryMapper.expiredTriggeredClaimsPage(7, 200)).thenReturn(List.of(expired));
        when(providerConfigService.resolveForWorkspace(7, DeliveryChannel.EMAIL))
                .thenReturn(current);
        CampaignDispatchService service = service(
                sendMapper, deliveryMapper, providerConfigService, gate);

        service.processSend(7, 11);

        verify(deliveryMapper).recoverExpiredTriggeredClaim(
                7, expired.getId(), current.attemptTargetFingerprint());
        verify(deliveryMapper, never()).markExpiredTriggeredClaimAmbiguous(
                anyInt(), anyInt(), anyString(), anyString());
    }

    @Test
    void unchangedConnectorWithoutAnIdempotencyPromiseBecomesAmbiguous() {
        CampaignSendMapper sendMapper = mock(CampaignSendMapper.class);
        CampaignDeliveryMapper deliveryMapper = mock(CampaignDeliveryMapper.class);
        DeliveryProviderConfigService providerConfigService = mock(DeliveryProviderConfigService.class);
        WorkflowTriggeredSendGate gate = mock(WorkflowTriggeredSendGate.class);
        ResolvedDeliveryProvider current = target(
                "https://account-a.example.test/send", "account-a", false);
        CampaignDelivery expired = expiredClaim(current);
        when(gate.dispatchPageSize()).thenReturn(200);
        when(deliveryMapper.expiredTriggeredClaimsPage(7, 200)).thenReturn(List.of(expired));
        when(providerConfigService.resolveForWorkspace(7, DeliveryChannel.EMAIL))
                .thenReturn(current);
        when(deliveryMapper.markExpiredTriggeredClaimAmbiguous(
                eq(7), eq(expired.getId()), anyString(),
                eq(CampaignDeliveryFailureReason.DEADLINE_AMBIGUOUS.token())))
                .thenReturn(1);
        CampaignDispatchService service = service(
                sendMapper, deliveryMapper, providerConfigService, gate);

        service.processSend(7, 11);

        verify(deliveryMapper).markExpiredTriggeredClaimAmbiguous(
                eq(7), eq(expired.getId()), anyString(),
                eq(CampaignDeliveryFailureReason.DEADLINE_AMBIGUOUS.token()));
        verify(deliveryMapper, never()).recoverExpiredTriggeredClaim(
                anyInt(), anyInt(), anyString());
    }

    @Test
    void disablingTheEspBeforeRecoveryMakesTheClaimAmbiguousInsteadOfFallingBackToSmtp() {
        CampaignSendMapper sendMapper = mock(CampaignSendMapper.class);
        CampaignDeliveryMapper deliveryMapper = mock(CampaignDeliveryMapper.class);
        DeliveryProviderConfigService providerConfigService = mock(DeliveryProviderConfigService.class);
        WorkflowTriggeredSendGate gate = mock(WorkflowTriggeredSendGate.class);
        ResolvedDeliveryProvider attempted = target(
                "https://account-a.example.test/send", "account-a", true);
        ResolvedDeliveryProvider fallback = ResolvedDeliveryProvider.of(
                "smtp", DeliveryChannel.EMAIL, 7, DeliveryCredentials.none());
        CampaignDelivery expired = expiredClaim(attempted);
        when(gate.dispatchPageSize()).thenReturn(200);
        when(deliveryMapper.expiredTriggeredClaimsPage(7, 200)).thenReturn(List.of(expired));
        when(providerConfigService.resolveForWorkspace(7, DeliveryChannel.EMAIL))
                .thenReturn(fallback);
        when(deliveryMapper.markExpiredTriggeredClaimAmbiguous(
                eq(7), eq(expired.getId()), anyString(),
                eq(CampaignDeliveryFailureReason.DELIVERY_TARGET_CHANGED.token())))
                .thenReturn(1);
        CampaignDispatchService service = service(
                sendMapper, deliveryMapper, providerConfigService, gate);

        service.processSend(7, 11);

        verify(deliveryMapper).markExpiredTriggeredClaimAmbiguous(
                eq(7), eq(expired.getId()), anyString(),
                eq(CampaignDeliveryFailureReason.DELIVERY_TARGET_CHANGED.token()));
        verify(deliveryMapper, never()).recoverExpiredTriggeredClaim(
                anyInt(), anyInt(), anyString());
    }

    private static CampaignSend triggeredSend() {
        CampaignSend send = new CampaignSend();
        send.setId(11);
        send.setWorkspaceId(7);
        send.setOrigin("triggered");
        send.setStatus("triggered");
        send.setMessageId(12);
        send.setMessageVersion(3);
        send.setChannel("email");
        send.setPurpose("marketing");
        return send;
    }

    private static CampaignMessageRevision revision() {
        CampaignMessageRevision revision = new CampaignMessageRevision();
        revision.setMessageId(12);
        revision.setVersion(3);
        revision.setSubject("Subject");
        revision.setBodyHtml("<p>Body</p>");
        revision.setBodyText("Body");
        return revision;
    }

    private static CampaignDelivery delivery() {
        CampaignDelivery delivery = new CampaignDelivery();
        delivery.setId(13);
        delivery.setPersonId(17);
        delivery.setAddress("recipient@dest.test");
        delivery.setUnsubscribeToken("token");
        return delivery;
    }

    private static ResolvedDeliveryProvider target(String endpoint, String account) {
        return target(endpoint, account, false);
    }

    private static ResolvedDeliveryProvider target(
            String endpoint, String account, boolean idempotentSubmission) {
        return new ResolvedDeliveryProvider(
                "http_esp",
                DeliveryChannel.EMAIL,
                7,
                endpoint,
                account + "@sender.test",
                account,
                DeliveryCredentials.none(),
                idempotentSubmission,
                DeliveryTargetFingerprint.create(
                        "http_esp", "delivery-provider:55:3",
                        endpoint + "|account=" + account, "secret:v1:55"),
                null);
    }

    private static CampaignDelivery expiredClaim(ResolvedDeliveryProvider attempted) {
        CampaignDelivery expired = new CampaignDelivery();
        expired.setId(13);
        expired.setProviderId(attempted.providerId());
        expired.setAttemptTargetFingerprint(attempted.attemptTargetFingerprint());
        expired.setChannel("email");
        return expired;
    }

    private static CampaignDispatchService service(
            CampaignSendMapper sendMapper,
            CampaignDeliveryMapper deliveryMapper,
            DeliveryProviderConfigService providerConfigService,
            WorkflowTriggeredSendGate gate) {
        CapabilityRegistry capabilityRegistry = mock(CapabilityRegistry.class);
        when(capabilityRegistry.isAvailable(Capability.CAMPAIGN_DELIVERY)).thenReturn(true);
        return new CampaignDispatchService(
                sendMapper,
                deliveryMapper,
                mock(CampaignMessageMapper.class),
                mock(AudienceEligibilityService.class),
                providerConfigService,
                mock(DeliveryProviderRouter.class),
                new DeliveryProperties(),
                capabilityRegistry,
                gate,
                mock(WorkflowRunMapper.class),
                mock(CampaignDispatchClaimBoundary.class));
    }
}
