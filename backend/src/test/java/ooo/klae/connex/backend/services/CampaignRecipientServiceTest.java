package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Campaign;
import ooo.klae.connex.backend.beans.CampaignAudienceSnapshot;
import ooo.klae.connex.backend.beans.CampaignDelivery;
import ooo.klae.connex.backend.beans.CampaignDeliveryEvent;
import ooo.klae.connex.backend.beans.CampaignMessage;
import ooo.klae.connex.backend.beans.CampaignSend;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.dto.CampaignRecipientDto;
import ooo.klae.connex.backend.dto.PageResponse;
import ooo.klae.connex.backend.dto.PersonCampaignTouchDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.CampaignDeliveryMapper;
import ooo.klae.connex.backend.mappers.CampaignMapper;
import ooo.klae.connex.backend.mappers.CampaignMessageMapper;
import ooo.klae.connex.backend.mappers.CampaignSendMapper;
import ooo.klae.connex.backend.mappers.RoleMapper;

/** Recipient lists behind campaign engagement counters: filters, gating, and isolation. */
class CampaignRecipientServiceTest extends AbstractServiceTest {

    @Autowired private CampaignRecipientService recipientService;
    @Autowired private CampaignMapper campaignMapper;
    @Autowired private CampaignMessageMapper campaignMessageMapper;
    @Autowired private CampaignSendMapper campaignSendMapper;
    @Autowired private CampaignDeliveryMapper campaignDeliveryMapper;
    @Autowired private ContactMarketingService contactMarketingService;
    @Autowired private RoleMapper roleMapper;

    @Test
    void recipientsCarryTheContactRecordIdAndLabel() {
        Campaign campaign = newCampaign();
        CampaignSend send = newSend(campaign);
        Person person = newPerson(newCompany());
        CampaignDelivery delivery = newDelivery(send, person, "dispatched", null);

        PageResponse<CampaignRecipientDto> page =
                recipientService.getRecipients(campaign.getId(), null, null, null, 25, 0);

        assertEquals(1, page.total());
        CampaignRecipientDto row = page.items().getFirst();
        assertEquals(delivery.getId(), row.deliveryId());
        assertEquals(person.getId(), row.personId());
        assertEquals(person.getName(), row.personLabel());
        assertEquals("dispatched", row.status());
        assertEquals(send.getId(), row.sendId());
        assertEquals("email", row.channel());
    }

    @Test
    void statusFilterSelectsOneCountersPopulation() {
        Campaign campaign = newCampaign();
        CampaignSend send = newSend(campaign);
        CampaignDelivery dispatched =
                newDelivery(send, newPerson(newCompany()), "dispatched", null);
        newDelivery(send, newPerson(newCompany()), "skipped", "suppressed");

        PageResponse<CampaignRecipientDto> page = recipientService.getRecipients(
                campaign.getId(), null, List.of("dispatched"), null, 25, 0);

        assertEquals(1, page.total());
        assertEquals(dispatched.getId(), page.items().getFirst().deliveryId());
    }

    @Test
    void skippedRecipientsExposeTheirSkipReason() {
        Campaign campaign = newCampaign();
        CampaignSend send = newSend(campaign);
        newDelivery(send, newPerson(newCompany()), "skipped", "suppressed");

        PageResponse<CampaignRecipientDto> page = recipientService.getRecipients(
                campaign.getId(), null, List.of("skipped"), null, 25, 0);

        assertEquals("suppressed", page.items().getFirst().skipReason());
    }

    @Test
    void eventFilterReachesTheUnsubscribedCounterWhichIsNeverADeliveryStatus() {
        Campaign campaign = newCampaign();
        CampaignSend send = newSend(campaign);
        CampaignDelivery unsubscribed =
                newDelivery(send, newPerson(newCompany()), "dispatched", null);
        newDelivery(send, newPerson(newCompany()), "dispatched", null);
        newEvent(unsubscribed, "unsubscribed");

        PageResponse<CampaignRecipientDto> page = recipientService.getRecipients(
                campaign.getId(), null, null, "unsubscribed", 25, 0);

        assertEquals(1, page.total());
        assertEquals(unsubscribed.getId(), page.items().getFirst().deliveryId());
    }

