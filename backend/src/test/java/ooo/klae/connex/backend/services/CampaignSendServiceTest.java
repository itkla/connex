package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.CampaignDelivery;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.delivery.CampaignDispatchService;
import ooo.klae.connex.backend.delivery.DeliveryCapabilities;
import ooo.klae.connex.backend.delivery.DeliveryChannel;
import ooo.klae.connex.backend.delivery.DeliveryProviderConfigService;
import ooo.klae.connex.backend.delivery.DeliveryCredentials;
import ooo.klae.connex.backend.delivery.DeliveryRequest;
import ooo.klae.connex.backend.delivery.DispatchReceipt;
import ooo.klae.connex.backend.delivery.DispatchStatus;
import ooo.klae.connex.backend.delivery.MessageDispatcher;
import ooo.klae.connex.backend.delivery.ResolvedDeliveryProvider;
import ooo.klae.connex.backend.dto.CampaignAudienceRequest;
import ooo.klae.connex.backend.dto.CampaignAudienceSnapshotDto;
import ooo.klae.connex.backend.dto.CampaignDto;
import ooo.klae.connex.backend.dto.CampaignMessageDto;
import ooo.klae.connex.backend.dto.CampaignMessageRequest;
import ooo.klae.connex.backend.dto.CampaignMessageRevisionRequest;
import ooo.klae.connex.backend.dto.CampaignRequest;
import ooo.klae.connex.backend.dto.CampaignSendDto;
import ooo.klae.connex.backend.dto.CampaignSendRequest;
import ooo.klae.connex.backend.dto.ContactChannelConsentRequest;
import ooo.klae.connex.backend.dto.SegmentCondition;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.dto.SuppressionEntryRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.CampaignDeliveryMapper;
import ooo.klae.connex.backend.mappers.CampaignSendMapper;

