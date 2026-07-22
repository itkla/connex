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

    @Autowired private CampaignService campaignService;
    @Autowired private CampaignSendService campaignSendService;
    @Autowired private CampaignEngagementService campaignEngagementService;
    @Autowired private CampaignDeliveryMapper campaignDeliveryMapper;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private RoleMapper roleMapper;

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

        assertEquals(6, engagement.totalRecipients());
        assertEquals(1, engagement.dispatched());
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
        assertEquals(6, channelTotal(engagement.channels(), "email"));

        assertEquals(1, engagement.sends().size());
        CampaignSendEngagementDto sendRollup = engagement.sends().getFirst();
        assertEquals(send.id(), sendRollup.sendId());
        assertEquals("email", sendRollup.channel());
        assertEquals(2, sendRollup.delivered());
        assertEquals(1, sendRollup.unsubscribed());
        assertTrue(sendRollup.deliveryReceiptsAvailable());
        assertEquals(0.5, sendRollup.deliveryRate(), 1.0e-9);
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
    }

    @Test
    void otherTenantCannotReadCampaignOrSendEngagement() {
        String prefix = "iso-" + unique();
        CampaignSendDto send = seedSend(prefix, 1);
        User other = newUser();
        WorkspaceMembershipDto otherWorkspace = workspaceService.createWorkspace("Tenant B", other.getId());
        authenticateAs(other, otherWorkspace.getId());

        assertThrows(ResourceNotFoundException.class,
                () -> campaignEngagementService.getCampaignEngagement(send.campaignId()));
        assertThrows(ResourceNotFoundException.class,
                () -> campaignEngagementService.getSendEngagement(send.campaignId(), send.id()));
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
        campaignDeliveryMapper.claim(workspace.getId(), id);
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
        campaignDeliveryMapper.claim(workspace.getId(), id);
        campaignDeliveryMapper.markSkipped(workspace.getId(), id, reason);
    }

    private void toFailed(int id) {
        campaignDeliveryMapper.claim(workspace.getId(), id);
        campaignDeliveryMapper.markFailed(workspace.getId(), id, "boom");
        recordEvent(id, "failed");
    }

    private void recordEvent(int deliveryId, String eventType) {
        CampaignDeliveryEvent event = new CampaignDeliveryEvent();
        event.setWorkspaceId(workspace.getId());
        event.setDeliveryId(deliveryId);
        event.setEventType(eventType);
        campaignDeliveryMapper.insertEvent(event);
    }

    private static int channelTotal(List<CampaignChannelStatDto> channels, String channel) {
        return channels.stream()
                .filter(stat -> channel.equals(stat.channel()))
                .mapToInt(CampaignChannelStatDto::deliveries)
                .sum();
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
