package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.CampaignDeliveryEvent;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.dto.CampaignAudienceRequest;
import ooo.klae.connex.backend.dto.CampaignChannelStatDto;
import ooo.klae.connex.backend.dto.CampaignDto;
import ooo.klae.connex.backend.dto.CampaignEngagementDto;
import ooo.klae.connex.backend.dto.CampaignMessageDto;
import ooo.klae.connex.backend.dto.CampaignMessageRequest;
import ooo.klae.connex.backend.dto.CampaignMessageRevisionRequest;
import ooo.klae.connex.backend.dto.CampaignRequest;
import ooo.klae.connex.backend.dto.CampaignSendDto;
import ooo.klae.connex.backend.dto.CampaignSendEngagementDto;
import ooo.klae.connex.backend.dto.CampaignSendRequest;
import ooo.klae.connex.backend.dto.SegmentCondition;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.dto.WorkspaceMembershipDto;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.CampaignDeliveryMapper;
import ooo.klae.connex.backend.mappers.RoleMapper;

class CampaignEngagementServiceTest extends AbstractServiceTest {
    private static final long AUDIENCE_LEASE_MICROS = 48_000_000L;

    @Autowired private CampaignService campaignService;
    @Autowired private CampaignSendService campaignSendService;
    @Autowired private CampaignEngagementService campaignEngagementService;
    @Autowired private CampaignRecipientService campaignRecipientService;
    @Autowired private CampaignDeliveryMapper campaignDeliveryMapper;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private RoleMapper roleMapper;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void deprecatedResponseAliasesRetainNumericConfirmedDeliveryCounts() {
        CampaignSendDto send = seedSend("aliases-" + unique(), 6);
        List<Integer> ids = campaignDeliveryMapper.pendingDeliveryIds(workspace.getId(), send.id());

        assertLegacyAliases(send, 0);
        toDispatched(ids.getFirst());
        assertLegacyAliases(send, 0);
        toDelivered(ids.get(1));
        assertLegacyAliases(send, 1);
    }

    private void assertLegacyAliases(CampaignSendDto send, int confirmedDelivered) {
        var campaignJson = objectMapper.readTree(objectMapper.writeValueAsString(
                campaignEngagementService.getCampaignEngagement(send.campaignId())));
        var sendJson = objectMapper.readTree(objectMapper.writeValueAsString(
                campaignEngagementService.getSendEngagement(send.campaignId(), send.id())));
        assertEquals(6, campaignJson.path("preparedRecipients").asInt());
        assertEquals(6, sendJson.path("preparedRecipients").asInt());
        assertTrue(campaignJson.path("totalRecipients").isIntegralNumber());
        assertEquals(confirmedDelivered, campaignJson.path("totalRecipients").asInt());
        assertTrue(sendJson.path("totalRecipients").isIntegralNumber());
        assertEquals(confirmedDelivered, sendJson.path("totalRecipients").asInt());
        assertEquals(confirmedDelivered, campaignJson.path("sends").path(0).path("totalRecipients").asInt());
        var channel = campaignJson.path("channels").path(0);
        assertTrue(channel.path("deliveries").isIntegralNumber());
        assertEquals(confirmedDelivered, channel.path("deliveries").asInt());
    }

    @Test
    void repeatedContactRowsIdentifyTheirOwnSendWithinTheCampaign() {
        String prefix = "send-labels-" + unique();
        CampaignSendDto first = seedSend(prefix, 1);
        CampaignSendDto second = campaignSendService.createSend(first.campaignId(),
                new CampaignSendRequest(1, first.messageId(), 1, null, null));
        toDispatched(campaignDeliveryMapper.pendingDeliveryIds(workspace.getId(), first.id()).getFirst());
        toDispatched(campaignDeliveryMapper.pendingDeliveryIds(workspace.getId(), second.id()).getFirst());

        var page = campaignRecipientService.getRecipients(first.campaignId(), null, null, null, 25, 0, true);

        assertEquals(2, page.total());
        assertEquals(page.items().getFirst().personId(), page.items().getLast().personId());
        assertEquals(List.of(1, 2), page.items().stream().map(row -> row.sendNumber()).toList());
        assertEquals(first.id(), page.items().getFirst().sendId());
        assertEquals(second.id(), page.items().getLast().sendId());
        assertEquals("Message " + prefix, page.items().getFirst().messageName());
        assertEquals(page.items().getFirst().messageName(), page.items().getLast().messageName());
    }

