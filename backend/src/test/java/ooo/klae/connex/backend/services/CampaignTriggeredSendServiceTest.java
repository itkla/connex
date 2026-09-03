package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.CampaignDelivery;
import ooo.klae.connex.backend.beans.CampaignSend;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.capability.Capability;
import ooo.klae.connex.backend.capability.CapabilityRegistry;
import ooo.klae.connex.backend.delivery.CampaignDispatchService;
import ooo.klae.connex.backend.delivery.DeliveryChannel;
import ooo.klae.connex.backend.delivery.DeliveryCredentials;
import ooo.klae.connex.backend.delivery.DeliveryProviderConfigService;
import ooo.klae.connex.backend.delivery.ResolvedDeliveryProvider;
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
import ooo.klae.connex.backend.mappers.CampaignSendMapper;
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
    @Autowired private SuppressionService suppressionService;
    @Autowired private CampaignDeliveryMapper campaignDeliveryMapper;
    @Autowired private CampaignSendMapper campaignSendMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private CampaignSendServiceTest.FakeDispatcher fakeDispatcher;
    @MockitoBean private CapabilityRegistry capabilityRegistry;
    @MockitoBean private DeliveryProviderConfigService deliveryProviderConfigService;
    @MockitoBean private WorkflowTriggeredSendGate triggeredSendGate;

    @BeforeEach
    void resetDelivery() {
        fakeDispatcher.reset();
        lenient().when(capabilityRegistry.isAvailable(Capability.CAMPAIGN_DELIVERY))
                .thenReturn(true);
        lenient().when(deliveryProviderConfigService.isReady(anyInt(), eq(DeliveryChannel.EMAIL)))
                .thenReturn(true);
        lenient().when(deliveryProviderConfigService.resolveForWorkspace(anyInt(), eq(DeliveryChannel.EMAIL)))
                .thenAnswer(invocation -> ResolvedDeliveryProvider.of(
                        CampaignSendServiceTest.FakeDispatcher.ID,
                        DeliveryChannel.EMAIL,
                        invocation.getArgument(0),
                        DeliveryCredentials.none()));
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
        int sendId = triggeredSendService.enroll(first.getId(), message.id(), 1).sendId();

        assertTrue(campaignDispatchService.processSend(workspace.getId(), sendId));
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
        when(triggeredSendGate.enabled()).thenReturn(false);

        assertEquals(0, campaignDispatchService.processWorkspace(workspace.getId()));
        assertTrue(campaignDispatchService.processSend(workspace.getId(), result.sendId()));
        assertEquals("pending", campaignDeliveryMapper.getDelivery(
                workspace.getId(), result.deliveryId()).getStatus());
        assertEquals(0, fakeDispatcher.count());

        when(triggeredSendGate.enabled()).thenReturn(true);
        assertEquals(0, campaignDispatchService.processWorkspace(workspace.getId()));
        assertEquals("dispatched", campaignDeliveryMapper.getDelivery(
                workspace.getId(), result.deliveryId()).getStatus());
        assertEquals(1, fakeDispatcher.count());
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
}
