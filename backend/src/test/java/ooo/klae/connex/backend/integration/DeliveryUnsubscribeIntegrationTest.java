package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;

import ooo.klae.connex.backend.beans.Campaign;
import ooo.klae.connex.backend.beans.CampaignAudienceSnapshot;
import ooo.klae.connex.backend.beans.CampaignDelivery;
import ooo.klae.connex.backend.beans.CampaignMessage;
import ooo.klae.connex.backend.beans.CampaignSend;
import ooo.klae.connex.backend.beans.ContactChannelConsent;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.SuppressionEntry;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.CampaignDeliveryMapper;
import ooo.klae.connex.backend.mappers.CampaignMapper;
import ooo.klae.connex.backend.mappers.CampaignMessageMapper;
import ooo.klae.connex.backend.mappers.CampaignSendMapper;
import ooo.klae.connex.backend.mappers.ConsentMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.SuppressionMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

/**
 * Drives the public unsubscribe endpoints through the real security filter chain and the real
 * {@code WebConfig} interceptor registration with no session — the only kind of caller these routes
 * actually have. Before #994 such a request reached the handler with no resolved tenant scope, so
 * the fail-closed {@code TenantScopeInterceptor} refused the first workspace-scoped statement after
 * the exempt token lookup; MyBatis wrapped that refusal into a {@code MyBatisSystemException}, which
 * no handler maps, so the recipient got a 500 instead of being unsubscribed.
 *
 * <p>Deliberately not {@code @Transactional}: a real recipient request arrives with no transaction
 * on the thread, and the service must establish its workspace scope before opening one. A
 * test-managed transaction would put the handler in exactly the shape the fix forbids, so it would
 * mask a regression instead of catching it. Fixtures therefore commit, each into their own
 * throwaway workspace.
 */
