package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
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
import ooo.klae.connex.backend.config.OneTimeLinkFlowCookie;
import ooo.klae.connex.backend.mappers.CampaignDeliveryMapper;
import ooo.klae.connex.backend.mappers.CampaignMapper;
import ooo.klae.connex.backend.mappers.CampaignMessageMapper;
import ooo.klae.connex.backend.mappers.CampaignSendMapper;
import ooo.klae.connex.backend.mappers.ConsentMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.SuppressionMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.OneTimeLinkFlowService;
import ooo.klae.connex.backend.util.OneTimeTokenDigest;

/**
 * Drives the public unsubscribe endpoints through the real security filter chain and the real
 * {@code WebConfig} interceptor registration with no Connex session — the only kind of caller these
 * routes actually have. The raw token travels once, in the exchange body, and every later request
 * carries only the purpose-bound grant cookie. Before #994 such a request reached the handler with
 * no resolved tenant scope, so the fail-closed {@code TenantScopeInterceptor} refused the first
 * workspace-scoped statement after the exempt token lookup; MyBatis wrapped that refusal into a
 * {@code MyBatisSystemException}, which no handler maps, so the recipient got a 500 instead of being
 * unsubscribed.
 *
 * <p>It stays {@code @Transactional} so its fixtures roll back: the suite shares one schema, these
 * workspaces land in the default organization, and committed ones change what organization-wide
 * assertions elsewhere observe. That does mean a transaction is already open when the handler runs,
 * which is harmless under {@code single-database} but is not the shape production uses — the
 * "scope before transaction" ordering is asserted separately by {@code DeliveryUnsubscribeServiceTest}.
 */
