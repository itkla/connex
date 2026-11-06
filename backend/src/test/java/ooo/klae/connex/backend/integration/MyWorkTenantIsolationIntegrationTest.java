package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.Map;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.work.WorkItemStateHash;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Authenticated HTTP coverage for My Work tenant and recipient isolation. */
@SpringBootTest
class MyWorkTenantIsolationIntegrationTest {
    private static final String PASSWORD = "My-Work-Isolation-Pw1!";

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private UserMapper userMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private TaskMapper taskMapper;
    @Autowired private CompanyMapper companyMapper;
    @Autowired private PipelineMapper pipelineMapper;
    @Autowired private DealMapper dealMapper;
    @Autowired private NotificationMapper notificationMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ObjectMapper objectMapper;

    @Autowired private JdbcTemplate jdbc;

    private MockMvc mockMvc;
    private Workspace firstWorkspace;

    @BeforeEach
    void setUp() {
        RequestContextHolder.resetRequestAttributes();
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(springSecurityFilterChain)
            .build();
        firstWorkspace = workspace("my-work-first", newOrganization().getId());
    }

    @Test
    void projectionAndActionsCannotCrossWorkspaceOrRecipientScope() throws Exception {
        User actor = user("actor");
        User otherRecipient = user("recipient");
        workspaceMapper.addMember(firstWorkspace.getId(), actor.getId(), "member");
        workspaceMapper.addMember(firstWorkspace.getId(), otherRecipient.getId(), "member");
        Workspace secondWorkspace = workspace("my-work-second", firstWorkspace.getOrgId());
        workspaceMapper.addMember(secondWorkspace.getId(), actor.getId(), "member");
        Task firstTask = task(firstWorkspace, actor, "first workspace");
        Task secondTask = task(secondWorkspace, actor, "second workspace");
        Deal deal = deal(firstWorkspace);
        Notification otherNotification = dealClose(firstWorkspace, otherRecipient, deal);
        notificationMapper.upsert(otherNotification);
        MockHttpSession session = login(actor.getUsername());

        mockMvc.perform(get("/api/my-work")
                .header("X-Workspace-Id", firstWorkspace.getId())
                .session(session)
                .param("source", "task"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].sourceId").value(firstTask.getId()));

        String secondVersion = firstItemVersion(session, secondWorkspace, "task");
        mockMvc.perform(post("/api/my-work/tasks/{id}/complete", secondTask.getId())
                .header("X-Workspace-Id", firstWorkspace.getId())
                .header("If-Match", '"' + secondVersion + '"')
                .session(session)
                .with(csrf().asHeader()))
            .andExpect(status().isNotFound());

        String notificationVersion = WorkItemStateHash.sha256(
            otherNotification.getId(),
            otherNotification.getSeverity(),
            otherNotification.getData(),
            otherNotification.getReadAt(),
            otherNotification.getDismissedAt(),
            otherNotification.getResolvedAt(),
            otherNotification.getSnoozedUntil(),
            otherNotification.getSnoozeTimezone(),
            otherNotification.getUpdatedAt());
        mockMvc.perform(post("/api/my-work/notifications/{id}/dismiss", otherNotification.getId())
                .header("X-Workspace-Id", firstWorkspace.getId())
                .header("If-Match", '"' + notificationVersion + '"')
                .session(session)
                .with(csrf().asHeader()))
            .andExpect(status().isNotFound());
    }

    @Test
    void personalReadsStayBoundedAndCannotSelectAnotherUserOrWorkspace() throws Exception {
        User actor = user("reader");
        User other = user("other");
        workspaceMapper.addMember(firstWorkspace.getId(), actor.getId(), "member");
        workspaceMapper.addMember(firstWorkspace.getId(), other.getId(), "member");
        Workspace second = workspace("personal-second", firstWorkspace.getOrgId());
        workspaceMapper.addMember(second.getId(), actor.getId(), "member");
        for (int index = 0; index < 30; index++) task(firstWorkspace, actor, "personal task " + index);
        task(firstWorkspace, other, "other recipient task");
        task(second, actor, "other workspace task");
        jdbc.update("INSERT INTO person (workspace_id, owner_id, name) VALUES (?, ?, ?)",
            firstWorkspace.getId(), actor.getId(), "Personal contact");
        jdbc.update("INSERT INTO person (workspace_id, owner_id, name) VALUES (?, ?, ?)",
            firstWorkspace.getId(), other.getId(), "Other contact");
        jdbc.update("INSERT INTO person (workspace_id, owner_id, name) VALUES (?, ?, ?)",
            second.getId(), actor.getId(), "Foreign contact");
        jdbc.update("INSERT INTO person (workspace_id, owner_id, name) VALUES (?, ?, ?)",
            firstWorkspace.getId(), actor.getId(), "Cooling contact");
        Integer coolingId = jdbc.queryForObject("SELECT id FROM person WHERE workspace_id = ? AND name = ?",
            Integer.class, firstWorkspace.getId(), "Cooling contact");
        assertNotNull(coolingId);
        for (int index = 0; index < 3; index++) {
            jdbc.update("INSERT INTO activity (workspace_id, person_id, created_by_id, type, subject, timestamp) "
                    + "VALUES (?, ?, ?, 'meeting', 'Prior meeting', ?)",
                firstWorkspace.getId(), coolingId, other.getId(),
                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusDays(40));
        }
        for (String statement : List.of(
                "INSERT INTO person (workspace_id, owner_id, name, archived_at) VALUES (?, ?, 'Archived', CURRENT_TIMESTAMP)",
                "INSERT INTO person (workspace_id, owner_id, name, suspended_at) VALUES (?, ?, 'Suspended', CURRENT_TIMESTAMP)",
                "INSERT INTO person (workspace_id, owner_id, name, provision_ceased_at) VALUES (?, ?, 'Ceased', CURRENT_TIMESTAMP)")) {
            jdbc.update(statement, firstWorkspace.getId(), actor.getId());
        }
        Deal yen = deal(firstWorkspace);
        Deal dollars = deal(firstWorkspace);
        Deal otherDeal = deal(firstWorkspace);
        jdbc.update("UPDATE deal SET owner_id = ?, currency = 'JPY' WHERE workspace_id = ? AND id = ?",
            actor.getId(), firstWorkspace.getId(), yen.getId());
        jdbc.update("UPDATE deal SET owner_id = ?, currency = 'USD' WHERE workspace_id = ? AND id = ?",
            actor.getId(), firstWorkspace.getId(), dollars.getId());
        jdbc.update("UPDATE deal SET owner_id = ? WHERE workspace_id = ? AND id = ?",
            other.getId(), firstWorkspace.getId(), otherDeal.getId());
        MockHttpSession session = login(actor.getUsername());
        mockMvc.perform(get("/api/my-work/history").session(session)
                .header("X-Workspace-Id", firstWorkspace.getId()).param("userId", Integer.toString(other.getId())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.tasks.length()").value(25))
            .andExpect(jsonPath("$.tasks[0].description").value("personal task 29"))
            .andExpect(jsonPath("$.tasks[24].description").value("personal task 5"));
        mockMvc.perform(get("/api/my-work/relationships").session(session)
                .header("X-Workspace-Id", firstWorkspace.getId())
                .param("userId", Integer.toString(other.getId())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.counts.cold").value(1))
            .andExpect(jsonPath("$.counts.cooling").value(1))
            .andExpect(jsonPath("$.contacts.length()").value(2))
            .andExpect(jsonPath("$.contacts[?(@.name == 'Personal contact')].length()").value(1))
            .andExpect(jsonPath("$.contacts[?(@.name == 'Cooling contact')].length()").value(1));
        for (String currency : List.of("JPY", "USD")) {
            mockMvc.perform(get("/api/my-work/deal-kpis").session(session)
                    .header("X-Workspace-Id", firstWorkspace.getId())
                    .param("currency", currency).param("scope", "members").param("memberIds", Integer.toString(other.getId())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.currency").value(currency))
                .andExpect(jsonPath("$.metrics.totalCount").value(2))
                .andExpect(jsonPath("$.kpis.newPipeline").value(1000));
        }
        mockMvc.perform(get("/api/deals/kpis").session(session)
                .header("X-Workspace-Id", firstWorkspace.getId()).param("scope", "me"))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/my-work/pulse").session(session)
                .header("X-Workspace-Id", firstWorkspace.getId())
                .param("userId", Integer.toString(other.getId())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/my-work/risks").session(session)
                .header("X-Workspace-Id", firstWorkspace.getId())
                .param("userId", Integer.toString(other.getId()))
                .param("scope", "members").param("memberIds", Integer.toString(other.getId())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.inputLimitExceeded").value(false))
            .andExpect(jsonPath("$.deals.length()").value(2))
            .andExpect(jsonPath("$.deals[?(@.id == " + otherDeal.getId() + ")].length()").value(0));
        mockMvc.perform(get("/api/my-work/history").session(session)
                .header("X-Workspace-Id", second.getId()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.tasks.length()").value(1))
            .andExpect(jsonPath("$.tasks[0].description").value("other workspace task"));
        Workspace unavailable = workspace("unavailable-personal", newOrganization().getId());
        for (String endpoint : List.of("history", "relationships", "deal-kpis", "pulse", "risks")) {
            mockMvc.perform(get("/api/my-work/" + endpoint)).andExpect(status().isUnauthorized());
            mockMvc.perform(get("/api/my-work/" + endpoint).session(session)
                    .header("X-Workspace-Id", unavailable.getId()))
                .andExpect(status().isForbidden());
        }
    }

    @Test
    void reportSizedWorkspaceUsesFixedSizePersonalResponses() throws Exception {
        User actor = user("large-personal");
        workspaceMapper.addMember(firstWorkspace.getId(), actor.getId(), "member");
        jdbc.batchUpdate("INSERT INTO person (workspace_id, owner_id, name) VALUES (?, ?, ?)",
            java.util.stream.IntStream.range(0, 5000).mapToObj(index -> new Object[] {
                firstWorkspace.getId(), actor.getId(), "Personal contact " + index }).toList());
        List<Integer> contactIds = jdbc.queryForList("SELECT id FROM person WHERE workspace_id = ? ORDER BY id",
            Integer.class, firstWorkspace.getId());
        var touchedAt = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(1);
        jdbc.batchUpdate("INSERT INTO activity (workspace_id, person_id, created_by_id, type, subject, timestamp) "
                + "VALUES (?, ?, ?, 'meeting', 'Personal meeting', ?)",
            java.util.stream.IntStream.range(0, 20_000).mapToObj(index -> new Object[] {
                firstWorkspace.getId(), contactIds.get(index % contactIds.size()), actor.getId(), touchedAt }).toList());
        Deal template = deal(firstWorkspace);
        jdbc.update("UPDATE deal SET owner_id = ? WHERE workspace_id = ? AND id = ?",
            actor.getId(), firstWorkspace.getId(), template.getId());
        jdbc.batchUpdate("INSERT INTO deal (workspace_id, owner_id, name, pipeline_id, stage_id, value, currency) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)",
            java.util.stream.IntStream.range(1, 2000).mapToObj(index -> new Object[] {
                firstWorkspace.getId(), actor.getId(), "Personal deal " + index,
                template.getPipelineId(), template.getStageId(), 1000, "JPY" }).toList());
        MockHttpSession session = login(actor.getUsername());
        MvcResult relationships = mockMvc.perform(get("/api/my-work/relationships").session(session)
                .header("X-Workspace-Id", firstWorkspace.getId()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.counts.hot").value(5000))
            .andExpect(jsonPath("$.contacts.length()").value(18))
            .andExpect(jsonPath("$.temperatures.length()").value(18)).andReturn();
        assertTrue(relationships.getResponse().getContentAsByteArray().length <= 32_768);
        MvcResult kpis = mockMvc.perform(get("/api/my-work/deal-kpis").session(session)
                .header("X-Workspace-Id", firstWorkspace.getId()).param("currency", "JPY"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.metrics.totalCount").value(2000))
            .andExpect(jsonPath("$.kpis.newPipeline").value(2_000_000)).andReturn();
        assertTrue(kpis.getResponse().getContentAsByteArray().length <= 8_192);
        mockMvc.perform(get("/api/my-work/risks").session(session)
                .header("X-Workspace-Id", firstWorkspace.getId()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.inputLimitExceeded").value(true))
            .andExpect(jsonPath("$.deals.length()").value(0));
    }

    private String firstItemVersion(
            MockHttpSession session, Workspace workspace, String source) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/my-work")
                .header("X-Workspace-Id", workspace.getId())
                .session(session)
                .param("source", source))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        return response.get("items").get(0).get("currentVersion").asText();
    }

    private Organization newOrganization() {
        Organization organization = new Organization();
        String value = suffix();
        organization.setName("My Work Org " + value);
        organization.setSlug("my-work-org-" + value);
        organizationMapper.insert(organization);
        return organization;
    }

    private Workspace workspace(String prefix, int orgId) {
        Workspace workspace = new Workspace();
        workspace.setName(prefix + " " + suffix());
        workspace.setSlug(prefix + "-" + suffix());
        workspace.setOrgId(orgId);
        workspaceMapper.insert(workspace);
        return workspace;
    }

    private User user(String prefix) {
        String value = suffix();
        User user = new User();
        user.setUsername(prefix + "_" + value);
        user.setDisplayName(prefix + " " + value);
        user.setEmail(value + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setTimezone("UTC");
        userMapper.insert(user);
        return user;
    }

    private Task task(Workspace workspace, User actor, String description) {
        Task task = new Task();
        task.setWorkspaceId(workspace.getId());
        task.setDescription(description);
        task.setStatus("todo");
        task.setAssignedTo(actor);
        taskMapper.insert(task);
        return task;
    }

    private Deal deal(Workspace workspace) {
        Company company = new Company();
        company.setWorkspaceId(workspace.getId());
        company.setName("Company " + suffix());
        companyMapper.insert(company);
        Pipeline pipeline = new Pipeline();
        pipeline.setWorkspaceId(workspace.getId());
        pipeline.setName("Pipeline " + suffix());
        pipelineMapper.insertPipeline(pipeline);
        Stage stage = new Stage();
        stage.setWorkspaceId(workspace.getId());
        stage.setPipeline(pipeline);
        stage.setName("Stage " + suffix());
        stage.setPosition(0);
        pipelineMapper.insertStage(stage);
        Deal deal = new Deal();
        deal.setWorkspaceId(workspace.getId());
        deal.setName("Deal " + suffix());
        deal.setValue(new BigDecimal("1000.00"));
        deal.setCurrency("JPY");
        deal.setCompanyId(company.getId());
        deal.setPipelineId(pipeline.getId());
        deal.setStageId(stage.getId());
        dealMapper.insert(deal);
        return deal;
    }

    private Notification dealClose(Workspace workspace, User recipient, Deal deal) {
        Notification notification = new Notification();
        notification.setWorkspaceId(workspace.getId());
        notification.setRecipientId(recipient.getId());
        notification.setType("deal.close");
        notification.setCategory("deal");
        notification.setSeverity("warning");
        notification.setTemplateVersion(1);
        notification.setTitle("Deal closing");
        notification.setBody("Deal body");
        notification.setSourceType("deal");
        notification.setSourceId(deal.getId());
        notification.setContextType("deal");
        notification.setContextId(deal.getId());
        notification.setData("{\"dealId\":" + deal.getId()
            + ",\"expectedCloseDate\":\"2026-09-01\"}");
        notification.setDedupeKey("deal.close:" + suffix());
        notification.setTriggeredAt("2026-08-29 00:00:00");
        return notification;
    }

    private MockHttpSession login(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "username", username, "password", PASSWORD))))
            .andExpect(status().isOk())
            .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(session);
        return session;
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