    @Test
    void draftRecipientsAreNeverReportedAsSentOrDelivered() {
        CampaignSendDto send = seedSend("draft-" + unique(), 6);

        CampaignEngagementDto engagement = campaignEngagementService.getCampaignEngagement(send.campaignId());

        assertEquals(6, engagement.preparedRecipients());
        assertEquals(0, engagement.dispatched());
        assertEquals(0, engagement.delivered());
        assertFalse(engagement.deliveryReceiptsAvailable());
        assertNull(engagement.deliveryRate());
        assertEquals(new CampaignChannelStatDto("email", 6, 0, 0, false), engagement.channels().getFirst());
        assertEquals(6, campaignEngagementService.getSendEngagement(send.campaignId(), send.id()).preparedRecipients());
        assertEquals(0, campaignRecipientService.getRecipients(
                send.campaignId(), null, null, null, 25, 0, true).total());
        int pending = campaignDeliveryMapper.pendingDeliveryIds(workspace.getId(), send.id()).getFirst();
        recordEvent(pending, "queued");
        campaignDeliveryMapper.claim(workspace.getId(), pending, AUDIENCE_LEASE_MICROS);
        assertEquals(0, campaignEngagementService.getCampaignEngagement(send.campaignId()).dispatched());
    }

    @Test
    void campaignAndSendRollupsMatchTheSeededDeliveriesWithProviderReceipts() {
        String prefix = "eng-" + unique();
        CampaignSendDto send = seedSend(prefix, 6);
        List<Integer> ids = campaignDeliveryMapper.pendingDeliveryIds(workspace.getId(), send.id());
        assertEquals(6, ids.size());
        toDelivered(ids.get(0));
        toDelivered(ids.get(1));
        toBounced(ids.get(2));
        toDispatched(ids.get(3));
        toSkipped(ids.get(4), "suppressed");
        toFailed(ids.get(5));
        recordEvent(ids.get(0), "unsubscribed");

        CampaignEngagementDto engagement = campaignEngagementService.getCampaignEngagement(send.campaignId());

        assertEquals(6, engagement.preparedRecipients());
        assertEquals(4, engagement.dispatched());
        assertEquals(2, engagement.delivered());
        assertEquals(1, engagement.bounced());
        assertEquals(0, engagement.complained());
        assertEquals(1, engagement.unsubscribed());
        assertEquals(1, engagement.failed());
        assertEquals(1, engagement.skipped());
        assertEquals(1, engagement.skipReasons().get("suppressed"));
        assertTrue(engagement.deliveryReceiptsAvailable());
        assertEquals(0.5, engagement.deliveryRate(), 1.0e-9);
        assertEquals(0.25, engagement.bounceRate(), 1.0e-9);
        assertEquals(new CampaignChannelStatDto("email", 6, 4, 2, true), engagement.channels().getFirst());
        assertEquals(4, engagement.eventCounts().get("dispatched"));
        assertEquals(2, engagement.eventCounts().get("delivered"));

        assertEquals(1, engagement.sends().size());
        CampaignSendEngagementDto sendRollup = engagement.sends().getFirst();
        assertEquals(send.id(), sendRollup.sendId());
        assertEquals("email", sendRollup.channel());
        assertEquals(4, sendRollup.dispatched());
        assertEquals(2, sendRollup.delivered());
        assertEquals(1, sendRollup.unsubscribed());
        assertTrue(sendRollup.deliveryReceiptsAvailable());
        assertEquals(0.5, sendRollup.deliveryRate(), 1.0e-9);
    }

