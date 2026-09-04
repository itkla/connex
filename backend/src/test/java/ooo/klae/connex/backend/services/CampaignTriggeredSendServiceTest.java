package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.CampaignDelivery;
import ooo.klae.connex.backend.beans.CampaignSend;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.capability.Capability;
import ooo.klae.connex.backend.capability.CapabilityRegistry;
import ooo.klae.connex.backend.delivery.CampaignDispatchClaimBoundary;
import ooo.klae.connex.backend.delivery.CampaignDispatchService;
import ooo.klae.connex.backend.delivery.CampaignSendWorker;
import ooo.klae.connex.backend.delivery.DeliveryChannel;
import ooo.klae.connex.backend.delivery.DeliveryCredentials;
import ooo.klae.connex.backend.delivery.DeliveryProperties;
import ooo.klae.connex.backend.delivery.DeliveryProviderConfigService;
import ooo.klae.connex.backend.delivery.DeliveryProviderRouter;
import ooo.klae.connex.backend.delivery.ResolvedDeliveryProvider;
import ooo.klae.connex.backend.dto.CampaignDeliveryReconciliationDto;
import ooo.klae.connex.backend.dto.CampaignDeliveryReconciliationRequest;
import ooo.klae.connex.backend.dto.CampaignDto;
import ooo.klae.connex.backend.dto.CampaignMessageDto;
import ooo.klae.connex.backend.dto.CampaignMessageRequest;
import ooo.klae.connex.backend.dto.CampaignMessageRevisionRequest;
import ooo.klae.connex.backend.dto.CampaignRequest;
import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.CampaignSendDto;
import ooo.klae.connex.backend.dto.SuppressionEntryRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.CampaignDeliveryMapper;
import ooo.klae.connex.backend.mappers.CampaignMessageMapper;
import ooo.klae.connex.backend.mappers.CampaignSendMapper;
import ooo.klae.connex.backend.mappers.WorkflowRunMapper;
import ooo.klae.connex.backend.tenant.Permission;

