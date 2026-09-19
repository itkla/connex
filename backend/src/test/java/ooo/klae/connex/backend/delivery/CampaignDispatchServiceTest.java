package ooo.klae.connex.backend.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.time.LocalDateTime;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.test.util.ReflectionTestUtils;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import ooo.klae.connex.backend.beans.CampaignDelivery;
import ooo.klae.connex.backend.beans.CampaignDeliveryEvent;
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

    private static final String EXPIRED_AUDIENCE_RESERVATION =
            "AMBIGUOUS: Audience dispatch did not finish before its reservation expired";
    private static final long RESERVATION_GRACE_MICROS = 30_000_000L;

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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void staleClaimOwnerCannotRewriteWorkflowFrequencyCapOutcome(boolean cappedAtAdmission) {
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
                eq(7), eq(17), eq("email"), eq(11), any())).thenReturn(cappedAtAdmission ? 0 : 1);
        CampaignFrequencyAdmissionService admission = mock(CampaignFrequencyAdmissionService.class);
        when(admission.reserve(eq(7), eq(13), eq(17), eq("email"), anyString(), eq(24)))
                .thenReturn(CampaignFrequencyAdmissionService.Admission.CAPPED);
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
                boundary,
                admission);

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
                boundary,
                admittedFrequency());

        assertTrue(service.processSend(7, 11));

        verify(deliveryMapper, times(2)).markTriggeredAmbiguous(
                eq(7), eq(13), anyString(), anyString(), anyString());
        verify(deliveryMapper, never()).markTriggeredFailed(
                anyInt(), anyInt(), anyString(), anyString(), anyString());
        verify(deliveryMapper, never()).markFailed(
                anyInt(), anyInt(), anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"TLS certificate identity mismatch", "Connection refused"})
    void preSubmissionRejectionsAreFailedWithoutAReconciliationMarker(String rejectionDetail) {
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
                DispatchReceipt.rejected(rejectionDetail));
        when(deliveryMapper.markTriggeredFailed(
                eq(7), eq(13), anyString(), anyString(), anyString())).thenReturn(1);
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
                boundary,
                admittedFrequency());

        assertTrue(service.processSend(7, 11));

        verify(deliveryMapper).markTriggeredFailed(
                eq(7), eq(13), anyString(), eq(rejectionDetail), anyString());
        verify(deliveryMapper, never()).markTriggeredAmbiguous(
                anyInt(), anyInt(), anyString(), anyString(), anyString());
        verify(deliveryMapper, never()).markAmbiguous(
                anyInt(), anyInt(), anyString(), anyString());
    }

    @Test
    void deadlineExpiringDuringAdmissionReleasesTheReservationBeforeEgress() {
        CampaignSendMapper sendMapper = mock(CampaignSendMapper.class);
        CampaignDeliveryMapper deliveryMapper = mock(CampaignDeliveryMapper.class);
        CampaignMessageMapper messageMapper = mock(CampaignMessageMapper.class);
        AudienceEligibilityService eligibilityService = mock(AudienceEligibilityService.class);
        DeliveryProviderConfigService providerConfigService = mock(DeliveryProviderConfigService.class);
        DeliveryProviderRouter providerRouter = mock(DeliveryProviderRouter.class);
        CapabilityRegistry capabilityRegistry = mock(CapabilityRegistry.class);
        WorkflowTriggeredSendGate gate = mock(WorkflowTriggeredSendGate.class);
        MessageDispatcher dispatcher = mock(MessageDispatcher.class);
        CampaignFrequencyAdmissionService admission = mock(CampaignFrequencyAdmissionService.class);
        AtomicLong now = new AtomicLong();
        ResolvedDeliveryProvider target = ResolvedDeliveryProvider.of(
                "smtp", DeliveryChannel.EMAIL, 7, DeliveryCredentials.none());
        when(capabilityRegistry.isAvailable(Capability.CAMPAIGN_DELIVERY)).thenReturn(true);
        when(gate.enabled()).thenReturn(true);
        when(gate.dispatchPageSize()).thenReturn(200);
        when(sendMapper.getSend(7, 11)).thenReturn(triggeredSend());
        when(messageMapper.getRevision(7, 12, 3)).thenReturn(revision());
        when(providerConfigService.resolveForWorkspace(7, DeliveryChannel.EMAIL)).thenReturn(target);
        when(providerRouter.dispatcherFor("smtp")).thenReturn(dispatcher);
        when(deliveryMapper.pendingDeliveryIdsPage(7, 11, 200)).thenReturn(List.of(13));
        when(deliveryMapper.claimTriggered(
                eq(7), eq(13), anyString(), anyLong(), eq("smtp"), anyString())).thenReturn(1);
        when(deliveryMapper.renewTriggeredClaim(eq(7), eq(13), anyString(), anyLong())).thenReturn(1);
        when(deliveryMapper.getDeliveryIdentity(7, 13)).thenReturn(delivery());
        when(deliveryMapper.getDelivery(7, 13)).thenReturn(delivery());
        when(eligibilityService.restrictedIds(7, List.of(17))).thenReturn(Set.of());
        when(eligibilityService.suppressedAddresses(eq(7), eq("email"), any())).thenReturn(Set.of());
        when(eligibilityService.suppressedPersonRefIds(7, List.of(17), "email")).thenReturn(Set.of());
        when(admission.reserve(eq(7), eq(13), eq(17), eq("email"), anyString(), eq(24)))
                .thenAnswer(invocation -> {
                    now.set(Long.MAX_VALUE / 2);
                    return CampaignFrequencyAdmissionService.Admission.RESERVED;
                });
        CampaignDispatchService service = new CampaignDispatchService(
                sendMapper, deliveryMapper, messageMapper, eligibilityService, providerConfigService,
                providerRouter, new DeliveryProperties(), capabilityRegistry, gate,
                mock(WorkflowRunMapper.class), mock(CampaignDispatchClaimBoundary.class), admission);
        ReflectionTestUtils.setField(service, "nanoTimeSource", (java.util.function.LongSupplier) now::get);

        assertTrue(service.processSend(7, 11));

        verify(admission).reserve(eq(7), eq(13), eq(17), eq("email"), anyString(), eq(24));
        verify(deliveryMapper).releaseFrequencyWindowBeforeEgress(eq(7), eq(13), anyString());
        verify(deliveryMapper).markTriggeredFailed(eq(7), eq(13), anyString(),
                eq("Provider deadline expired before egress"), eq("provider_timeout"));
        verifyNoInteractions(dispatcher);
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
                boundary,
                admittedFrequency());

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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void onlyAProvenPreEgressRejectionReleasesTheFrequencyReservation(boolean provenBeforeEgress) {
        CampaignDeliveryMapper deliveryMapper = mock(CampaignDeliveryMapper.class);
        MessageDispatcher dispatcher = mock(MessageDispatcher.class);
        CampaignDispatchService service =
                triggeredDispatch(deliveryMapper, dispatcher, admittedFrequency());
        when(dispatcher.dispatch(any(), any())).thenReturn(provenBeforeEgress
                ? DispatchReceipt.rejectedBeforeEgress("No usable ESP credential is configured")
                : DispatchReceipt.rejected("esp rejected with status 503"));
        when(deliveryMapper.markTriggeredFailed(
                eq(7), eq(13), anyString(), anyString(), anyString())).thenReturn(1);

        assertTrue(service.processSend(7, 11));

        verify(deliveryMapper, times(provenBeforeEgress ? 1 : 0))
                .releaseFrequencyWindowBeforeEgress(eq(7), eq(13), anyString());
        verify(deliveryMapper).markTriggeredFailed(
                eq(7), eq(13), anyString(), anyString(), anyString());
    }

    @Test
    void anAmbiguousOutcomeNeverReleasesTheFrequencyReservation() {
        CampaignDeliveryMapper deliveryMapper = mock(CampaignDeliveryMapper.class);
        MessageDispatcher dispatcher = mock(MessageDispatcher.class);
        CampaignDispatchService service =
                triggeredDispatch(deliveryMapper, dispatcher, admittedFrequency());
        when(dispatcher.dispatch(any(), any())).thenReturn(
                DispatchReceipt.ambiguous("Provider request failed after egress began"));
        when(deliveryMapper.markTriggeredAmbiguous(
                eq(7), eq(13), anyString(), anyString(), anyString())).thenReturn(1);

        assertTrue(service.processSend(7, 11));

        verify(deliveryMapper, never()).releaseFrequencyWindowBeforeEgress(
                anyInt(), anyInt(), anyString());
        verify(deliveryMapper).markTriggeredAmbiguous(
                eq(7), eq(13), anyString(), anyString(), anyString());
    }

    @Test
    void aRefusedReservationTerminatesTheStillOwnedClaimInsteadOfStrandingIt() throws Exception {
        CampaignDeliveryMapper deliveryMapper = mock(CampaignDeliveryMapper.class);
        MessageDispatcher dispatcher = mock(MessageDispatcher.class);
        CampaignFrequencyAdmissionService admission = mock(CampaignFrequencyAdmissionService.class);
        when(admission.reserve(eq(7), eq(13), eq(17), eq("email"), anyString(), eq(24)))
                .thenReturn(CampaignFrequencyAdmissionService.Admission.REFUSED);
        CampaignDispatchService service = triggeredDispatch(deliveryMapper, dispatcher, admission);

        assertTrue(service.processSend(7, 11));

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(deliveryMapper).markTriggeredSkipped(eq(7), eq(13), anyString(), reason.capture());
        assertEquals("not_dispatchable", reason.getValue());
        try (InputStream migration = Objects.requireNonNull(getClass().getResourceAsStream(
                "/db/migration/tenant/V210__allow_not_dispatchable_delivery_skip_reason.sql"))) {
            String sql = new String(migration.readAllBytes(), StandardCharsets.UTF_8);
            Matcher constraint = Pattern.compile("skip_reason IN \\(([^)]+)\\)").matcher(sql);
            assertTrue(constraint.find(), "The migration must declare the allowed skip reasons");
            List<String> allowed = Pattern.compile("'([^']+)'").matcher(constraint.group(1))
                    .results().map(match -> match.group(1)).toList();
            assertTrue(allowed.contains(reason.getValue()), "The database must accept the emitted reason");
        }
        verify(deliveryMapper, never()).markTriggeredFailed(
                anyInt(), anyInt(), anyString(), anyString(), anyString());
        verifyNoInteractions(dispatcher);
    }

    @Test
    void aLostClaimIsLeftUntouchedForItsCurrentOwner() {
        CampaignDeliveryMapper deliveryMapper = mock(CampaignDeliveryMapper.class);
        MessageDispatcher dispatcher = mock(MessageDispatcher.class);
        CampaignFrequencyAdmissionService admission = mock(CampaignFrequencyAdmissionService.class);
        when(admission.reserve(eq(7), eq(13), eq(17), eq("email"), anyString(), eq(24)))
                .thenReturn(CampaignFrequencyAdmissionService.Admission.CLAIM_LOST);
        CampaignDispatchService service = triggeredDispatch(deliveryMapper, dispatcher, admission);

        assertTrue(service.processSend(7, 11));

        verify(deliveryMapper, never()).markTriggeredSkipped(
                anyInt(), anyInt(), anyString(), anyString());
        verify(deliveryMapper, never()).markTriggeredFailed(
                anyInt(), anyInt(), anyString(), anyString(), anyString());
        verifyNoInteractions(dispatcher);
    }

    @ParameterizedTest
    @CsvSource({"recovered, false", "recovered, true", "legacy, false", "legacy, true",
            "detail, false", "detail, true", "marker, false", "marker, true"})
    void refusedClaimsPreserveEarlierSubmissionUncertainty(String historyKind, boolean refusedAtAdmission) {
        CampaignDeliveryMapper deliveryMapper = mock(CampaignDeliveryMapper.class);
        MessageDispatcher dispatcher = mock(MessageDispatcher.class);
        CampaignFrequencyAdmissionService admission = admittedFrequency();
        CampaignDispatchService service = triggeredDispatch(deliveryMapper, dispatcher, admission);
        CampaignDelivery history = delivery();
        history.setAttemptCount(2);
        String expectedReason = "deadline_ambiguous";
        switch (historyKind) {
            case "recovered" -> history.setLastErrorCode(expectedReason);
            case "legacy" -> history.setLastErrorCode(null);
            case "detail" -> {
                history.setAttemptCount(1);
                history.setLastError("AMBIGUOUS: Earlier provider outcome is unknown");
                history.setLastErrorCode("relay_error");
                expectedReason = "relay_error";
            }
            case "marker" -> {
                history.setAttemptCount(1);
                history.setReconciliationRequiredAt(LocalDateTime.of(2026, 1, 1, 0, 0));
                history.setLastErrorCode("provider_timeout");
                expectedReason = "provider_timeout";
            }
            default -> throw new IllegalArgumentException("Unknown attempt history");
        }
        when(deliveryMapper.getDeliveryIdentity(7, 13)).thenReturn(history);
        when(deliveryMapper.markTriggeredAmbiguous(
                eq(7), eq(13), anyString(), anyString(), anyString())).thenReturn(1);
        if (refusedAtAdmission) {
            when(admission.reserve(eq(7), eq(13), eq(17), eq("email"), anyString(), eq(24)))
                    .thenReturn(CampaignFrequencyAdmissionService.Admission.REFUSED);
        } else {
            when(dispatcher.dispatch(any(), any())).thenReturn(
                    DispatchReceipt.rejectedBeforeEgress("No usable provider credential is configured"));
        }

        assertTrue(service.processSend(7, 11));

        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(deliveryMapper).markTriggeredAmbiguous(
                eq(7), eq(13), anyString(), error.capture(), eq(expectedReason));
        assertTrue(error.getValue().startsWith("AMBIGUOUS:"));
        if ("detail".equals(historyKind)) {
            assertEquals(history.getLastError(), error.getValue());
        }
        verify(deliveryMapper, never()).markTriggeredFailed(
                anyInt(), anyInt(), anyString(), anyString(), anyString());
        verify(deliveryMapper, never()).markTriggeredSkipped(
                anyInt(), anyInt(), anyString(), anyString());
        verify(deliveryMapper, never()).releaseFrequencyWindowBeforeEgress(
                anyInt(), anyInt(), anyString());
        if (refusedAtAdmission) {
            verifyNoInteractions(dispatcher);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void theAudienceReservationSweepRunsFromBothDispatchEntryPoints(boolean workspacePass) {
        CampaignSendMapper sendMapper = mock(CampaignSendMapper.class);
        CampaignDeliveryMapper deliveryMapper = mock(CampaignDeliveryMapper.class);
        WorkflowTriggeredSendGate gate = mock(WorkflowTriggeredSendGate.class);
        when(gate.dispatchPageSize()).thenReturn(200);
        CampaignDispatchService service = service(
                sendMapper, deliveryMapper, mock(DeliveryProviderConfigService.class), gate);

        if (workspacePass) {
            assertEquals(0, service.processWorkspace(7));
        } else {
            assertTrue(service.processSend(7, 11));
        }

        verify(deliveryMapper).expiredAudienceReservationsPage(7, RESERVATION_GRACE_MICROS, 200);
    }

    @Test
    void anAbandonedAudienceAttemptBecomesAmbiguousWithOneEventAndRefreshedCounters() {
        CampaignSendMapper sendMapper = mock(CampaignSendMapper.class);
        CampaignDeliveryMapper deliveryMapper = mock(CampaignDeliveryMapper.class);
        WorkflowTriggeredSendGate gate = mock(WorkflowTriggeredSendGate.class);
        when(gate.dispatchPageSize()).thenReturn(200);
        when(deliveryMapper.expiredAudienceReservationsPage(7, RESERVATION_GRACE_MICROS, 200))
                .thenReturn(List.of(abandonedAudienceAttempt(13, 11), abandonedAudienceAttempt(14, 11)));
        when(deliveryMapper.markExpiredAudienceReservationAmbiguous(
                eq(7), anyInt(), eq(RESERVATION_GRACE_MICROS), anyString(), anyString())).thenReturn(1);
        when(sendMapper.audienceSendsAwaitingRecoverySettlement(7, 200)).thenReturn(List.of(11));
        CampaignDispatchService service = service(
                sendMapper, deliveryMapper, mock(DeliveryProviderConfigService.class), gate);

        assertEquals(0, service.processWorkspace(7));

        InOrder recovery = inOrder(deliveryMapper, sendMapper);
        for (int deliveryId : List.of(13, 14)) {
            recovery.verify(deliveryMapper).markExpiredAudienceReservationAmbiguous(
                    7, deliveryId, RESERVATION_GRACE_MICROS, EXPIRED_AUDIENCE_RESERVATION,
                    CampaignDeliveryFailureReason.DEADLINE_AMBIGUOUS.token());
        }
        recovery.verify(sendMapper).audienceSendsAwaitingRecoverySettlement(7, 200);
        recovery.verify(sendMapper).refreshCounters(7, 11);
        ArgumentCaptor<CampaignDeliveryEvent> events = ArgumentCaptor.forClass(CampaignDeliveryEvent.class);
        verify(deliveryMapper, times(2)).insertEvent(events.capture());
        assertEquals(List.of(13, 14), events.getAllValues().stream()
                .map(CampaignDeliveryEvent::getDeliveryId).toList());
        for (CampaignDeliveryEvent event : events.getAllValues()) {
            assertEquals("failed", event.getEventType());
            assertEquals(EXPIRED_AUDIENCE_RESERVATION, event.getDetail());
        }
        verify(sendMapper, times(1)).refreshCounters(7, 11);
        verify(deliveryMapper, never()).markAmbiguous(anyInt(), anyInt(), anyString(), anyString());
        verify(deliveryMapper, never()).claim(anyInt(), anyInt());
    }

    @Test
    void aLostAudienceReservationCompareAndSetAppendsNothing() {
        CampaignSendMapper sendMapper = mock(CampaignSendMapper.class);
        CampaignDeliveryMapper deliveryMapper = mock(CampaignDeliveryMapper.class);
        WorkflowTriggeredSendGate gate = mock(WorkflowTriggeredSendGate.class);
        when(gate.dispatchPageSize()).thenReturn(200);
        when(deliveryMapper.expiredAudienceReservationsPage(7, RESERVATION_GRACE_MICROS, 200))
                .thenReturn(List.of(abandonedAudienceAttempt(13, 11)));
        when(deliveryMapper.markExpiredAudienceReservationAmbiguous(
                eq(7), eq(13), eq(RESERVATION_GRACE_MICROS), anyString(), anyString())).thenReturn(0);
        CampaignDispatchService service = service(
                sendMapper, deliveryMapper, mock(DeliveryProviderConfigService.class), gate);

        assertEquals(0, service.processWorkspace(7));

        verify(deliveryMapper).markExpiredAudienceReservationAmbiguous(
                7, 13, RESERVATION_GRACE_MICROS, EXPIRED_AUDIENCE_RESERVATION,
                CampaignDeliveryFailureReason.DEADLINE_AMBIGUOUS.token());
        verify(deliveryMapper, never()).insertEvent(any());
        verify(sendMapper, never()).refreshCounters(anyInt(), anyInt());
    }

    @Test
    void anAudienceReservationEventFailureIsSwallowedAndTheSweepContinues() {
        CampaignSendMapper sendMapper = mock(CampaignSendMapper.class);
        CampaignDeliveryMapper deliveryMapper = mock(CampaignDeliveryMapper.class);
        WorkflowTriggeredSendGate gate = mock(WorkflowTriggeredSendGate.class);
        when(gate.dispatchPageSize()).thenReturn(200);
        when(deliveryMapper.expiredAudienceReservationsPage(7, RESERVATION_GRACE_MICROS, 200))
                .thenReturn(List.of(abandonedAudienceAttempt(13, 11), abandonedAudienceAttempt(14, 12)));
        when(deliveryMapper.markExpiredAudienceReservationAmbiguous(
                eq(7), anyInt(), eq(RESERVATION_GRACE_MICROS), anyString(), anyString())).thenReturn(1);
        doThrow(new IllegalStateException("event store unavailable"))
                .when(deliveryMapper).insertEvent(any());
        when(sendMapper.audienceSendsAwaitingRecoverySettlement(7, 200)).thenReturn(List.of(11, 12));
        CampaignDispatchService service = service(
                sendMapper, deliveryMapper, mock(DeliveryProviderConfigService.class), gate);

        assertEquals(0, service.processWorkspace(7));

        verify(deliveryMapper).markExpiredAudienceReservationAmbiguous(
                eq(7), eq(14), eq(RESERVATION_GRACE_MICROS), anyString(), anyString());
        verify(deliveryMapper, times(2)).insertEvent(any());
        verify(sendMapper).refreshCounters(7, 11);
        verify(sendMapper).refreshCounters(7, 12);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aFailedRecoverySweepIsCountedButNeverStarvesTheOtherSweepOrQueuedDispatch(boolean audienceFails) {
        CampaignSendMapper sendMapper = mock(CampaignSendMapper.class);
        CampaignDeliveryMapper deliveryMapper = mock(CampaignDeliveryMapper.class);
        WorkflowTriggeredSendGate gate = mock(WorkflowTriggeredSendGate.class);
        when(gate.dispatchPageSize()).thenReturn(200);
        when(sendMapper.queuedSendIds(7, false)).thenReturn(List.of(11));
        if (audienceFails) {
            when(deliveryMapper.expiredAudienceReservationsPage(7, RESERVATION_GRACE_MICROS, 200))
                    .thenThrow(new IllegalStateException("Deadlock found when trying to get lock"));
        } else {
            when(deliveryMapper.expiredTriggeredClaimsPage(7, 200))
                    .thenThrow(new IllegalStateException("Deadlock found when trying to get lock"));
        }
        CampaignDispatchService service = service(
                sendMapper, deliveryMapper, mock(DeliveryProviderConfigService.class), gate);

        assertEquals(1, service.processWorkspace(7));
        assertTrue(service.processSend(7, 11));

        verify(deliveryMapper, times(2)).expiredTriggeredClaimsPage(7, 200);
        verify(deliveryMapper, times(2)).expiredAudienceReservationsPage(7, RESERVATION_GRACE_MICROS, 200);
        verify(sendMapper, times(2)).getSend(7, 11);
    }

    @Test
    void aFailedAudienceCompareAndSetStillSettlesTheSendsAwaitingSettlement() {
        CampaignSendMapper sendMapper = mock(CampaignSendMapper.class);
        CampaignDeliveryMapper deliveryMapper = mock(CampaignDeliveryMapper.class);
        WorkflowTriggeredSendGate gate = mock(WorkflowTriggeredSendGate.class);
        when(gate.dispatchPageSize()).thenReturn(200);
        when(sendMapper.queuedSendIds(7, false)).thenReturn(List.of(11));
        when(deliveryMapper.expiredAudienceReservationsPage(7, RESERVATION_GRACE_MICROS, 200))
                .thenReturn(List.of(abandonedAudienceAttempt(13, 21), abandonedAudienceAttempt(14, 22)));
        when(deliveryMapper.markExpiredAudienceReservationAmbiguous(
                eq(7), eq(13), eq(RESERVATION_GRACE_MICROS), anyString(), anyString())).thenReturn(1);
        when(deliveryMapper.markExpiredAudienceReservationAmbiguous(
                eq(7), eq(14), eq(RESERVATION_GRACE_MICROS), anyString(), anyString()))
                .thenThrow(new IllegalStateException("Deadlock found when trying to get lock"));
        when(sendMapper.audienceSendsAwaitingRecoverySettlement(7, 200)).thenReturn(List.of(21));
        CampaignDispatchService service = service(
                sendMapper, deliveryMapper, mock(DeliveryProviderConfigService.class), gate);

        assertEquals(1, service.processWorkspace(7));

        verify(deliveryMapper, times(1)).insertEvent(any());
        verify(sendMapper).audienceSendsAwaitingRecoverySettlement(7, 200);
        verify(sendMapper).refreshCounters(7, 21);
        verify(sendMapper).getSend(7, 11);
    }

    @ParameterizedTest
    @CsvSource({"running, 0, true", "running, 2, false", "completed, 0, false"})
    void aSendAwaitingRecoverySettlementSettlesWithoutResolvingItsProvider(
            String status, int pending, boolean completes) {
        CampaignSendMapper sendMapper = mock(CampaignSendMapper.class);
        CampaignDeliveryMapper deliveryMapper = mock(CampaignDeliveryMapper.class);
        DeliveryProviderConfigService providerConfigService = mock(DeliveryProviderConfigService.class);
        WorkflowTriggeredSendGate gate = mock(WorkflowTriggeredSendGate.class);
        when(gate.dispatchPageSize()).thenReturn(200);
        when(sendMapper.audienceSendsAwaitingRecoverySettlement(7, 200)).thenReturn(List.of(11));
        when(sendMapper.getSend(7, 11)).thenReturn(audienceSend(status));
        when(deliveryMapper.countPending(7, 11)).thenReturn(pending);
        CampaignDispatchService service = service(sendMapper, deliveryMapper, providerConfigService, gate);

        assertEquals(0, service.processWorkspace(7));

        InOrder settlement = inOrder(sendMapper);
        settlement.verify(sendMapper).refreshCounters(7, 11);
        if (completes) {
            settlement.verify(sendMapper).markCompleted(7, 11);
        } else {
            verify(sendMapper, never()).markCompleted(anyInt(), anyInt());
        }
        verifyNoInteractions(providerConfigService);
    }

    private static CampaignSend audienceSend(String status) {
        CampaignSend send = new CampaignSend();
        send.setId(11);
        send.setWorkspaceId(7);
        send.setOrigin("audience");
        send.setStatus(status);
        send.setChannel("email");
        return send;
    }

    private static CampaignDelivery abandonedAudienceAttempt(int deliveryId, int sendId) {
        CampaignDelivery abandoned = new CampaignDelivery();
        abandoned.setId(deliveryId);
        abandoned.setSendId(sendId);
        return abandoned;
    }

    private static CampaignDispatchService triggeredDispatch(
            CampaignDeliveryMapper deliveryMapper,
            MessageDispatcher dispatcher,
            CampaignFrequencyAdmissionService admission) {
        CampaignSendMapper sendMapper = mock(CampaignSendMapper.class);
        CampaignMessageMapper messageMapper = mock(CampaignMessageMapper.class);
        AudienceEligibilityService eligibilityService = mock(AudienceEligibilityService.class);
        DeliveryProviderConfigService providerConfigService = mock(DeliveryProviderConfigService.class);
        DeliveryProviderRouter providerRouter = mock(DeliveryProviderRouter.class);
        CapabilityRegistry capabilityRegistry = mock(CapabilityRegistry.class);
        WorkflowTriggeredSendGate gate = mock(WorkflowTriggeredSendGate.class);
        ResolvedDeliveryProvider target = ResolvedDeliveryProvider.of(
                "smtp", DeliveryChannel.EMAIL, 7, DeliveryCredentials.none());
        when(capabilityRegistry.isAvailable(Capability.CAMPAIGN_DELIVERY)).thenReturn(true);
        when(gate.enabled()).thenReturn(true);
        when(gate.dispatchPageSize()).thenReturn(200);
        when(sendMapper.getSend(7, 11)).thenReturn(triggeredSend());
        when(messageMapper.getRevision(7, 12, 3)).thenReturn(revision());
        when(providerConfigService.resolveForWorkspace(7, DeliveryChannel.EMAIL)).thenReturn(target);
        when(providerRouter.dispatcherFor("smtp")).thenReturn(dispatcher);
        when(deliveryMapper.pendingDeliveryIdsPage(7, 11, 200)).thenReturn(List.of(13));
        when(deliveryMapper.claimTriggered(
                eq(7), eq(13), anyString(), anyLong(), eq("smtp"), anyString())).thenReturn(1);
        when(deliveryMapper.renewTriggeredClaim(eq(7), eq(13), anyString(), anyLong())).thenReturn(1);
        when(deliveryMapper.getDeliveryIdentity(7, 13)).thenReturn(delivery());
        when(deliveryMapper.getDelivery(7, 13)).thenReturn(delivery());
        when(eligibilityService.restrictedIds(7, List.of(17))).thenReturn(Set.of());
        when(eligibilityService.suppressedAddresses(eq(7), eq("email"), any())).thenReturn(Set.of());
        when(eligibilityService.suppressedPersonRefIds(7, List.of(17), "email")).thenReturn(Set.of());
        when(eligibilityService.consentBlocks(7, 17, "email", "marketing")).thenReturn(false);
        when(deliveryMapper.recentDispatchCount(
                eq(7), eq(17), eq("email"), eq(11), any())).thenReturn(0);
        return new CampaignDispatchService(
                sendMapper, deliveryMapper, messageMapper, eligibilityService, providerConfigService,
                providerRouter, new DeliveryProperties(), capabilityRegistry, gate,
                mock(WorkflowRunMapper.class), mock(CampaignDispatchClaimBoundary.class), admission);
    }

    private static CampaignFrequencyAdmissionService admittedFrequency() {
        CampaignFrequencyAdmissionService admission = mock(CampaignFrequencyAdmissionService.class);
        when(admission.reserve(anyInt(), anyInt(), anyInt(), anyString(), any(), anyInt()))
                .thenReturn(CampaignFrequencyAdmissionService.Admission.RESERVED);
        return admission;
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
                mock(CampaignDispatchClaimBoundary.class),
                admittedFrequency());
    }
}