    @Test
    void confirmedAcceptanceAndRecoveredReceiptsCountAsSentWithoutDispatchEvents() {
        CampaignSendDto send = seedSend("confirmed-" + unique(), 4);
        List<Integer> ids = campaignDeliveryMapper.pendingDeliveryIds(workspace.getId(), send.id());
        toDelivered(ids.getFirst());
        campaignDeliveryMapper.claim(workspace.getId(), ids.get(1), AUDIENCE_LEASE_MICROS);
        campaignDeliveryMapper.markDispatched(workspace.getId(), ids.get(1), "prov", "confirmed");
        campaignDeliveryMapper.claim(workspace.getId(), ids.get(2), AUDIENCE_LEASE_MICROS);
        campaignDeliveryMapper.markAmbiguous(workspace.getId(), ids.get(2), "ambiguous", "deadline_ambiguous");
        assertEquals(1, campaignDeliveryMapper.applyProviderStatus(
                workspace.getId(), ids.get(2), "delivered", List.of("failed")));
        recordEvent(ids.get(2), "delivered");
        toDispatched(ids.get(3));
        assertEquals(1, campaignDeliveryMapper.applyProviderStatus(
                workspace.getId(), ids.get(3), "failed", List.of("dispatched")));
        recordEvent(ids.get(3), "failed");

        CampaignEngagementDto engagement = campaignEngagementService.getCampaignEngagement(send.campaignId());

        assertEquals(2, engagement.eventCounts().get("dispatched"));
        assertEquals(4, engagement.dispatched());
        assertEquals(2, engagement.delivered());
        assertEquals(0.5, engagement.deliveryRate(), 1.0e-9);
        assertEquals(new CampaignChannelStatDto("email", 4, 4, 2, true), engagement.channels().getFirst());
        assertEquals(4, campaignRecipientService.getRecipients(
                send.campaignId(), null, null, null, 25, 0, true).total());
    }

    @Test
    void smtpPathWithoutReceiptsDoesNotPresentAMeasuredDeliveryRate() {
        String prefix = "smtp-" + unique();
        CampaignSendDto send = seedSend(prefix, 3);
        for (int id : campaignDeliveryMapper.pendingDeliveryIds(workspace.getId(), send.id())) {
            toDispatched(id);
        }

        CampaignEngagementDto engagement = campaignEngagementService.getCampaignEngagement(send.campaignId());

        assertEquals(3, engagement.dispatched());
        assertEquals(0, engagement.delivered());
        assertEquals(0, engagement.bounced());
        assertEquals(0, engagement.complained());
        assertFalse(engagement.deliveryReceiptsAvailable());
        assertNull(engagement.deliveryRate());
        assertNull(engagement.bounceRate());
        assertNull(engagement.complaintRate());
        assertEquals(3, engagement.eventCounts().get("dispatched"));
        assertFalse(engagement.eventCounts().containsKey("delivered"));
        assertEquals(new CampaignChannelStatDto("email", 3, 3, 0, false), engagement.channels().getFirst());
    }

    @Test
    void repeatedDraftRecipientsRemainPreparationAndDoNotInflateDispatchOrDelivery() {
        CampaignSendDto first = seedSend("repeat-" + unique(), 2);
        List<Integer> ids = campaignDeliveryMapper.pendingDeliveryIds(workspace.getId(), first.id());
        toDelivered(ids.getFirst());
        CampaignSendDto second = campaignSendService.createSend(first.campaignId(),
                new CampaignSendRequest(1, first.messageId(), 1, null, null));
        CampaignSendDto unrelated = seedSend("unrelated-" + unique(), 1);
        toDelivered(campaignDeliveryMapper.pendingDeliveryIds(workspace.getId(), unrelated.id()).getFirst());

        CampaignEngagementDto engagement = campaignEngagementService.getCampaignEngagement(first.campaignId());

        assertEquals(4, engagement.preparedRecipients());
        assertEquals(1, engagement.dispatched());
        assertEquals(1, engagement.delivered());
        assertEquals(new CampaignChannelStatDto("email", 4, 1, 1, true), engagement.channels().getFirst());
        CampaignSendEngagementDto draft = campaignEngagementService.getSendEngagement(first.campaignId(), second.id());
        assertEquals(2, draft.preparedRecipients());
        assertEquals(0, draft.dispatched());
        assertEquals(0, draft.delivered());
        assertFalse(draft.deliveryReceiptsAvailable());
    }

    @Test
    void otherTenantCannotReadCampaignOrSendEngagement() {
        String prefix = "iso-" + unique();
        CampaignSendDto send = seedSend(prefix, 1);
        toDelivered(campaignDeliveryMapper.pendingDeliveryIds(workspace.getId(), send.id()).getFirst());
        User other = newUser();
        WorkspaceMembershipDto otherWorkspace = workspaceService.createWorkspace("Tenant B", other.getId());
        authenticateAs(other, otherWorkspace.getId());

        assertThrows(ResourceNotFoundException.class,
                () -> campaignEngagementService.getCampaignEngagement(send.campaignId()));
        assertThrows(ResourceNotFoundException.class,
                () -> campaignEngagementService.getSendEngagement(send.campaignId(), send.id()));
        assertEquals(0, campaignDeliveryMapper.countRecipients(
                otherWorkspace.getId(), send.campaignId(), null, null, null, true));
        assertTrue(campaignDeliveryMapper.listRecipients(
                otherWorkspace.getId(), send.campaignId(), null, null, null, 25, 0, true).isEmpty());
    }