    @Test
    void unknownEventIsRejected() {
        Campaign campaign = newCampaign();
        assertThrows(BadRequestException.class, () -> recipientService.getRecipients(
                campaign.getId(), null, null, "opened", 25, 0));
    }

    @Test
    void anotherCampaignsSendIsNotFound() {
        Campaign campaign = newCampaign();
        Campaign other = newCampaign();
        CampaignSend send = newSend(other);
        assertThrows(ResourceNotFoundException.class, () -> recipientService.getRecipients(
                campaign.getId(), send.getId(), null, null, 25, 0));
    }

    @Test
    void recipientRosterRequiresConsentManagement() {
        Campaign campaign = newCampaign();
        newSend(campaign);
        workspaceMapper.updateMemberRole(workspace.getId(), currentUser.getId(), "member");
        authenticateAs(currentUser, workspace.getId());

        assertThrows(ForbiddenException.class, () -> recipientService.getRecipients(
                campaign.getId(), null, null, null, 25, 0));
    }

    @Test
    void anotherWorkspaceCannotReadTheRecipients() {
        Campaign campaign = newCampaign();
        CampaignSend send = newSend(campaign);
        newDelivery(send, newPerson(newCompany()), "dispatched", null);

        Workspace sibling = newSiblingWorkspace();
        authenticateAs(currentUser, sibling.getId());

        assertThrows(ResourceNotFoundException.class, () -> recipientService.getRecipients(
                campaign.getId(), null, null, null, 25, 0));
    }

    @Test
    void aClearedContactLinkStillListsWithoutALabel() {
        Campaign campaign = newCampaign();
        CampaignSend send = newSend(campaign);
        newDelivery(send, null, "dispatched", null);

        PageResponse<CampaignRecipientDto> page =
                recipientService.getRecipients(campaign.getId(), null, null, null, 25, 0);

        assertEquals(1, page.total());
        assertNull(page.items().getFirst().personId());
        assertNull(page.items().getFirst().personLabel());
        assertTrue(page.items().getFirst().deliveryId() > 0);
    }

    @Test
    void contactTimelineListsTheCampaignsThatReachedTheContact() {
        Campaign campaign = newCampaign();
        CampaignSend send = newSend(campaign);
        Person person = newPerson(newCompany());
        CampaignDelivery delivery = newDelivery(send, person, "dispatched", null);

        PageResponse<PersonCampaignTouchDto> page =
                contactMarketingService.getCampaignTouches(person.getId(), 25, 0);

        assertEquals(1, page.total());
        PersonCampaignTouchDto touch = page.items().getFirst();
        assertEquals(delivery.getId(), touch.deliveryId());
        assertEquals(campaign.getId(), touch.campaignId());
        assertEquals(campaign.getName(), touch.campaignName());
        assertEquals("email", touch.channel());
        assertEquals("dispatched", touch.status());
    }

    @Test
    void contactTimelineRequiresCampaignRead() {
        Person person = newPerson(newCompany());
        int roleId = customRoleWithoutCampaignView();
        workspaceMapper.setMemberCustomRole(workspace.getId(), currentUser.getId(), roleId);
        authenticateAs(currentUser, workspace.getId());

        assertThrows(ForbiddenException.class,
                () -> contactMarketingService.getCampaignTouches(person.getId(), 25, 0));
    }

    @Test
    void contactTimelineIsEmptyInAnotherWorkspace() {
        Campaign campaign = newCampaign();
        CampaignSend send = newSend(campaign);
        Person person = newPerson(newCompany());
        newDelivery(send, person, "dispatched", null);

        Workspace sibling = newSiblingWorkspace();
        authenticateAs(currentUser, sibling.getId());

        assertThrows(ResourceNotFoundException.class,
                () -> contactMarketingService.getCampaignTouches(person.getId(), 25, 0));
    }

    private int customRoleWithoutCampaignView() {
        WorkspaceRole role = new WorkspaceRole();
        role.setWorkspaceId(workspace.getId());
        role.setName("no_campaign_" + unique());
        roleMapper.insertRole(role);
        roleMapper.insertPermissions(workspace.getId(), role.getId(), List.of("PERSON_CREATE"));
        return role.getId();
    }