@SpringBootTest
@Transactional
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
    @Autowired private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(springSecurityFilterChain)
            .build();
    }

    @Test
    void exchangeStoresOnlyTheHashAndRejectsTheRawTokenInPath() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Recipient recipient = seedRecipient();
        Browser browser = bootstrapBrowser();

        MvcResult exchanged = exchange(recipient.token(), 303, browser);
        assertEquals("/unsubscribe", exchanged.getResponse().getHeader(HttpHeaders.LOCATION));
        assertResponseSecretFree(exchanged, recipient.token());
        Cookie flowCookie = flowCookie(exchanged);
        assertTrue(exchanged.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
            .anyMatch(value -> value.startsWith(OneTimeLinkFlowCookie.DELIVERY_UNSUBSCRIBE + "=")
                && value.contains("Max-Age=600")));

        assertEquals(OneTimeTokenDigest.sha256(recipient.token()), jdbcTemplate.queryForObject(
            "SELECT unsubscribe_token_hash FROM campaign_delivery WHERE id = ?",
            String.class,
            recipient.deliveryId()));
        assertEquals(OneTimeTokenDigest.sha256(recipient.token()), jdbcTemplate.queryForObject(
            "SELECT source_token_hash FROM one_time_link_flow WHERE grant_hash = ? AND purpose = ?",
            String.class,
            OneTimeTokenDigest.sha256(flowCookie.getValue()),
            "DELIVERY_UNSUBSCRIBE"));

        MvcResult legacyPreview = mockMvc.perform(
                get("/api/delivery/unsubscribe/" + recipient.token()))
            .andExpect(status().isNotFound())
            .andReturn();
        assertResponseSecretFree(legacyPreview, recipient.token());
        mockMvc.perform(post("/api/delivery/unsubscribe/" + recipient.token()))
            .andExpect(status().isForbidden());
        MvcResult legacyUnsubscribe = mockMvc.perform(
                post("/api/delivery/unsubscribe/" + recipient.token())
                    .with(csrf().asHeader()))
            .andExpect(status().isNotFound())
            .andReturn();
        assertResponseSecretFree(legacyUnsubscribe, recipient.token());
        mockMvc.perform(get("/api/delivery/unsubscribe").queryParam("token", recipient.token()))
            .andExpect(status().isBadRequest());

        RequestContextHolder.resetRequestAttributes();
        assertFalse(campaignDeliveryMapper.hasEvent(
            recipient.workspaceId(), recipient.deliveryId(), "unsubscribed"));
    }

    @Test
    void previewAndUnsubscribeIdempotentThroughTheGrant() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Recipient recipient = seedRecipient();
        Browser browser = bootstrapBrowser();
        Cookie flowCookie = flowCookie(exchange(recipient.token(), 303, browser));

        MvcResult preview = mockMvc.perform(get("/api/delivery/unsubscribe")
                .session(browser.session())
                .cookie(flowCookie, browser.bindingCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.channel").value("email"))
            .andExpect(jsonPath("$.address").value("r***@dest.test"))
            .andExpect(jsonPath("$.unsubscribed").value(false))
            .andReturn();
        assertResponseSecretFree(preview, recipient.token());
        String flowId = flowIdOf(preview);
        assertNotEquals(flowCookie.getValue(), flowId);

        for (int attempt = 0; attempt < 2; attempt++) {
            MvcResult unsubscribed = mockMvc.perform(post("/api/delivery/unsubscribe")
                    .session(browser.session())
                    .cookie(flowCookie, browser.bindingCookie())
                    .with(csrf().asHeader())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(confirmBody(flowId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flowId").value(flowId))
                .andExpect(jsonPath("$.unsubscribed").value(true))
                .andReturn();
            assertResponseSecretFree(unsubscribed, recipient.token());
        }

        mockMvc.perform(get("/api/delivery/unsubscribe")
                .session(browser.session())
                .cookie(flowCookie, browser.bindingCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.unsubscribed").value(true));

        RequestContextHolder.resetRequestAttributes();
        int workspaceId = recipient.workspaceId();
        assertTrue(campaignDeliveryMapper.hasEvent(workspaceId, recipient.deliveryId(), "unsubscribed"),
            "the unsubscribe event was not recorded");
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM campaign_delivery_event WHERE workspace_id = ? "
                + "AND delivery_id = ? AND event_type = 'unsubscribed'",
            Integer.class,
            workspaceId,
            recipient.deliveryId()));
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
    void unknownAndMalformedTokensAre404AndIssueNoGrant() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        String unknown = "0".repeat(64);
        Browser browser = bootstrapBrowser();

        MvcResult unknownExchange = exchange(unknown, 404, browser);
        assertResponseSecretFree(unknownExchange, unknown);
        assertEquals("Unsubscribe link is not valid", jsonMessage(unknownExchange));
        MvcResult malformedExchange = exchange("not-a-token", 404, browser);
        assertEquals("Unsubscribe link is not valid", jsonMessage(malformedExchange));

        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM one_time_link_flow WHERE purpose = ? AND source_token_hash = ?",
            Integer.class,
            "DELIVERY_UNSUBSCRIBE",
            OneTimeTokenDigest.sha256(unknown)));
    }

    @Test
    void grantlessAndUnboundRequestsAre400WithoutAnyState() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Recipient recipient = seedRecipient();

        MvcResult unbound = mockMvc.perform(post("/api/delivery/unsubscribe/exchange")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + recipient.token() + "\"}"))
            .andExpect(status().isBadRequest())
            .andReturn();
        assertResponseSecretFree(unbound, recipient.token());

        mockMvc.perform(get("/api/delivery/unsubscribe")
                .session(new MockHttpSession(context.getServletContext())))
            .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/delivery/unsubscribe")
                .session(new MockHttpSession(context.getServletContext()))
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(confirmBody("f".repeat(64))))
            .andExpect(status().isBadRequest());

        Browser browser = bootstrapBrowser();
        Cookie flowCookie = flowCookie(exchange(recipient.token(), 303, browser));
        String flowId = OneTimeTokenDigest.sha256(flowCookie.getValue());
        mockMvc.perform(post("/api/delivery/unsubscribe")
                .session(new MockHttpSession(context.getServletContext()))
                .cookie(flowCookie, browser.bindingCookie())
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(confirmBody(flowId)))
            .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/delivery/unsubscribe")
                .session(browser.session())
                .cookie(flowCookie, browser.bindingCookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(confirmBody(flowId)))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/delivery/unsubscribe")
                .session(browser.session())
                .cookie(flowCookie, browser.bindingCookie())
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest());

        RequestContextHolder.resetRequestAttributes();
        assertFalse(campaignDeliveryMapper.hasEvent(
            recipient.workspaceId(), recipient.deliveryId(), "unsubscribed"));
    }

    /**
     * The purpose cookie is shared by every tab of one browser, so a later exchange replaces the
     * grant an earlier tab rendered. Confirming with the earlier tab's flow identity must refuse
     * rather than suppress the later address.
     */
    @Test
    void staleUnsubscribeTabCannotSuppressTheLaterTabsAddress() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Recipient recipient = seedRecipient();
        Recipient laterRecipient = seedRecipient();
        Browser browser = bootstrapBrowser();
        Cookie flowCookie = flowCookie(exchange(recipient.token(), 303, browser));
        String flowId = flowIdOf(mockMvc.perform(get("/api/delivery/unsubscribe")
                .session(browser.session())
                .cookie(flowCookie, browser.bindingCookie()))
            .andExpect(status().isOk())
            .andReturn());
        Cookie laterFlowCookie = flowCookie(exchange(laterRecipient.token(), 303, browser));
        String laterFlowId = flowIdOf(mockMvc.perform(get("/api/delivery/unsubscribe")
                .session(browser.session())
                .cookie(laterFlowCookie, browser.bindingCookie()))
            .andExpect(status().isOk())
            .andReturn());
        assertNotEquals(flowId, laterFlowId);

        MvcResult stale = mockMvc.perform(post("/api/delivery/unsubscribe")
                .session(browser.session())
                .cookie(laterFlowCookie, browser.bindingCookie())
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(confirmBody(flowId)))
            .andExpect(status().isBadRequest())
            .andReturn();
        assertEquals("This link is invalid or has expired", jsonMessage(stale));

        RequestContextHolder.resetRequestAttributes();
        assertFalse(campaignDeliveryMapper.hasEvent(
            recipient.workspaceId(), recipient.deliveryId(), "unsubscribed"));
        assertFalse(campaignDeliveryMapper.hasEvent(
            laterRecipient.workspaceId(), laterRecipient.deliveryId(), "unsubscribed"));

        mockMvc.perform(post("/api/delivery/unsubscribe")
                .session(browser.session())
                .cookie(laterFlowCookie, browser.bindingCookie())
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(confirmBody(laterFlowId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.unsubscribed").value(true));
        RequestContextHolder.resetRequestAttributes();
        assertFalse(campaignDeliveryMapper.hasEvent(
            recipient.workspaceId(), recipient.deliveryId(), "unsubscribed"));
        assertTrue(campaignDeliveryMapper.hasEvent(
            laterRecipient.workspaceId(), laterRecipient.deliveryId(), "unsubscribed"));
    }

    private static String flowIdOf(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.flowId");
    }

    private static String confirmBody(String flowId) {
        return "{\"flowId\":\"" + flowId + "\"}";
    }

    private MvcResult exchange(String rawToken, int expectedStatus, Browser browser)
            throws Exception {
        return mockMvc.perform(post("/api/delivery/unsubscribe/exchange")
                .session(browser.session())
                .cookie(browser.bindingCookie())
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + rawToken + "\"}"))
            .andExpect(status().is(expectedStatus))
            .andReturn();
    }

    private Browser bootstrapBrowser() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/csrf"))
            .andExpect(status().isOk())
            .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(session);
        return new Browser(
            session,
            responseCookie(result, OneTimeLinkFlowService.BROWSER_BINDING_COOKIE, "Path=/api"));
    }

    private static Cookie flowCookie(MvcResult result) {
        return responseCookie(
            result, OneTimeLinkFlowCookie.DELIVERY_UNSUBSCRIBE, "Path=/api/delivery/unsubscribe");
    }

    private static Cookie responseCookie(MvcResult result, String name, String expectedPath) {
        String header = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
            .filter(value -> value.startsWith(name + "="))
            .findFirst()
            .orElseThrow();
        assertTrue(header.contains("HttpOnly"));
        assertTrue(header.contains("SameSite=Strict"));
        assertTrue(header.contains(expectedPath));
        assertFalse(header.contains("token="));
        String value = header.substring(name.length() + 1, header.indexOf(';'));
        return new Cookie(name, value);
    }

    private static String jsonMessage(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        int start = body.indexOf("\"message\":\"") + "\"message\":\"".length();
        return body.substring(start, body.indexOf('"', start));
    }

    private static void assertResponseSecretFree(MvcResult result, String rawToken) throws Exception {
        assertFalse(result.getResponse().getContentAsString().contains(rawToken));
        for (String value : result.getResponse().getHeaderNames().stream()
                .flatMap(name -> result.getResponse().getHeaders(name).stream())
                .toList()) {
            assertFalse(value.contains(rawToken));
        }
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
        CampaignDelivery stored = campaignDeliveryMapper.getByTokenHash(
            OneTimeTokenDigest.sha256(token));
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
        snapshot.setChannel("email");
        snapshot.setPurpose("marketing");
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

    private record Browser(MockHttpSession session, Cookie bindingCookie) {
    }
}