@SpringBootTest
class DeliveryUnsubscribeIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private PersonMapper personMapper;
    @Autowired private CampaignMapper campaignMapper;
    @Autowired private CampaignMessageMapper campaignMessageMapper;
    @Autowired private CampaignSendMapper campaignSendMapper;
    @Autowired private CampaignDeliveryMapper campaignDeliveryMapper;
    @Autowired private SuppressionMapper suppressionMapper;
    @Autowired private ConsentMapper consentMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(springSecurityFilterChain)
            .build();
    }

    @Test
    void unauthenticatedRecipientPreviewsAndUnsubscribesIdempotently() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Recipient recipient = seedRecipient();

        mockMvc.perform(get("/api/delivery/unsubscribe/" + recipient.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.channel").value("email"))
            .andExpect(jsonPath("$.address").value("r***@dest.test"))
            .andExpect(jsonPath("$.unsubscribed").value(false));

        mockMvc.perform(post("/api/delivery/unsubscribe/" + recipient.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.unsubscribed").value(true));

        mockMvc.perform(post("/api/delivery/unsubscribe/" + recipient.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.unsubscribed").value(true));

        mockMvc.perform(get("/api/delivery/unsubscribe/" + recipient.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.unsubscribed").value(true));

        RequestContextHolder.resetRequestAttributes();
        int workspaceId = recipient.workspaceId();
        assertTrue(campaignDeliveryMapper.hasEvent(workspaceId, recipient.deliveryId(), "unsubscribed"),
            "the unsubscribe event was not recorded");
        List<SuppressionEntry> suppressions = suppressionMapper.getAll(workspaceId);
        assertEquals(1, suppressions.size(), "expected exactly one suppression entry");
        assertEquals("recipient@dest.test", suppressions.getFirst().getAddress());
        assertEquals("unsubscribe", suppressions.getFirst().getReason());
        List<ContactChannelConsent> consents =
            consentMapper.getForPerson(workspaceId, recipient.personId());
        assertEquals(1, consents.size(), "expected exactly one consent row");
        assertEquals("revoked", consents.getFirst().getStatus());
    }

    @Test
    void unknownTokenIsNotFoundRatherThanRefusedForAnUnresolvedTenant() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        String unknown = "0".repeat(64);

        mockMvc.perform(get("/api/delivery/unsubscribe/" + unknown))
            .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/delivery/unsubscribe/" + unknown))
            .andExpect(status().isNotFound());
    }

    private Recipient seedRecipient() {
        Workspace workspace = newWorkspace();
        Person person = newPerson(workspace);
        Campaign campaign = newCampaign(workspace);
        CampaignAudienceSnapshot snapshot = newSnapshot(workspace, campaign);
        CampaignMessage message = newMessage(workspace, campaign);
        CampaignSend send = newSend(workspace, campaign, snapshot, message);
        String token = randomToken();
        CampaignDelivery delivery = new CampaignDelivery();
        delivery.setSendId(send.getId());
        delivery.setPersonId(person.getId());
        delivery.setAddress("recipient@dest.test");
        delivery.setStatus("dispatched");
        delivery.setUnsubscribeToken(token);
        campaignDeliveryMapper.insertDeliveries(workspace.getId(), List.of(delivery));
        CampaignDelivery stored = campaignDeliveryMapper.getByToken(token);
        assertNotNull(stored, "the seeded delivery was not persisted");
        return new Recipient(workspace.getId(), stored.getId(), person.getId(), token);
    }

    private Workspace newWorkspace() {
        String slug = "unsub-" + UUID.randomUUID().toString().substring(0, 8);
        Workspace workspace = new Workspace();
        workspace.setName(slug);
        workspace.setSlug(slug);
        workspaceMapper.insert(workspace);
        return workspace;
    }

    private Person newPerson(Workspace workspace) {
        Person person = new Person();
        person.setWorkspaceId(workspace.getId());
        person.setName("Unsubscribe Recipient");
        personMapper.insert(person);
        return person;
    }

    private Campaign newCampaign(Workspace workspace) {
        Campaign campaign = new Campaign();
        campaign.setWorkspaceId(workspace.getId());
        campaign.setName("Unsubscribe campaign");
        campaign.setType("email");
        campaign.setStatus("active");
        campaignMapper.insertCampaign(campaign);
        return campaign;
    }

    private CampaignAudienceSnapshot newSnapshot(Workspace workspace, Campaign campaign) {
        CampaignAudienceSnapshot snapshot = new CampaignAudienceSnapshot();
        snapshot.setWorkspaceId(workspace.getId());
        snapshot.setCampaignId(campaign.getId());
        snapshot.setVersion(1);
        snapshot.setRecordType("person");
        snapshot.setDefinitionJson("{\"match\":\"all\",\"conditions\":[]}");
        snapshot.setEstimatedIncluded(1);
        campaignMapper.insertSnapshot(snapshot);
        return snapshot;
    }

    private CampaignMessage newMessage(Workspace workspace, Campaign campaign) {
        CampaignMessage message = new CampaignMessage();
        message.setWorkspaceId(workspace.getId());
        message.setCampaignId(campaign.getId());
        message.setChannel("email");
        message.setName("Unsubscribe message");
        message.setStatus("final");
        campaignMessageMapper.insertMessage(message);
        return message;
    }

    private CampaignSend newSend(
            Workspace workspace,
            Campaign campaign,
            CampaignAudienceSnapshot snapshot,
            CampaignMessage message) {
        CampaignSend send = new CampaignSend();
        send.setWorkspaceId(workspace.getId());
        send.setCampaignId(campaign.getId());
        send.setSnapshotId(snapshot.getId());
        send.setMessageId(message.getId());
        send.setMessageVersion(1);
        send.setChannel("email");
        send.setPurpose("marketing");
        send.setStatus("running");
        send.setTotalRecipients(1);
        campaignSendMapper.insertSend(send);
        return send;
    }

    private static String randomToken() {
        return (UUID.randomUUID().toString() + UUID.randomUUID())
            .replace("-", "")
            .substring(0, 64);
    }

    private record Recipient(int workspaceId, int deliveryId, int personId, String token) {
    }
}
