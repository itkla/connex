package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