@TestPropertySource(properties = {
    "connex.delivery.enabled=true",
    "connex.workflows.triggered-send.enabled=true"
})
@Import(CampaignSendServiceTest.FakeDeliveryConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CampaignTriggeredSendServiceTest extends CampaignRealDbTestSupport {

    @Autowired private CampaignService campaignService;
    @Autowired private CampaignSendService campaignSendService;
    @Autowired private RuleActionExecutor ruleActionExecutor;
    @Autowired private CampaignTriggeredSendService triggeredSendService;
    @Autowired private CampaignDispatchService campaignDispatchService;
    @Autowired private CampaignSendWorker campaignSendWorker;
    @Autowired private SuppressionService suppressionService;
    @Autowired private CampaignDeliveryMapper campaignDeliveryMapper;
    @Autowired private CampaignMessageMapper campaignMessageMapper;
    @Autowired private CampaignSendMapper campaignSendMapper;
    @Autowired private AudienceEligibilityService audienceEligibilityService;
    @Autowired private DeliveryProviderRouter deliveryProviderRouter;
    @Autowired private DeliveryProperties deliveryProperties;
    @Autowired private WorkflowRunMapper workflowRunMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private CampaignSendServiceTest.FakeDispatcher fakeDispatcher;
    @MockitoBean private CapabilityRegistry capabilityRegistry;
    @MockitoBean private DeliveryProviderConfigService deliveryProviderConfigService;
    @MockitoBean private WorkflowTriggeredSendGate triggeredSendGate;
    @MockitoBean private CampaignDispatchClaimBoundary claimBoundary;

    @BeforeEach
    void resetDelivery() {
        fakeDispatcher.reset();
        ReflectionTestUtils.setField(
                campaignDispatchService, "nanoTimeSource", (LongSupplier) System::nanoTime);
        ReflectionTestUtils.setField(campaignSendWorker, "dispatchEnabled", false);
        lenient().when(capabilityRegistry.isAvailable(Capability.CAMPAIGN_DELIVERY))
                .thenReturn(true);
        lenient().when(deliveryProviderConfigService.isReady(anyInt(), eq(DeliveryChannel.EMAIL)))
                .thenReturn(true);
        lenient().when(deliveryProviderConfigService.resolveForWorkspace(anyInt(), eq(DeliveryChannel.EMAIL)))
                .thenAnswer(invocation -> resolvedTarget(invocation.getArgument(0)));
        lenient().when(triggeredSendGate.enabled()).thenReturn(true);
        lenient().when(triggeredSendGate.dispatchPageSize()).thenReturn(200);
        lenient().when(triggeredSendGate.recipientLimit()).thenReturn(200);
    }

    @Test
    void enrollCreatesOneTriggeredSendAndOneIdempotentDelivery() {
        String prefix = "triggered-" + unique();
        Person person = person(prefix, prefix + "@example.com");
        CampaignMessageDto message = message(prefix);

        CampaignTriggeredSendService.EnrollmentResult first = triggeredSendService.enroll(
                person.getId(), message.id(), 1);
        CampaignTriggeredSendService.EnrollmentResult replay = triggeredSendService.enroll(
                person.getId(), message.id(), 1);

        assertTrue(first.enrolled());
        assertFalse(replay.enrolled());
        assertEquals(first.sendId(), replay.sendId());
        assertEquals(first.deliveryId(), replay.deliveryId());
        assertEquals(1, campaignDeliveryMapper.pendingDeliveryIds(
                workspace.getId(), first.sendId()).size());
        CampaignSendDto send = campaignSendService.listSends(message.campaignId()).getFirst();
        assertTrue(send.snapshotId() > 0);
        assertEquals("triggered", send.origin());
        assertEquals("triggered", send.status());
        assertEquals(1, send.totalRecipients());
        assertEquals(1, auditCount("campaign.send.triggered_create", message.campaignId()));
        assertEquals(1, auditCount("campaign.send.triggered_enroll", message.campaignId()));
    }

    @Test
    void ruleActionExecutorQueuesTheDeliveryWithoutDispatchingIt() {
        String prefix = "action-" + unique();
        Person person = person(prefix, prefix + "@example.com");
        CampaignMessageDto message = message(prefix);
        RuleAction action = new RuleAction();
        action.setType("send_message");
        action.setCampaignMessageId(message.id());
        action.setCampaignMessageVersion(1);

        ruleActionExecutor.execute(action, new RuleFireContext(
                workspace.getId(), 19, "person", person.getId(), currentUser.getId(), "fire"));

        CampaignSendDto send = campaignSendService.listSends(message.campaignId()).getFirst();
        assertEquals(1, campaignDeliveryMapper.pendingDeliveryIds(
                workspace.getId(), send.id()).size());
        assertEquals(0, fakeDispatcher.count());
    }

    @Test
    void drainedTriggeredSendRemainsRollbackIsolatedAndWakesForALaterContact() {
        String prefix = "drain-" + unique();
        Person first = person(prefix + "-a", prefix + "-a@example.com");
        Person second = person(prefix + "-b", prefix + "-b@example.com");
        CampaignMessageDto message = message(prefix);
        CampaignTriggeredSendService.EnrollmentResult firstEnrollment =
                triggeredSendService.enroll(first.getId(), message.id(), 1);
        int sendId = firstEnrollment.sendId();

        assertTrue(campaignDispatchService.processSend(workspace.getId(), sendId));
        assertEquals("send:" + sendId + ":" + firstEnrollment.deliveryId(),
                fakeDispatcher.requests().getFirst().dedupeKey());
        assertTrue(fakeDispatcher.requests().getFirst().providerDeadlineNanos() != null);
        assertEquals("triggered", campaignSendMapper.getSend(workspace.getId(), sendId).getStatus());
        assertFalse(campaignSendMapper.queuedSendIds(workspace.getId(), false).contains(sendId));

        triggeredSendService.enroll(second.getId(), message.id(), 1);

        assertTrue(campaignSendMapper.queuedSendIds(workspace.getId(), true).contains(sendId));
        assertTrue(campaignDispatchService.processSend(workspace.getId(), sendId));
        assertEquals(2, fakeDispatcher.count());
        assertEquals("triggered", campaignSendMapper.getSend(workspace.getId(), sendId).getStatus());
        assertEquals(2, campaignSendMapper.getSend(workspace.getId(), sendId).getTotalRecipients());
    }

    @Test
    void triggeredDispatchReusesSuppressionEligibilityAndRecordsTheSkip() {
        String prefix = "suppressed-" + unique();
        Person person = person(prefix, prefix + "@example.com");
        CampaignMessageDto message = message(prefix);
        CampaignTriggeredSendService.EnrollmentResult enrollment = triggeredSendService.enroll(
                person.getId(), message.id(), 1);
        suppressionService.add(new SuppressionEntryRequest(
                "workspace", "email", person.getEmail(), person.getId(), "manual", null));

        campaignDispatchService.processSend(workspace.getId(), enrollment.sendId());

        CampaignDelivery delivery = campaignDeliveryMapper.getDelivery(
                workspace.getId(), enrollment.deliveryId());
        assertEquals(0, fakeDispatcher.count());
        assertEquals("skipped", delivery.getStatus());
        assertEquals("suppressed", delivery.getSkipReason());
    }

    @Test
    void frequencyCappedSkipIsPermanentForTheSameMessageRevision() {
        String prefix = "frequency-" + unique();
        Person person = person(prefix, prefix + "@example.com");
        CampaignMessageDto firstMessage = message(prefix + "-first");
        CampaignMessageDto secondMessage = message(prefix + "-second");
        int firstSendId = triggeredSendService.enroll(person.getId(), firstMessage.id(), 1).sendId();
        campaignDispatchService.processSend(workspace.getId(), firstSendId);
        CampaignTriggeredSendService.EnrollmentResult second = triggeredSendService.enroll(
                person.getId(), secondMessage.id(), 1);

        campaignDispatchService.processSend(workspace.getId(), second.sendId());
        CampaignTriggeredSendService.EnrollmentResult replay = triggeredSendService.enroll(
                person.getId(), secondMessage.id(), 1);

        CampaignDelivery delivery = campaignDeliveryMapper.getDelivery(
                workspace.getId(), second.deliveryId());
        assertEquals("skipped", delivery.getStatus());
        assertEquals("frequency_capped", delivery.getSkipReason());
        assertFalse(replay.enrolled());
        assertEquals("delivery_capped", replay.outcome());
        assertEquals(second.deliveryId(), replay.deliveryId());
        assertEquals(1, fakeDispatcher.count());
    }

    @Test
    void restrictionAddedAfterEnrollmentIsAppliedBeforeDispatch() {
        String prefix = "dispatch-restricted-" + unique();
        Person person = person(prefix, prefix + "@example.com");
        CampaignMessageDto message = message(prefix);
        CampaignTriggeredSendService.EnrollmentResult enrollment = triggeredSendService.enroll(
                person.getId(), message.id(), 1);
        personMapper.updateProcessingRestrictions(workspace.getId(), person.getId(), false, true);

        campaignDispatchService.processSend(workspace.getId(), enrollment.sendId());

        CampaignDelivery delivery = campaignDeliveryMapper.getDelivery(
                workspace.getId(), enrollment.deliveryId());
        assertEquals("skipped", delivery.getStatus());
        assertEquals("restricted", delivery.getSkipReason());
        assertEquals(0, fakeDispatcher.count());
    }

    @Test
    void addresslessContactIsExcludedBeforeCreatingASend() {
        String prefix = "addressless-" + unique();
        Person person = person(prefix, null);
        CampaignMessageDto message = message(prefix);

        CampaignTriggeredSendService.EnrollmentResult result = triggeredSendService.enroll(
                person.getId(), message.id(), 1);

        assertEquals("no_address", result.exclusionReason());
        assertTrue(campaignSendMapper.getSendsByCampaign(
                workspace.getId(), message.campaignId()).isEmpty());
    }

    @Test
    void legacyPauseAndCancelTransitionsCannotMutateTriggeredSend() {
        String prefix = "paused-" + unique();
        Person first = person(prefix + "-a", prefix + "-a@example.com");
        Person second = person(prefix + "-b", prefix + "-b@example.com");
        CampaignMessageDto message = message(prefix);
        int sendId = triggeredSendService.enroll(first.getId(), message.id(), 1).sendId();
        assertThrows(BadRequestException.class, () -> campaignSendService.pauseSend(
                message.campaignId(), sendId));
        assertThrows(BadRequestException.class, () -> campaignSendService.cancelSend(
                message.campaignId(), sendId));
        assertEquals(0, campaignSendMapper.transitionStatus(
                workspace.getId(), sendId, "queued", "paused"));

        assertTrue(triggeredSendService.enroll(
                second.getId(), message.id(), 1).enrolled());

        assertEquals(2, campaignSendMapper.getSend(workspace.getId(), sendId).getTotalRecipients());
    }

    @Test
    void restrictedContactWritesNoDeliveryAddressAndStrictAuditsTheReason() {
        String prefix = "restricted-" + unique();
        Person person = person(prefix, prefix + "@example.com");
        CampaignMessageDto message = message(prefix);
        personMapper.updateProcessingRestrictions(workspace.getId(), person.getId(), false, true);

        CampaignTriggeredSendService.EnrollmentResult result = triggeredSendService.enroll(
                person.getId(), message.id(), 1);

        assertEquals("restricted", result.exclusionReason());
        assertTrue(campaignSendMapper.getSendsByCampaign(
                workspace.getId(), message.campaignId()).isEmpty());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM campaign_delivery WHERE workspace_id = ? AND person_id = ?",
                Integer.class,
                workspace.getId(),
                person.getId()));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM campaign_delivery WHERE workspace_id = ? AND address = ?",
                Integer.class,
                workspace.getId(),
                person.getEmail()));
        assertEquals("restricted", jdbcTemplate.queryForObject(
                "SELECT JSON_UNQUOTE(JSON_EXTRACT(changes, '$.reason')) FROM audit_log "
                        + "WHERE workspace_id = ? AND action = 'campaign.send.triggered_exclude' "
                        + "AND entity_id = ? ORDER BY id DESC LIMIT 1",
                String.class,
                workspace.getId(),
                message.campaignId()));
    }

    @Test
    void dispatchFenceLeavesPendingTriggeredDeliveryIntactUntilReopened() {
        String prefix = "fence-" + unique();
        Person person = person(prefix, prefix + "@example.com");
        CampaignMessageDto message = message(prefix);
        CampaignTriggeredSendService.EnrollmentResult result = triggeredSendService.enroll(
                person.getId(), message.id(), 1);
        CampaignDispatchService closedInstance = dispatchService(false);

        assertEquals(0, closedInstance.processWorkspace(workspace.getId()));
        assertTrue(closedInstance.processSend(workspace.getId(), result.sendId()));
        assertEquals("pending", campaignDeliveryMapper.getDelivery(
                workspace.getId(), result.deliveryId()).getStatus());
        assertEquals(0, fakeDispatcher.count());

        CampaignDispatchService openedInstance = dispatchService(true);
        assertEquals(0, openedInstance.processWorkspace(workspace.getId()));
        assertEquals("dispatched", campaignDeliveryMapper.getDelivery(
                workspace.getId(), result.deliveryId()).getStatus());
        assertEquals(1, fakeDispatcher.count());
    }

    @Test
    void disabledRestartRecoversAnExpiredIdempotentClaimWithoutProviderEgress() {
        String prefix = "fence-restart-" + unique();
        Person person = person(prefix, prefix + "@example.com");
        CampaignMessageDto message = message(prefix);
        CampaignTriggeredSendService.EnrollmentResult result = triggeredSendService.enroll(
                person.getId(), message.id(), 1);
        assertEquals(1, campaignDeliveryMapper.claimTriggered(
                workspace.getId(), result.deliveryId(), "dead-worker", 1_000_000L,
                CampaignSendServiceTest.FakeDispatcher.ID,
                resolvedTarget(workspace.getId()).attemptTargetFingerprint()));
        jdbcTemplate.update(
                "UPDATE campaign_delivery SET dispatch_lease_until = "
                        + "DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 SECOND) "
                        + "WHERE workspace_id = ? AND id = ?",
                workspace.getId(), result.deliveryId());
        CampaignDispatchService closedInstance = dispatchService(false);

        assertTrue(closedInstance.processSend(workspace.getId(), result.sendId()));

        assertEquals("pending", campaignDeliveryMapper.getDelivery(
                workspace.getId(), result.deliveryId()).getStatus());
        assertEquals(0, fakeDispatcher.count());

        CampaignDispatchService openedInstance = dispatchService(true);
        assertTrue(openedInstance.processSend(workspace.getId(), result.sendId()));
        assertEquals("dispatched", campaignDeliveryMapper.getDelivery(
                workspace.getId(), result.deliveryId()).getStatus());
        assertEquals(1, fakeDispatcher.count());
    }

    @Test
    void deadlineAnchoredBeforeLeaseRenewalCanExpireWithoutProviderEgress() {
        String prefix = "deadline-before-egress-" + unique();
        Person person = person(prefix, prefix + "@example.com");
        CampaignMessageDto message = message(prefix);
        CampaignTriggeredSendService.EnrollmentResult result = triggeredSendService.enroll(
                person.getId(), message.id(), 1);
        AtomicLong nanoTime = new AtomicLong(1_000L);
        ReflectionTestUtils.setField(
                campaignDispatchService, "nanoTimeSource", (LongSupplier) nanoTime::get);
        doAnswer(invocation -> {
            nanoTime.addAndGet(Duration.ofMinutes(1).toNanos());
            return null;
        }).when(claimBoundary).beforeProviderLeaseRenewal(
                workspace.getId(), result.deliveryId());

        assertTrue(campaignDispatchService.processSend(workspace.getId(), result.sendId()));

        assertEquals("failed", campaignDeliveryMapper.getDelivery(
                workspace.getId(), result.deliveryId()).getStatus());
        assertEquals(0, fakeDispatcher.count());
    }

    @Test
    void ambiguousProviderOutcomeIsTerminalAndNotAutomaticallyReplayed() {
        String prefix = "ambiguous-delivery-" + unique();
        Person person = person(prefix, prefix + "@example.com");
        CampaignMessageDto message = message(prefix);
        CampaignTriggeredSendService.EnrollmentResult result = triggeredSendService.enroll(
                person.getId(), message.id(), 1);
        fakeDispatcher.returnAmbiguous();

        assertTrue(campaignDispatchService.processSend(workspace.getId(), result.sendId()));
        assertTrue(campaignDispatchService.processSend(workspace.getId(), result.sendId()));

        CampaignDelivery delivery = campaignDeliveryMapper.getDelivery(
                workspace.getId(), result.deliveryId());
        assertEquals("failed", delivery.getStatus());
        assertTrue(delivery.getLastError().startsWith("AMBIGUOUS:"));
        assertTrue(delivery.getReconciliationRequiredAt() != null);
        assertEquals(1, fakeDispatcher.count());
    }

    @Test
    void expiredTriggeredClaimIsRecoveredAfterWorkerLoss() {
        String prefix = "expired-claim-" + unique();
        Person person = person(prefix, prefix + "@example.com");
        CampaignMessageDto message = message(prefix);
        CampaignTriggeredSendService.EnrollmentResult result = triggeredSendService.enroll(
                person.getId(), message.id(), 1);

        assertEquals(1, campaignDeliveryMapper.claimTriggered(
                workspace.getId(), result.deliveryId(), "dead-worker", 1_000_000L,
                CampaignSendServiceTest.FakeDispatcher.ID,
                resolvedTarget(workspace.getId()).attemptTargetFingerprint()));
        jdbcTemplate.update(
                "UPDATE campaign_delivery SET dispatch_lease_until = "
                        + "DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 SECOND) "
                        + "WHERE workspace_id = ? AND id = ?",
                workspace.getId(), result.deliveryId());

        ReflectionTestUtils.setField(campaignSendWorker, "dispatchEnabled", true);
        try {
            campaignSendWorker.dispatch();
        } finally {
            ReflectionTestUtils.setField(campaignSendWorker, "dispatchEnabled", false);
        }

        assertEquals("dispatched", campaignDeliveryMapper.getDelivery(
                workspace.getId(), result.deliveryId()).getStatus());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM campaign_delivery WHERE workspace_id = ? AND id = ? "
                        + "AND dispatch_lease_owner IS NOT NULL",
                Integer.class, workspace.getId(), result.deliveryId()));
        assertEquals(1, fakeDispatcher.count());
    }

    @Test
    void expiredClaimWithChangedConnectorBecomesAmbiguousWithoutProviderEgress() {
        String prefix = "expired-changed-target-" + unique();
        Person person = person(prefix, prefix + "@example.com");
        CampaignMessageDto message = message(prefix);
        CampaignTriggeredSendService.EnrollmentResult result = triggeredSendService.enroll(
                person.getId(), message.id(), 1);
        ResolvedDeliveryProvider original = resolvedTarget(workspace.getId());

        assertEquals(1, campaignDeliveryMapper.claimTriggered(
                workspace.getId(), result.deliveryId(), "dead-worker", 1_000_000L,
                original.providerId(), original.attemptTargetFingerprint()));
        jdbcTemplate.update(
                "UPDATE campaign_delivery SET dispatch_lease_until = "
                        + "DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 SECOND) "
                        + "WHERE workspace_id = ? AND id = ?",
                workspace.getId(), result.deliveryId());
        ResolvedDeliveryProvider changed = new ResolvedDeliveryProvider(
                CampaignSendServiceTest.FakeDispatcher.ID,
                DeliveryChannel.EMAIL,
                workspace.getId(),
                "https://account-b.example.test/send",
                null,
                null,
                DeliveryCredentials.none());
        when(deliveryProviderConfigService.resolveForWorkspace(
                workspace.getId(), DeliveryChannel.EMAIL)).thenReturn(changed);

        assertTrue(dispatchService(true).processSend(workspace.getId(), result.sendId()));

        CampaignDelivery delivery = campaignDeliveryMapper.getDelivery(
                workspace.getId(), result.deliveryId());
        assertEquals("failed", delivery.getStatus());
        assertEquals("delivery_target_changed", delivery.getLastErrorCode());
        assertTrue(delivery.getReconciliationRequiredAt() != null);
        assertEquals(0, fakeDispatcher.count());
    }

    @Test
    void expiredSmtpClaimBecomesAmbiguousAndIsNeverAutomaticallyReplayed() {
        String prefix = "expired-smtp-" + unique();
        Person person = person(prefix, prefix + "@example.com");
        CampaignMessageDto message = message(prefix);
        CampaignTriggeredSendService.EnrollmentResult result = triggeredSendService.enroll(
                person.getId(), message.id(), 1);

        assertEquals(1, campaignDeliveryMapper.claimTriggered(
                workspace.getId(), result.deliveryId(), "dead-smtp-worker", 1_000_000L,
                "smtp", "0".repeat(64)));
        jdbcTemplate.update(
                "UPDATE campaign_delivery SET dispatch_lease_until = "
                        + "DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 SECOND) "
                        + "WHERE workspace_id = ? AND id = ?",
                workspace.getId(), result.deliveryId());

        assertTrue(dispatchService(true).processSend(workspace.getId(), result.sendId()));
        assertTrue(dispatchService(true).processSend(workspace.getId(), result.sendId()));

        CampaignDelivery delivery = campaignDeliveryMapper.getDelivery(
                workspace.getId(), result.deliveryId());
        assertEquals("failed", delivery.getStatus());
        assertTrue(delivery.getReconciliationRequiredAt() != null);
        assertTrue(delivery.getLastError().startsWith("AMBIGUOUS:"));
        assertEquals(0, fakeDispatcher.count());
    }

    @Test
    void ambiguousDeliveryCanBeConfirmedDeliveredIdempotentlyAndIsStrictlyAudited() {
        String prefix = "reconcile-delivered-" + unique();
        Person person = person(prefix, prefix + "@example.com");
        CampaignMessageDto message = message(prefix);
        CampaignTriggeredSendService.EnrollmentResult enrollment = triggeredSendService.enroll(
                person.getId(), message.id(), 1);
        fakeDispatcher.returnAmbiguous();
        campaignDispatchService.processSend(workspace.getId(), enrollment.sendId());

        CampaignTriggeredSendService.EnrollmentResult replay = triggeredSendService.enroll(
                person.getId(), message.id(), 1);
        CampaignDeliveryReconciliationRequest request =
                new CampaignDeliveryReconciliationRequest("delivered");
        CampaignDeliveryReconciliationDto first = triggeredSendService.reconcile(
                message.campaignId(), enrollment.deliveryId(), request);
        CampaignDeliveryReconciliationDto repeated = triggeredSendService.reconcile(
                message.campaignId(), enrollment.deliveryId(), request);

        assertEquals("delivery_reconciliation_required", replay.outcome());
        assertEquals("dispatched", first.status());
        assertFalse(first.reconciliationRequired());
        assertEquals(first, repeated);
        assertEquals(1, auditCount(
                "campaign.delivery.reconcile", message.campaignId()));
    }

    @Test
    void confirmedNotDeliveredAllowsANewEnrollmentWithoutRewritingTheOldEvidence() {
        String prefix = "reconcile-not-delivered-" + unique();
        Person person = person(prefix, prefix + "@example.com");
        CampaignMessageDto message = message(prefix);
        CampaignTriggeredSendService.EnrollmentResult original = triggeredSendService.enroll(
                person.getId(), message.id(), 1);
        fakeDispatcher.returnAmbiguous();
        campaignDispatchService.processSend(workspace.getId(), original.sendId());

        CampaignDeliveryReconciliationDto resolved = triggeredSendService.reconcile(
                message.campaignId(), original.deliveryId(),
                new CampaignDeliveryReconciliationRequest("not_delivered"));
        CampaignTriggeredSendService.EnrollmentResult replacement = triggeredSendService.enroll(
                person.getId(), message.id(), 1);

        assertEquals("failed", resolved.status());
        assertFalse(resolved.reconciliationRequired());
        assertTrue(replacement.enrolled());
        assertFalse(original.deliveryId().equals(replacement.deliveryId()));
        CampaignDelivery originalRow = campaignDeliveryMapper.getDelivery(
                workspace.getId(), original.deliveryId());
        assertEquals("operator_not_delivered", originalRow.getReconciliationOutcome());
    }

    @Test
    void originMainRollbackOracleCannotSweepOrMutateTriggeredRowsAndCanReadSnapshotId() {
        String prefix = "rollback-" + unique();
        Person person = person(prefix, prefix + "@example.com");
        CampaignMessageDto message = message(prefix);
        CampaignTriggeredSendService.EnrollmentResult result = triggeredSendService.enroll(
                person.getId(), message.id(), 1);

        String originMainColumns = "id, workspace_id, campaign_id, snapshot_id, message_id, "
                + "message_version, channel, purpose, provider_id, status, scheduled_at, started_at, "
                + "completed_at, total_recipients, dispatched_count, skipped_count, failed_count, "
                + "created_by_id, created_at, updated_at";
        CampaignSend oldReader = jdbcTemplate.queryForObject(
                "SELECT " + originMainColumns + " FROM campaign_send "
                        + "WHERE workspace_id = ? AND id = ?",
                BeanPropertyRowMapper.newInstance(CampaignSend.class),
                workspace.getId(),
                result.sendId());
        assertTrue(oldReader.getSnapshotId() > 0);
        Map<String, Object> oldSnapshot = jdbcTemplate.queryForList(
                "SELECT version, record_type, channel, purpose, estimated_included, "
                        + "excluded_total, excluded_no_address, excluded_consent, "
                        + "excluded_suppressed, excluded_restricted, created_by_id, created_at "
                        + "FROM campaign_audience_snapshot "
                        + "WHERE workspace_id = ? AND campaign_id = ? ORDER BY version DESC",
                workspace.getId(), message.campaignId()).getFirst();
        assertEquals("Triggered (system)", oldSnapshot.get("purpose"));
        assertEquals(0L, ((Number) oldSnapshot.get("estimated_included")).longValue());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM campaign_audience_member "
                        + "WHERE workspace_id = ? AND snapshot_id = ?",
                Integer.class, workspace.getId(), oldReader.getSnapshotId()));

        List<Integer> originMainSweep = jdbcTemplate.queryForList(
                "SELECT id FROM campaign_send "
                        + "WHERE workspace_id = ? AND status IN ('queued', 'running') ORDER BY id",
                Integer.class,
                workspace.getId());
        assertFalse(originMainSweep.contains(result.sendId()));
        jdbcTemplate.update(
                "UPDATE campaign_send SET status = 'paused' "
                        + "WHERE workspace_id = ? AND id = ? AND status IN ('queued', 'running')",
                workspace.getId(), result.sendId());
        jdbcTemplate.update(
                "UPDATE campaign_send SET status = 'cancelled' "
                        + "WHERE workspace_id = ? AND id = ? "
                        + "AND status IN ('draft', 'queued', 'running', 'paused')",
                workspace.getId(), result.sendId());
        jdbcTemplate.update(
                "UPDATE campaign_send SET status = 'completed' "
                        + "WHERE workspace_id = ? AND id = ? AND status = 'running'",
                workspace.getId(), result.sendId());
        assertEquals("triggered", campaignSendMapper.getSend(
                workspace.getId(), result.sendId()).getStatus());

        assertTrue(campaignDispatchService.processSend(workspace.getId(), result.sendId()));
        assertEquals("dispatched", campaignDeliveryMapper.getDelivery(
                workspace.getId(), result.deliveryId()).getStatus());
    }

    @Test
    void enrollmentRequiresManageSendAndConsentPermissions() {
        String prefix = "permission-" + unique();
        Person person = person(prefix, prefix + "@example.com");
        CampaignMessageDto message = message(prefix);
        CampaignActorRole withoutManage = newCampaignActor(
                workspace,
                List.of(Permission.CAMPAIGN_SEND, Permission.CONSENT_MANAGE));
        authenticateAs(withoutManage.actor(), workspace.getId());

        assertThrows(ForbiddenException.class, () -> triggeredSendService.enroll(
                person.getId(), message.id(), 1));

        authenticateAs(currentUser, workspace.getId());
        CampaignActorRole withoutConsent = newCampaignActor(
                workspace,
                List.of(Permission.CAMPAIGN_MANAGE, Permission.CAMPAIGN_SEND));
        authenticateAs(withoutConsent.actor(), workspace.getId());
        assertThrows(ForbiddenException.class, () -> triggeredSendService.enroll(
                person.getId(), message.id(), 1));

        authenticateAs(currentUser, workspace.getId());
        CampaignActorRole withoutSend = newCampaignActor(
                workspace,
                List.of(Permission.CAMPAIGN_MANAGE, Permission.CONSENT_MANAGE));
        authenticateAs(withoutSend.actor(), workspace.getId());
        assertThrows(ForbiddenException.class, () -> triggeredSendService.enroll(
                person.getId(), message.id(), 1));
    }

    @Test
    void reconciliationRequiresLockedCampaignAndConsentManagementPermissions() {
        String prefix = "reconcile-permission-" + unique();
        Person person = person(prefix, prefix + "@example.com");
        CampaignMessageDto message = message(prefix);
        CampaignTriggeredSendService.EnrollmentResult enrollment = triggeredSendService.enroll(
                person.getId(), message.id(), 1);
        fakeDispatcher.returnAmbiguous();
        campaignDispatchService.processSend(workspace.getId(), enrollment.sendId());
        CampaignActorRole withoutConsent = newCampaignActor(
                workspace,
                List.of(Permission.CAMPAIGN_MANAGE));
        authenticateAs(withoutConsent.actor(), workspace.getId());

        assertThrows(ForbiddenException.class, () -> triggeredSendService.reconcile(
                message.campaignId(),
                enrollment.deliveryId(),
                new CampaignDeliveryReconciliationRequest("delivered")));

        authenticateAs(currentUser, workspace.getId());
        assertTrue(campaignDeliveryMapper.getDelivery(
                workspace.getId(), enrollment.deliveryId()).getReconciliationRequiredAt() != null);
    }

    @Test
    void capabilityAndProviderReadinessFailClosedWithoutWriting() {
        String prefix = "readiness-" + unique();
        Person person = person(prefix, prefix + "@example.com");
        CampaignMessageDto message = message(prefix);
        when(capabilityRegistry.isAvailable(Capability.CAMPAIGN_DELIVERY)).thenReturn(false);

        assertThrows(ForbiddenException.class, () -> triggeredSendService.enroll(
                person.getId(), message.id(), 1));

        when(capabilityRegistry.isAvailable(Capability.CAMPAIGN_DELIVERY)).thenReturn(true);
        when(deliveryProviderConfigService.isReady(workspace.getId(), DeliveryChannel.EMAIL))
                .thenReturn(false);
        assertThrows(BadRequestException.class, () -> triggeredSendService.enroll(
                person.getId(), message.id(), 1));
        assertTrue(campaignSendMapper.getSendsByCampaign(
                workspace.getId(), message.campaignId()).isEmpty());
    }

    @Test
    void anotherWorkspaceCannotEnrollAgainstTheMessage() {
        String prefix = "isolation-" + unique();
        Person person = person(prefix, prefix + "@example.com");
        CampaignMessageDto message = message(prefix);
        CampaignActorWorkspace other = newCampaignWorkspaceActor();
        authenticateAs(other.actor(), other.workspace().getId());

        assertThrows(ResourceNotFoundException.class, () -> triggeredSendService.enroll(
                person.getId(), message.id(), 1));

        assertTrue(campaignSendMapper.getSendsByCampaign(
                other.workspace().getId(), message.campaignId()).isEmpty());
        authenticateAs(currentUser, workspace.getId());
        assertTrue(campaignSendMapper.getSendsByCampaign(
                workspace.getId(), message.campaignId()).isEmpty());
    }

    private CampaignMessageDto message(String prefix) {
        CampaignDto campaign = campaignService.create(new CampaignRequest(
                "Campaign " + prefix,
                null,
                "email",
                null,
                currentUser.getId(),
                null,
                null,
                null,
                null,
                null));
        CampaignMessageDto message = campaignSendService.createMessage(
                campaign.id(), new CampaignMessageRequest("Message " + prefix, "email"));
        campaignSendService.addRevision(
                campaign.id(),
                message.id(),
                new CampaignMessageRevisionRequest(
                        "en",
                        "Hello",
                        "<p>Hello</p><a href=\"{{unsubscribe_url}}\">unsubscribe</a>",
                        null));
        return message;
    }

    private Person person(String name, String email) {
        Company company = newCompany();
        Person person = new Person();
        person.setWorkspaceId(workspace.getId());
        person.setName(name);
        person.setEmail(email);
        person.setTitle("Marketing contact");
        person.setCompany(company);
        personMapper.insert(person);
        return person;
    }

    private int auditCount(String action, int campaignId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log "
                        + "WHERE workspace_id = ? AND action = ? AND entity_id = ?",
                Integer.class,
                workspace.getId(),
                action,
                campaignId);
        return count == null ? 0 : count;
    }

    private CampaignDispatchService dispatchService(boolean enabled) {
        return new CampaignDispatchService(
                campaignSendMapper,
                campaignDeliveryMapper,
                campaignMessageMapper,
                audienceEligibilityService,
                deliveryProviderConfigService,
                deliveryProviderRouter,
                deliveryProperties,
                capabilityRegistry,
                new WorkflowTriggeredSendGate(enabled, 200, 200),
                workflowRunMapper,
                new CampaignDispatchClaimBoundary());
    }

    private ResolvedDeliveryProvider resolvedTarget(int workspaceId) {
        return new ResolvedDeliveryProvider(
                CampaignSendServiceTest.FakeDispatcher.ID,
                DeliveryChannel.EMAIL,
                workspaceId,
                null,
                null,
                null,
                DeliveryCredentials.none(),
                true,
                "f".repeat(64),
                null);
    }
}