    @Test
    void readingEngagementRequiresCampaignViewPermission() {
        String prefix = "rbac-" + unique();
        CampaignSendDto send = seedSend(prefix, 1);
        User member = newUser();
        WorkspaceRole role = new WorkspaceRole();
        role.setWorkspaceId(workspace.getId());
        role.setName("No View " + UUID.randomUUID().toString().substring(0, 8));
        roleMapper.insertRole(role);
        roleMapper.insertPermissions(workspace.getId(), role.getId(), List.of("REPORT_READ"));
        workspaceMapper.setMemberCustomRole(workspace.getId(), member.getId(), role.getId());
        authenticateAs(member, workspace.getId());

        assertThrows(ForbiddenException.class,
                () -> campaignEngagementService.getCampaignEngagement(send.campaignId()));
        assertThrows(ForbiddenException.class,
                () -> campaignEngagementService.getSendEngagement(send.campaignId(), send.id()));
    }

    private CampaignSendDto seedSend(String prefix, int recipients) {
        Company company = newCompany();
        for (int i = 0; i < recipients; i++) {
            person(company, prefix + "-in-" + i, prefix + "-" + i + "@example.com");
        }
        CampaignDto campaign = campaignService.create(new CampaignRequest(
                "Campaign " + prefix, null, "email", null, currentUser.getId(), null, null, null, null, null));
        campaignService.setAudience(campaign.id(), audience(prefix));
        campaignService.snapshotAudience(campaign.id());
        CampaignMessageDto message = campaignSendService.createMessage(
                campaign.id(), new CampaignMessageRequest("Message " + prefix, "email"));
        campaignSendService.addRevision(campaign.id(), message.id(),
                new CampaignMessageRevisionRequest("en", "Hello", "<p>Hi</p>", null));
        return campaignSendService.createSend(
                campaign.id(), new CampaignSendRequest(1, message.id(), 1, null, null));
    }

    private void toDispatched(int id) {
        campaignDeliveryMapper.claim(workspace.getId(), id, AUDIENCE_LEASE_MICROS);
        campaignDeliveryMapper.markDispatched(workspace.getId(), id, "prov", "msg-" + id);
        recordEvent(id, "dispatched");
    }

    private void toDelivered(int id) {
        toDispatched(id);
        campaignDeliveryMapper.applyProviderStatus(workspace.getId(), id, "delivered", List.of("dispatched"));
        recordEvent(id, "delivered");
    }

    private void toBounced(int id) {
        toDispatched(id);
        campaignDeliveryMapper.applyProviderStatus(workspace.getId(), id, "bounced", List.of("dispatched"));
        recordEvent(id, "bounced");
    }

    private void toSkipped(int id, String reason) {
        campaignDeliveryMapper.claim(workspace.getId(), id, AUDIENCE_LEASE_MICROS);
        campaignDeliveryMapper.markSkipped(workspace.getId(), id, reason);
    }

    private void toFailed(int id) {
        campaignDeliveryMapper.claim(workspace.getId(), id, AUDIENCE_LEASE_MICROS);
        campaignDeliveryMapper.markFailed(workspace.getId(), id, "boom", "relay_error");
        recordEvent(id, "failed");
    }

    private void recordEvent(int deliveryId, String eventType) {
        CampaignDeliveryEvent event = new CampaignDeliveryEvent();
        event.setWorkspaceId(workspace.getId());
        event.setDeliveryId(deliveryId);
        event.setEventType(eventType);
        campaignDeliveryMapper.insertEvent(event);
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

    private CampaignAudienceRequest audience(String namePrefix) {
        SegmentCondition condition = new SegmentCondition();
        condition.setType("field");
        condition.setField("name");
        condition.setOp("starts_with");
        condition.setValue(namePrefix + "-in");
        SegmentDefinition definition = new SegmentDefinition();
        definition.setMatch("all");
        definition.setConditions(List.of(condition));
        return new CampaignAudienceRequest("person", definition);
    }
}