    private Workspace newSiblingWorkspace() {
        Workspace sibling = new Workspace();
        sibling.setName("Sibling " + unique());
        sibling.setSlug("sibling-" + unique());
        sibling.setOrgId(workspaceMapper.getOrgId(workspace.getId()));
        workspaceMapper.insert(sibling);
        workspaceMapper.addMember(sibling.getId(), currentUser.getId(), "owner");
        return sibling;
    }

    private Campaign newCampaign() {
        Campaign campaign = new Campaign();
        campaign.setWorkspaceId(workspace.getId());
        campaign.setName("Campaign " + unique());
        campaign.setType("email");
        campaign.setStatus("draft");
        campaign.setCreatedById(currentUser.getId());
        campaignMapper.insertCampaign(campaign);
        return campaign;
    }

    private CampaignSend newSend(Campaign campaign) {
        CampaignAudienceSnapshot snapshot = new CampaignAudienceSnapshot();
        snapshot.setWorkspaceId(workspace.getId());
        snapshot.setCampaignId(campaign.getId());
        snapshot.setVersion(1);
        snapshot.setRecordType("person");
        snapshot.setDefinitionJson("{\"match\":\"all\",\"conditions\":[]}");
        snapshot.setChannel("email");
        snapshot.setPurpose("marketing");
        snapshot.setEstimatedIncluded(0);
        snapshot.setExcludedTotal(0);
        snapshot.setExcludedConsent(0);
        snapshot.setExcludedSuppressed(0);
        snapshot.setExcludedRestricted(0);
        snapshot.setCreatedById(currentUser.getId());
        campaignMapper.insertSnapshot(snapshot);

        CampaignMessage message = new CampaignMessage();
        message.setWorkspaceId(workspace.getId());
        message.setCampaignId(campaign.getId());
        message.setChannel("email");
        message.setName("Message " + unique());
        message.setStatus("draft");
        message.setCreatedById(currentUser.getId());
        campaignMessageMapper.insertMessage(message);

        CampaignSend send = new CampaignSend();
        send.setWorkspaceId(workspace.getId());
        send.setCampaignId(campaign.getId());
        send.setSnapshotId(snapshot.getId());
        send.setMessageId(message.getId());
        send.setMessageVersion(1);
        send.setChannel("email");
        send.setPurpose("marketing");
        send.setStatus("draft");
        send.setTotalRecipients(0);
        send.setCreatedById(currentUser.getId());
        campaignSendMapper.insertSend(send);
        return send;
    }

    private CampaignDelivery newDelivery(
            CampaignSend send, Person person, String status, String skipReason) {
        CampaignDelivery delivery = new CampaignDelivery();
        delivery.setSendId(send.getId());
        delivery.setPersonId(person == null ? null : person.getId());
        delivery.setAddress(person == null ? unique() + "@example.com" : person.getEmail());
        delivery.setStatus("pending");
        delivery.setUnsubscribeToken(
                UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""));
        campaignDeliveryMapper.insertDeliveries(workspace.getId(), List.of(delivery));
        CampaignDelivery stored = campaignDeliveryMapper.getByToken(delivery.getUnsubscribeToken());
        if (!"pending".equals(status)) {
            campaignDeliveryMapper.claim(workspace.getId(), stored.getId());
            if ("skipped".equals(status)) {
                campaignDeliveryMapper.markSkipped(workspace.getId(), stored.getId(), skipReason);
            } else if ("failed".equals(status)) {
                campaignDeliveryMapper.markFailed(workspace.getId(), stored.getId(), "fixture");
            } else {
                campaignDeliveryMapper.markDispatched(
                        workspace.getId(), stored.getId(), "smtp", "msg-" + unique());
                if (!"dispatched".equals(status)) {
                    campaignDeliveryMapper.applyProviderStatus(
                            workspace.getId(), stored.getId(), status, List.of("dispatched"));
                }
            }
        }
        return campaignDeliveryMapper.getDelivery(workspace.getId(), stored.getId());
    }

    private void newEvent(CampaignDelivery delivery, String eventType) {
        CampaignDeliveryEvent event = new CampaignDeliveryEvent();
        event.setWorkspaceId(workspace.getId());
        event.setDeliveryId(delivery.getId());
        event.setEventType(eventType);
        campaignDeliveryMapper.insertEvent(event);
    }
}