@TestPropertySource(properties = "connex.delivery.enabled=true")
@Import(CampaignSendServiceTest.FakeDeliveryConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CampaignSendServiceTest extends CampaignRealDbTestSupport {

    @Autowired private CampaignService campaignService;
    @Autowired private CampaignSendService campaignSendService;
    @Autowired private ConsentService consentService;
    @Autowired private SuppressionService suppressionService;
    @Autowired private CampaignDispatchService campaignDispatchService;
    @Autowired private DeliveryUnsubscribeService deliveryUnsubscribeService;
    @Autowired private CampaignDeliveryMapper campaignDeliveryMapper;
    @Autowired private CampaignSendMapper campaignSendMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private FakeDispatcher fakeDispatcher;
    @MockitoBean private DeliveryProviderConfigService deliveryProviderConfigService;

    @BeforeEach
    void resetDelivery() {
        fakeDispatcher.reset();
        lenient().when(deliveryProviderConfigService.isReady(anyInt(), eq(DeliveryChannel.EMAIL)))
                .thenReturn(true);
        lenient().when(deliveryProviderConfigService.resolveForWorkspace(anyInt(), eq(DeliveryChannel.EMAIL)))
                .thenAnswer(invocation -> ResolvedDeliveryProvider.of(
                        FakeDispatcher.ID, DeliveryChannel.EMAIL, invocation.getArgument(0),
                        DeliveryCredentials.none()));
        lenient().when(deliveryProviderConfigService.isReady(anyInt(), eq(DeliveryChannel.SMS)))
                .thenReturn(true);
        lenient().when(deliveryProviderConfigService.resolveForWorkspace(anyInt(), eq(DeliveryChannel.SMS)))
                .thenAnswer(invocation -> ResolvedDeliveryProvider.of(
                        FakeDispatcher.ID, DeliveryChannel.SMS, invocation.getArgument(0),
                        DeliveryCredentials.none()));
    }

    @Test
    void createSendMaterializesIncludedMembersOnly() {
        String prefix = "send-" + unique();
        Company company = newCompany();
        Person included = person(company, prefix + "-in", prefix + "-in@example.com");
        person(company, prefix + "-out", prefix + "-out@example.com");
        consentService.setForPerson(included.getId(), grantedConsent());

        int sendId = readySend(prefix).id();

        List<Integer> pending = campaignDeliveryMapper.pendingDeliveryIds(workspace.getId(), sendId);
        assertEquals(1, pending.size());
        CampaignDelivery delivery = campaignDeliveryMapper.getDelivery(workspace.getId(), pending.getFirst());
        assertEquals(included.getId(), delivery.getPersonId());
        assertEquals(prefix + "-in@example.com", delivery.getAddress());
        assertEquals(64, delivery.getUnsubscribeToken().length());
    }

    @Test
    void sendOriginAndSnapshotMustRemainPaired() {
        String prefix = "send-origin-" + unique();
        person(newCompany(), prefix + "-in", prefix + "-in@example.com");
        CampaignSendDto audience = readySend(prefix);

        assertCheckViolation(() -> jdbcTemplate.update(
                "INSERT INTO campaign_send "
                        + "(workspace_id, campaign_id, snapshot_id, origin, message_id, message_version, "
                        + "channel, purpose, status) VALUES (?, ?, ?, 'audience', ?, ?, 'email', "
                        + "'marketing', 'triggered')",
                workspace.getId(), audience.campaignId(), audience.snapshotId(),
                audience.messageId(), audience.messageVersion()));
        assertCheckViolation(() -> jdbcTemplate.update(
                "INSERT INTO campaign_send "
                        + "(workspace_id, campaign_id, snapshot_id, origin, message_id, message_version, "
                        + "channel, purpose, status) VALUES (?, ?, ?, 'triggered', ?, ?, 'email', "
                        + "'marketing', 'running')",
                workspace.getId(), audience.campaignId(), audience.snapshotId(),
                audience.messageId(), audience.messageVersion()));
    }

    private static void assertCheckViolation(org.junit.jupiter.api.function.Executable write) {
        RuntimeException failure = assertThrows(RuntimeException.class, write);
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        assertTrue(root instanceof SQLException);
        SQLException sql = (SQLException) root;
        assertEquals("HY000", sql.getSQLState());
        assertEquals(3819, sql.getErrorCode());
    }

    @Test
    void emailSendResolvesAddressesFromPersonEmailNormalizedAndSkipsPersonsWithoutOne() {
        String prefix = "email-" + unique();
        Company company = newCompany();
        Person mailable = person(company, prefix + "-in-a", prefix + "-A@Example.COM");
        Person phoneOnly = phonePerson(company, prefix + "-in-b", "+81 50-1234-5678");
        consentService.setForPerson(mailable.getId(), grantedConsent());
        consentService.setForPerson(phoneOnly.getId(), grantedConsent());

        int sendId = readySend(prefix).id();

        List<Integer> pending = campaignDeliveryMapper.pendingDeliveryIds(workspace.getId(), sendId);
        assertEquals(1, pending.size());
        CampaignDelivery delivery = campaignDeliveryMapper.getDelivery(workspace.getId(), pending.getFirst());
        assertEquals(mailable.getId(), delivery.getPersonId());
        assertNotEquals(phoneOnly.getId(), delivery.getPersonId());
        assertEquals(prefix.toLowerCase() + "-a@example.com", delivery.getAddress());
    }

    @Test
    void queuingRequiresCampaignSendPermission() {
        String prefix = "rbac-" + unique();
        person(newCompany(), prefix + "-in", prefix + "-in@example.com");
        CampaignSendDto send = readySend(prefix);
        User member = newUser();
        authenticateAs(member, workspace.getId());

        assertThrows(ForbiddenException.class,
                () -> campaignSendService.queueSend(send.campaignId(), send.id()));
    }

    @Test
    void otherTenantCannotReadOrQueueSend() {
        String prefix = "iso-" + unique();
        person(newCompany(), prefix + "-in", prefix + "-in@example.com");
        CampaignSendDto send = readySend(prefix);
        CampaignActorWorkspace other = newCampaignWorkspaceActor();
        authenticateAs(other.actor(), other.workspace().getId());

        assertThrows(ResourceNotFoundException.class,
                () -> campaignSendService.getSend(send.campaignId(), send.id()));
        assertThrows(ResourceNotFoundException.class,
                () -> campaignSendService.queueSend(send.campaignId(), send.id()));
    }

    @Test
    void createSendRejectsForeignCampaignSnapshotAndMessageWithoutWritingSends() {
        String prefix = "create-send-iso-" + unique();
        CampaignDto foreignCampaign = campaignService.create(new CampaignRequest(
                "Foreign campaign " + prefix, null, "email", null, currentUser.getId(),
                null, null, null, null, null));
        campaignService.setAudience(foreignCampaign.id(), audience(prefix));
        campaignService.snapshotAudience(foreignCampaign.id());
        CampaignMessageDto foreignMessage = campaignSendService.createMessage(
                foreignCampaign.id(), new CampaignMessageRequest("Foreign message " + prefix, "email"));
        campaignSendService.addRevision(
                foreignCampaign.id(), foreignMessage.id(), emailRevision());

        CampaignActorWorkspace other = newCampaignWorkspaceActor();
        authenticateAs(other.actor(), other.workspace().getId());
        CampaignDto localMessageBoundary = campaignService.create(new CampaignRequest(
                "Local message boundary " + prefix, null, "email", null, other.actor().getId(),
                null, null, null, null, null));
        campaignService.setAudience(localMessageBoundary.id(), audience(prefix));
        campaignService.snapshotAudience(localMessageBoundary.id());
        CampaignMessageDto localMessage = campaignSendService.createMessage(
                localMessageBoundary.id(), new CampaignMessageRequest("Local message " + prefix, "email"));
        campaignSendService.addRevision(
                localMessageBoundary.id(), localMessage.id(), emailRevision());
        CampaignDto localSnapshotBoundary = campaignService.create(new CampaignRequest(
                "Local snapshot boundary " + prefix, null, "email", null, other.actor().getId(),
                null, null, null, null, null));
        CampaignMessageDto snapshotBoundaryMessage = campaignSendService.createMessage(
                localSnapshotBoundary.id(), new CampaignMessageRequest("Snapshot boundary " + prefix, "email"));
        campaignSendService.addRevision(
                localSnapshotBoundary.id(), snapshotBoundaryMessage.id(), emailRevision());

        CampaignSendRequest foreignRequest = new CampaignSendRequest(
                1, foreignMessage.id(), 1, "marketing", null);
        assertThrows(ResourceNotFoundException.class,
                () -> campaignSendService.createSend(foreignCampaign.id(), foreignRequest));
        assertThrows(ResourceNotFoundException.class,
                () -> campaignSendService.createSend(localMessageBoundary.id(), foreignRequest));
        assertThrows(ResourceNotFoundException.class, () -> campaignSendService.createSend(
                localSnapshotBoundary.id(), new CampaignSendRequest(
                        1, snapshotBoundaryMessage.id(), 1, "marketing", null)));
        assertTrue(campaignSendMapper.getSendsByCampaign(
                other.workspace().getId(), foreignCampaign.id()).isEmpty());
        assertTrue(campaignSendService.listSends(localMessageBoundary.id()).isEmpty());
        assertTrue(campaignSendService.listSends(localSnapshotBoundary.id()).isEmpty());

        authenticateAs(currentUser, workspace.getId());
        assertTrue(campaignSendService.listSends(foreignCampaign.id()).isEmpty());
    }

    @Test
    void dispatchSendsEachRecipientOnceAndIsIdempotent() {
        String prefix = "dispatch-" + unique();
        Person recipient = person(newCompany(), prefix + "-in", prefix + "-in@example.com");
        consentService.setForPerson(recipient.getId(), grantedConsent());
        CampaignSendDto send = readySend(prefix);
        campaignSendService.queueSend(send.campaignId(), send.id());

        campaignDispatchService.processSend(workspace.getId(), send.id());
        campaignDispatchService.processSend(workspace.getId(), send.id());

        assertEquals(1, fakeDispatcher.count());
        CampaignSendDto completed = campaignSendService.getSend(send.campaignId(), send.id());
        assertEquals("completed", completed.status());
        assertEquals(1, completed.dispatchedCount());
    }

    @Test
    void dispatchSkipsSuppressedRecipientWithoutSending() {
        String prefix = "skip-" + unique();
        Person person = person(newCompany(), prefix + "-in", prefix + "-in@example.com");
        consentService.setForPerson(person.getId(), grantedConsent());
        CampaignSendDto send = readySend(prefix);
        campaignSendService.queueSend(send.campaignId(), send.id());
        int deliveryId = campaignDeliveryMapper.pendingDeliveryIds(workspace.getId(), send.id()).getFirst();
        suppressionService.add(new SuppressionEntryRequest(
                "workspace", "email", person.getEmail(), person.getId(), "manual", null));

        campaignDispatchService.processSend(workspace.getId(), send.id());

        assertEquals(0, fakeDispatcher.count());
        CampaignDelivery delivery = campaignDeliveryMapper.getDelivery(workspace.getId(), deliveryId);
        assertEquals("skipped", delivery.getStatus());
        assertEquals("suppressed", delivery.getSkipReason());
    }

    @Test
    void unsubscribeSuppressesRevokesConsentAndIsIdempotent() {
        String prefix = "unsub-" + unique();
        Person person = person(newCompany(), prefix + "-in", prefix + "-in@example.com");
        consentService.setForPerson(person.getId(), grantedConsent());
        CampaignSendDto send = readySend(prefix);
        int deliveryId = campaignDeliveryMapper.pendingDeliveryIds(workspace.getId(), send.id()).getFirst();
        CampaignDelivery delivery = campaignDeliveryMapper.getDelivery(workspace.getId(), deliveryId);

        deliveryUnsubscribeService.unsubscribe(delivery.getUnsubscribeToken());
        deliveryUnsubscribeService.unsubscribe(delivery.getUnsubscribeToken());

        assertTrue(campaignDeliveryMapper.hasEvent(workspace.getId(), deliveryId, "unsubscribed"));
        assertEquals(1, suppressionService.list().stream()
                .filter(entry -> "unsubscribe".equals(entry.reason())
                        && entry.address().equals(person.getEmail().toLowerCase()))
                .count());
        assertTrue(consentService.getForPerson(person.getId()).stream()
                .anyMatch(state -> "email".equals(state.channel()) && "revoked".equals(state.status())));
    }

    @Test
    void smsSendResolvesAddressesFromPersonPhoneNormalizedAndSkipsPersonsWithoutOne() {
        String prefix = "sms-mat-" + unique();
        Company company = newCompany();
        Person textable = phonePerson(company, prefix + "-in-a", "+81 90-1234-5678");
        Person emailOnly = person(company, prefix + "-in-b", prefix + "-b@example.com");

        CampaignSendDto send = readySmsSend(prefix);
        int sendId = send.id();

        List<Integer> pending = campaignDeliveryMapper.pendingDeliveryIds(workspace.getId(), sendId);
        assertEquals(1, pending.size());
        CampaignDelivery delivery = campaignDeliveryMapper.getDelivery(workspace.getId(), pending.getFirst());
        assertEquals(textable.getId(), delivery.getPersonId());
        assertNotEquals(emailOnly.getId(), delivery.getPersonId());
        assertEquals("+819012345678", delivery.getAddress());
        CampaignAudienceSnapshotDto snapshot = campaignService.getSnapshot(send.campaignId(), 1);
        assertEquals(1, snapshot.excludedNoAddress());
        assertEquals(snapshot.estimatedIncluded(), send.totalRecipients());
    }

    @Test
    void createSendRejectsAMessageOnADifferentChannelThanTheSnapshot() {
        String prefix = "channel-mismatch-" + unique();
        Person person = person(newCompany(), prefix + "-in", prefix + "-in@example.com");
        person.setPhone("+819012345678");
        personMapper.update(person);
        CampaignDto campaign = campaignService.create(new CampaignRequest(
                "Campaign " + prefix, null, "email", null, currentUser.getId(),
                null, null, null, null, null));
        campaignService.setAudience(campaign.id(), audience(prefix, "email", "marketing"));
        campaignService.snapshotAudience(campaign.id());
        CampaignMessageDto message = campaignSendService.createMessage(
                campaign.id(), new CampaignMessageRequest("Message " + prefix, "sms"));
        campaignSendService.addRevision(campaign.id(), message.id(), new CampaignMessageRevisionRequest(
                "en", null, null, "Hi from {{unsubscribe_url}}"));

        BadRequestException exception = assertThrows(BadRequestException.class,
                () -> campaignSendService.createSend(campaign.id(),
                        new CampaignSendRequest(1, message.id(), 1, null, null)));

        assertEquals("This audience snapshot was frozen for the email channel "
                + "and cannot be sent with a sms message", exception.getMessage());
    }

    @Test
    void createSendRejectsADifferentPurposeAndAcceptsTheFrozenPurpose() {
        String prefix = "purpose-mismatch-" + unique();
        person(newCompany(), prefix + "-in", prefix + "-in@example.com");
        CampaignDto campaign = campaignService.create(new CampaignRequest(
                "Campaign " + prefix, null, "email", null, currentUser.getId(),
                null, null, null, null, null));
        campaignService.setAudience(campaign.id(), audience(prefix, "email", "product_update"));
        campaignService.snapshotAudience(campaign.id());
        CampaignMessageDto message = campaignSendService.createMessage(
                campaign.id(), new CampaignMessageRequest("Message " + prefix, "email"));
        campaignSendService.addRevision(campaign.id(), message.id(), new CampaignMessageRevisionRequest(
                "en", "Hello", "<p>Hi</p><a href=\"{{unsubscribe_url}}\">unsubscribe</a>", null));

        BadRequestException exception = assertThrows(BadRequestException.class,
                () -> campaignSendService.createSend(campaign.id(),
                        new CampaignSendRequest(1, message.id(), 1, "marketing", null)));
        CampaignSendDto send = campaignSendService.createSend(campaign.id(),
                new CampaignSendRequest(1, message.id(), 1, "product_update", null));

        assertEquals("This audience snapshot was frozen for the product_update purpose "
                + "and cannot be sent with the marketing purpose", exception.getMessage());
        assertEquals("product_update", send.purpose());
        assertEquals(1, send.totalRecipients());
    }

    @Test
    void dispatchingAnSmsSendHandsTheAdapterTheTextBodyOnly() {
        String prefix = "sms-disp-" + unique();
        phonePerson(newCompany(), prefix + "-in", "+81 90-1234-5678");
        CampaignSendDto send = readySmsSend(prefix);
        campaignSendService.queueSend(send.campaignId(), send.id());

        campaignDispatchService.processSend(workspace.getId(), send.id());

        assertEquals(1, fakeDispatcher.count());
        DeliveryRequest request = fakeDispatcher.requests().getFirst();
        assertEquals(DeliveryChannel.SMS, request.channel());
        assertEquals("+819012345678", request.address());
        assertNull(request.content().subject());
        assertNull(request.content().bodyHtml());
        assertTrue(request.content().bodyText().startsWith("Hi from "));
        CampaignSendDto completed = campaignSendService.getSend(send.campaignId(), send.id());
        assertEquals("completed", completed.status());
        assertEquals(1, completed.dispatchedCount());
    }

    /**
     * The invariant: a suppression stored in one phone formatting must block a send addressed in
     * another. Both sides run through the one shared normalizer, so the differently-formatted
     * suppression below collapses to the same value as the delivery address and matches.
     */
    @Test
    void smsSuppressionBlocksTheSendEvenWhenTheStoredPhoneIsFormattedDifferently() {
        String prefix = "sms-supp-" + unique();
        Person person = phonePerson(newCompany(), prefix + "-in", "+819012345678");
        CampaignSendDto send = readySmsSend(prefix);
        campaignSendService.queueSend(send.campaignId(), send.id());
        int deliveryId = campaignDeliveryMapper.pendingDeliveryIds(workspace.getId(), send.id()).getFirst();
        suppressionService.add(new SuppressionEntryRequest(
                "workspace", "sms", "+81 (90) 1234-5678", person.getId(), "do_not_contact", null));

        campaignDispatchService.processSend(workspace.getId(), send.id());

        assertEquals(0, fakeDispatcher.count());
        CampaignDelivery delivery = campaignDeliveryMapper.getDelivery(workspace.getId(), deliveryId);
        assertEquals("skipped", delivery.getStatus());
        assertEquals("suppressed", delivery.getSkipReason());
    }

    /**
     * The opt-out integrity invariant: an unsubscribe stores the person's {@code person_id} alongside
     * the phone as it read then. If the phone is later reformatted, the next send materializes a
     * differently-canonicalized address the stored suppression address no longer matches, but the
     * person-sticky arm keeps the dispatch re-check skipping them.
     */
    @Test
    void smsDispatchSkipsAPersonSuppressedByPersonRefEvenWhenTheDeliveryAddressWasReformatted() {
        String prefix = "sms-sticky-" + unique();
        Person person = phonePerson(newCompany(), prefix + "-in", "09012345678");
        CampaignSendDto send = readySmsSend(prefix);
        campaignSendService.queueSend(send.campaignId(), send.id());
        int deliveryId = campaignDeliveryMapper.pendingDeliveryIds(workspace.getId(), send.id()).getFirst();
        suppressionService.add(new SuppressionEntryRequest(
                "workspace", "sms", "+819012345678", person.getId(), "unsubscribe", null));

        campaignDispatchService.processSend(workspace.getId(), send.id());

        assertEquals(0, fakeDispatcher.count());
        CampaignDelivery delivery = campaignDeliveryMapper.getDelivery(workspace.getId(), deliveryId);
        assertEquals("skipped", delivery.getStatus());
        assertEquals("suppressed", delivery.getSkipReason());
    }

    @Test
    void anEmailSuppressionDoesNotBlockAnSmsSend() {
        String prefix = "sms-xchan-" + unique();
        Person person = phonePerson(newCompany(), prefix + "-in", "+819012345678");
        person.setEmail(prefix + "-in@example.com");
        personMapper.update(person);
        CampaignSendDto send = readySmsSend(prefix);
        campaignSendService.queueSend(send.campaignId(), send.id());
        suppressionService.add(new SuppressionEntryRequest(
                "workspace", "email", person.getEmail(), person.getId(), "manual", null));

        campaignDispatchService.processSend(workspace.getId(), send.id());

        assertEquals(1, fakeDispatcher.count());
    }

    @Test
    void anSmsSuppressionDoesNotBlockAnEmailSend() {
        String prefix = "email-xchan-" + unique();
        Person person = person(newCompany(), prefix + "-in", prefix + "-in@example.com");
        person.setPhone("+819012345678");
        personMapper.update(person);
        CampaignSendDto send = readySend(prefix);
        campaignSendService.queueSend(send.campaignId(), send.id());
        suppressionService.add(new SuppressionEntryRequest(
                "workspace", "sms", "+81 90-1234-5678", person.getId(), "manual", null));

        campaignDispatchService.processSend(workspace.getId(), send.id());

        assertEquals(1, fakeDispatcher.count());
    }

    @Test
    void aPersonWithNoConsentRecordIsIncludedAndDispatchedOnBothChannels() {
        String emailPrefix = "optout-email-" + unique();
        person(newCompany(), emailPrefix + "-in", emailPrefix + "-in@example.com");
        CampaignSendDto emailSend = readySend(emailPrefix);
        campaignSendService.queueSend(emailSend.campaignId(), emailSend.id());
        campaignDispatchService.processSend(workspace.getId(), emailSend.id());
        assertEquals(1, fakeDispatcher.count());

        fakeDispatcher.reset();
        String smsPrefix = "optout-sms-" + unique();
        phonePerson(newCompany(), smsPrefix + "-in", "+819012345678");
        CampaignSendDto smsSend = readySmsSend(smsPrefix);
        campaignSendService.queueSend(smsSend.campaignId(), smsSend.id());
        campaignDispatchService.processSend(workspace.getId(), smsSend.id());

        assertEquals(1, fakeDispatcher.count());
    }

    /**
     * Under opt-out both an unknown-consent person and a person with no consent record materialize.
     * Revoking one after the snapshot is frozen proves the dispatch-time re-check applies the same
     * policy and records its reason, while the other is still delivered.
     */
    @Test
    void aPersonWithUnknownConsentIsDispatchedAndAPersonWhoRevokedIsSkippedAsConsentRevoked() {
        String prefix = "consent-" + unique();
        Company company = newCompany();
        Person unknown = person(company, prefix + "-in-a", prefix + "-a@example.com");
        Person revoking = person(company, prefix + "-in-b", prefix + "-b@example.com");
        consentService.setForPerson(unknown.getId(), new ContactChannelConsentRequest(
                "email", "marketing", "unknown", "manual", null, null));
        CampaignSendDto send = readySend(prefix);
        assertEquals(2, send.totalRecipients());
        campaignSendService.queueSend(send.campaignId(), send.id());
        int unknownDeliveryId = deliveryIdFor(send.id(), unknown.getId());
        int revokedDeliveryId = deliveryIdFor(send.id(), revoking.getId());
        consentService.setForPerson(revoking.getId(), new ContactChannelConsentRequest(
                "email", "marketing", "revoked", "manual", null, null));

        campaignDispatchService.processSend(workspace.getId(), send.id());

        assertEquals(1, fakeDispatcher.count());
        CampaignDelivery unknownDelivery =
                campaignDeliveryMapper.getDelivery(workspace.getId(), unknownDeliveryId);
        CampaignDelivery revokedDelivery =
                campaignDeliveryMapper.getDelivery(workspace.getId(), revokedDeliveryId);
        assertEquals("dispatched", unknownDelivery.getStatus());
        assertEquals("skipped", revokedDelivery.getStatus());
        assertEquals("consent_revoked", revokedDelivery.getSkipReason());
    }

    @Test
    void anSmsPersonWhoRevokedSmsConsentIsSkippedAsConsentRevoked() {
        String prefix = "sms-consent-" + unique();
        Person person = phonePerson(newCompany(), prefix + "-in", "+819012345678");
        CampaignSendDto send = readySmsSend(prefix);
        campaignSendService.queueSend(send.campaignId(), send.id());
        int deliveryId = campaignDeliveryMapper.pendingDeliveryIds(workspace.getId(), send.id()).getFirst();
        consentService.setForPerson(person.getId(), new ContactChannelConsentRequest(
                "sms", "marketing", "revoked", "manual", null, null));

        campaignDispatchService.processSend(workspace.getId(), send.id());

        assertEquals(0, fakeDispatcher.count());
        CampaignDelivery delivery = campaignDeliveryMapper.getDelivery(workspace.getId(), deliveryId);
        assertEquals("skipped", delivery.getStatus());
        assertEquals("consent_revoked", delivery.getSkipReason());
    }

    /**
     * Unsubscribe must still block end-to-end under opt-out consent. The consent revocation the
     * unsubscribe wrote is deliberately reset to unknown afterwards, so it can no longer be what stops
     * the send: the suppression the unsubscribe also wrote is left as the only thing blocking, and the
     * next send excludes the person from its snapshot and reaches nobody.
     */
    @Test
    void unsubscribeStillBlocksASubsequentSendThroughSuppressionAlone() {
        String prefix = "unsub-block-" + unique();
        Person person = person(newCompany(), prefix + "-in", prefix + "-in@example.com");
        CampaignSendDto first = readySend(prefix);
        assertEquals(1, first.totalRecipients());
        int deliveryId = campaignDeliveryMapper.pendingDeliveryIds(workspace.getId(), first.id()).getFirst();
        CampaignDelivery delivery = campaignDeliveryMapper.getDelivery(workspace.getId(), deliveryId);
        deliveryUnsubscribeService.unsubscribe(delivery.getUnsubscribeToken());
        consentService.setForPerson(person.getId(), new ContactChannelConsentRequest(
                "email", "marketing", "unknown", "manual", null, null));

        CampaignSendDto second = readySend(prefix);
        campaignSendService.queueSend(second.campaignId(), second.id());
        campaignDispatchService.processSend(workspace.getId(), second.id());

        assertEquals(0, second.totalRecipients());
        assertTrue(campaignDeliveryMapper.pendingDeliveryIds(workspace.getId(), second.id()).isEmpty());
        assertEquals(0, fakeDispatcher.count());
    }

    @Test
    void anEmailRevisionRequiresSubjectAndHtmlWhileAnSmsRevisionRequiresOnlyText() {
        String prefix = "content-" + unique();
        CampaignDto campaign = campaignService.create(new CampaignRequest(
                "Campaign " + prefix, null, "email", null, currentUser.getId(), null, null, null, null, null));
        CampaignMessageDto emailMessage = campaignSendService.createMessage(
                campaign.id(), new CampaignMessageRequest("Email " + prefix, "email"));
        CampaignMessageDto smsMessage = campaignSendService.createMessage(
                campaign.id(), new CampaignMessageRequest("Sms " + prefix, "sms"));

        assertThrows(BadRequestException.class, () -> campaignSendService.addRevision(
                campaign.id(), emailMessage.id(),
                new CampaignMessageRevisionRequest("en", null, null, "text only")));
        assertThrows(BadRequestException.class, () -> campaignSendService.addRevision(
                campaign.id(), emailMessage.id(),
                new CampaignMessageRevisionRequest("en", "Subject", " ", null)));
        assertThrows(BadRequestException.class, () -> campaignSendService.addRevision(
                campaign.id(), smsMessage.id(),
                new CampaignMessageRevisionRequest("en", "Subject", "<p>Hi</p>", null)));

        CampaignMessageDto saved = campaignSendService.addRevision(
                campaign.id(), smsMessage.id(),
                new CampaignMessageRevisionRequest("en", null, null, "  Just text  "));

        assertEquals(1, saved.revisions().size());
        assertEquals("Just text", saved.revisions().getFirst().bodyText());
        assertNull(saved.revisions().getFirst().subject());
        assertNull(saved.revisions().getFirst().bodyHtml());
    }

    @Test
    void queuingAnSmsSendRequiresCampaignSendPermission() {
        String prefix = "sms-rbac-" + unique();
        phonePerson(newCompany(), prefix + "-in", "+819012345678");
        CampaignSendDto send = readySmsSend(prefix);
        User member = newUser();
        authenticateAs(member, workspace.getId());

        assertThrows(ForbiddenException.class,
                () -> campaignSendService.queueSend(send.campaignId(), send.id()));
    }

    @Test
    void otherTenantCannotReadOrQueueAnSmsSend() {
        String prefix = "sms-iso-" + unique();
        phonePerson(newCompany(), prefix + "-in", "+819012345678");
        CampaignSendDto send = readySmsSend(prefix);
        CampaignActorWorkspace other = newCampaignWorkspaceActor();
        authenticateAs(other.actor(), other.workspace().getId());

        assertThrows(ResourceNotFoundException.class,
                () -> campaignSendService.getSend(send.campaignId(), send.id()));
        assertThrows(ResourceNotFoundException.class,
                () -> campaignSendService.queueSend(send.campaignId(), send.id()));
    }

    private CampaignSendDto readySend(String prefix) {
        CampaignDto campaign = campaignService.create(new CampaignRequest(
                "Campaign " + prefix, null, "email", null, currentUser.getId(), null, null, null, null, null));
        campaignService.setAudience(campaign.id(), audience(prefix));
        campaignService.snapshotAudience(campaign.id());
        CampaignMessageDto message = campaignSendService.createMessage(
                campaign.id(), new CampaignMessageRequest("Message " + prefix, "email"));
        campaignSendService.addRevision(campaign.id(), message.id(), new CampaignMessageRevisionRequest(
                "en", "Hello", "<p>Hi</p><a href=\"{{unsubscribe_url}}\">unsubscribe</a>", null));
        CampaignSendDto send = campaignSendService.createSend(
                campaign.id(), new CampaignSendRequest(1, message.id(), 1, null, null));
        assertNotNull(send);
        return send;
    }

    private int deliveryIdFor(int sendId, int personId) {
        return campaignDeliveryMapper.pendingDeliveryIds(workspace.getId(), sendId).stream()
                .filter(id -> {
                    CampaignDelivery delivery = campaignDeliveryMapper.getDelivery(workspace.getId(), id);
                    return delivery.getPersonId() != null && delivery.getPersonId() == personId;
                })
                .findFirst()
                .orElseThrow();
    }

    private CampaignSendDto readySmsSend(String prefix) {
        CampaignDto campaign = campaignService.create(new CampaignRequest(
                "Campaign " + prefix, null, "sms", null, currentUser.getId(), null, null, null, null, null));
        campaignService.setAudience(campaign.id(), audience(prefix, "sms", "marketing"));
        campaignService.snapshotAudience(campaign.id());
        CampaignMessageDto message = campaignSendService.createMessage(
                campaign.id(), new CampaignMessageRequest("Message " + prefix, "sms"));
        campaignSendService.addRevision(campaign.id(), message.id(), new CampaignMessageRevisionRequest(
                "en", null, null, "Hi from {{unsubscribe_url}}"));
        CampaignSendDto send = campaignSendService.createSend(
                campaign.id(), new CampaignSendRequest(1, message.id(), 1, null, null));
        assertNotNull(send);
        assertEquals("sms", send.channel());
        return send;
    }

    private Person person(Company company, String name, String email) {
        Person person = new Person();
        person.setWorkspaceId(workspace.getId());
        person.setName(name);
        person.setEmail(email);
        person.setTitle("Marketing contact");
        person.setCompany(company);
        personMapper.insert(person);
        return person;
    }

    private Person phonePerson(Company company, String name, String phone) {
        Person person = new Person();
        person.setWorkspaceId(workspace.getId());
        person.setName(name);
        person.setPhone(phone);
        person.setTitle("Marketing contact");
        person.setCompany(company);
        personMapper.insert(person);
        return person;
    }

    private CampaignAudienceRequest audience(String namePrefix) {
        return audience(namePrefix, null, null);
    }

    private CampaignAudienceRequest audience(String namePrefix, String channel, String purpose) {
        SegmentCondition condition = new SegmentCondition();
        condition.setType("field");
        condition.setField("name");
        condition.setOp("starts_with");
        condition.setValue(namePrefix + "-in");
        SegmentDefinition definition = new SegmentDefinition();
        definition.setMatch("all");
        definition.setConditions(List.of(condition));
        return new CampaignAudienceRequest("person", definition, channel, purpose);
    }

    private ContactChannelConsentRequest grantedConsent() {
        return new ContactChannelConsentRequest("email", "marketing", "granted", "manual", null, null);
    }

    private CampaignMessageRevisionRequest emailRevision() {
        return new CampaignMessageRevisionRequest(
                "en", "Hello", "<p>Hi</p><a href=\"{{unsubscribe_url}}\">unsubscribe</a>", null);
    }

    @TestConfiguration
    static class FakeDeliveryConfig {
        @Bean
        FakeDispatcher fakeDispatcher() {
            return new FakeDispatcher();
        }
    }

    static final class FakeDispatcher implements MessageDispatcher {
        static final String ID = "fake-smtp";
        private final AtomicInteger dispatches = new AtomicInteger();
        private final List<DeliveryRequest> requests = new CopyOnWriteArrayList<>();
        private volatile DispatchStatus status = DispatchStatus.SENT;

        void reset() {
            dispatches.set(0);
            requests.clear();
            status = DispatchStatus.SENT;
        }

        int count() {
            return dispatches.get();
        }

        List<DeliveryRequest> requests() {
            return requests;
        }

        void returnAmbiguous() {
            status = DispatchStatus.AMBIGUOUS;
        }

        @Override
        public String providerId() {
            return ID;
        }

        @Override
        public Set<DeliveryChannel> channels() {
            return Set.of(DeliveryChannel.EMAIL, DeliveryChannel.SMS);
        }

        @Override
        public DeliveryCapabilities capabilities() {
            return new DeliveryCapabilities(true, false, false, true, 1);
        }

        @Override
        public DispatchReceipt dispatch(ResolvedDeliveryProvider target, DeliveryRequest request) {
            dispatches.incrementAndGet();
            requests.add(request);
            return switch (status) {
                case SENT -> DispatchReceipt.sent("fake-" + dispatches.get(), "ok");
                case REJECTED -> DispatchReceipt.rejected("rejected");
                case AMBIGUOUS -> DispatchReceipt.ambiguous("provider outcome needs reconciliation");
            };
        }
    }
}
