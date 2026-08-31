package ooo.klae.connex.backend.integration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
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

import ooo.klae.connex.backend.beans.ApprovalStepApprover;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.DealDocument;
import ooo.klae.connex.backend.beans.DocumentApproval;
import ooo.klae.connex.backend.beans.DocumentApprovalStep;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealDocumentMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.DocumentApprovalMapper;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** End-to-end reconciliation coverage for source-owned My Work actions. */
@SpringBootTest
class MyWorkReconciliationIntegrationTest {
    private static final String PASSWORD = "My-Work-Reconcile-Pw1!";

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private UserMapper userMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private TaskMapper taskMapper;
    @Autowired private CompanyMapper companyMapper;
    @Autowired private PipelineMapper pipelineMapper;
    @Autowired private DealMapper dealMapper;
    @Autowired private DealDocumentMapper documentMapper;
    @Autowired private DocumentApprovalMapper approvalMapper;
    @Autowired private NotificationMapper notificationMapper;
    @Autowired private RoleMapper roleMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ObjectMapper objectMapper;

    private MockMvc mockMvc;
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        RequestContextHolder.resetRequestAttributes();
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(springSecurityFilterChain)
            .build();
        Organization organization = new Organization();
        String value = suffix();
        organization.setName("My Work Reconciliation Org " + value);
        organization.setSlug("my-work-reconcile-org-" + value);
        organizationMapper.insert(organization);
        workspace = new Workspace();
        workspace.setName("My Work Reconciliation " + value);
        workspace.setSlug("my-work-reconcile-" + value);
        workspace.setOrgId(organization.getId());
        workspaceMapper.insert(workspace);
    }

    @Test
    void taskAndApprovalActionsResolveNotificationsAdvanceVersionsAndLeaveTheQueue()
            throws Exception {
        User actor = user("actor");
        User requester = user("requester");
        workspaceMapper.addMember(workspace.getId(), actor.getId(), "member");
        workspaceMapper.addMember(workspace.getId(), requester.getId(), "member");
        assignApproverRole(actor);
        MockHttpSession session = login(actor.getUsername());

        Task task = task(actor);
        Notification taskNotification = taskDue(actor, task);
        notificationMapper.upsert(taskNotification);
        long taskStateVersion = notificationMapper.getStateVersion(actor.getId());
        JsonNode taskItem = firstItem(session, "task");

        mockMvc.perform(post("/api/my-work/tasks/{id}/complete", task.getId())
                .header("X-Workspace-Id", workspace.getId())
                .header("If-Match", taskItem.get("etag").asText())
                .session(session)
                .with(csrf().asHeader()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.removedFromQueue").value(true));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Notification resolved = notificationMapper.findById(
                actor.getId(), taskNotification.getId());
            assertNotNull(resolved);
            assertNotNull(resolved.getResolvedAt());
            assertTrue(notificationMapper.getStateVersion(actor.getId()) > taskStateVersion);
        });
        mockMvc.perform(get("/api/my-work")
                .header("X-Workspace-Id", workspace.getId())
                .session(session)
                .param("source", "task"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(0));

        ApprovalFixture approval = approval(actor, requester);
        Notification approvalNotification = approvalRequest(actor, approval.document());
        notificationMapper.upsert(approvalNotification);
        long approvalStateVersion = notificationMapper.getStateVersion(actor.getId());
        JsonNode approvalItem = firstItem(session, "document_approval");

        mockMvc.perform(post(
                "/api/my-work/document-approvals/{id}/decision", approval.approval().getId())
                .header("X-Workspace-Id", workspace.getId())
                .header("If-Match", approvalItem.get("etag").asText())
                .session(session)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "stepId", approval.step().getId(), "decision", "approved"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.removedFromQueue").value(true));

        Notification resolvedApproval = notificationMapper.findById(
            actor.getId(), approvalNotification.getId());
        assertNotNull(resolvedApproval);
        assertNotNull(resolvedApproval.getResolvedAt());
        assertTrue(notificationMapper.getStateVersion(actor.getId()) > approvalStateVersion);
        mockMvc.perform(get("/api/my-work")
                .header("X-Workspace-Id", workspace.getId())
                .session(session)
                .param("source", "document_approval"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(0));

        mockMvc.perform(post("/api/notifications/{id}/restore", approvalNotification.getId())
                .header("X-Workspace-Id", workspace.getId())
                .session(session)
                .with(csrf().asHeader()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.resolvedAt").isNotEmpty());

        ApprovalFixture dismissedApproval = approval(actor, requester);
        Notification dismissedNotification = approvalRequest(
            actor, dismissedApproval.document());
        notificationMapper.upsert(dismissedNotification);
        mockMvc.perform(post("/api/notifications/{id}/dismiss", dismissedNotification.getId())
                .header("X-Workspace-Id", workspace.getId())
                .session(session)
                .with(csrf().asHeader()))
            .andExpect(status().isOk());
        JsonNode dismissedItem = firstItem(session, "document_approval");
        mockMvc.perform(post(
                "/api/my-work/document-approvals/{id}/decision",
                dismissedApproval.approval().getId())
                .header("X-Workspace-Id", workspace.getId())
                .header("If-Match", dismissedItem.get("etag").asText())
                .session(session)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "stepId", dismissedApproval.step().getId(), "decision", "approved"))))
            .andExpect(status().isOk());
        Notification convergedDismissed = notificationMapper.findById(
            actor.getId(), dismissedNotification.getId());
        assertNotNull(convergedDismissed.getDismissedAt());
        assertNotNull(convergedDismissed.getResolvedAt());

        mockMvc.perform(post("/api/notifications/{id}/restore", dismissedNotification.getId())
                .header("X-Workspace-Id", workspace.getId())
                .session(session)
                .with(csrf().asHeader()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dismissedAt").doesNotExist())
            .andExpect(jsonPath("$.resolvedAt").isNotEmpty());
    }

    private JsonNode firstItem(MockHttpSession session, String source) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/my-work")
                .header("X-Workspace-Id", workspace.getId())
                .session(session)
                .param("source", source))
            .andDo(org.springframework.test.web.servlet.result.MockMvcResultHandlers.print())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
            .get("items").get(0);
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

    private Task task(User actor) {
        Task task = new Task();
        task.setWorkspaceId(workspace.getId());
        task.setDescription("Reconcile task " + suffix());
        task.setStatus("todo");
        task.setAssignedTo(actor);
        taskMapper.insert(task);
        return task;
    }

    private Notification taskDue(User recipient, Task task) {
        Notification notification = notification(recipient, "task.due", "task", "warning");
        notification.setSourceType("task");
        notification.setSourceId(task.getId());
        notification.setData("{\"taskId\":" + task.getId() + "}");
        notification.setDedupeKey("task.due:" + task.getId());
        return notification;
    }

    private ApprovalFixture approval(User actor, User requester) {
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
        DealDocument document = new DealDocument();
        document.setWorkspaceId(workspace.getId());
        document.setDealId(deal.getId());
        document.setType("quote");
        document.setLocale("en");
        document.setStatus("pending_approval");
        document.setVersion(1);
        document.setTitle("Quote " + suffix());
        document.setContent("{}");
        document.setCurrency("JPY");
        document.setCreatedBy(requester.getId());
        documentMapper.insert(document);
        DocumentApproval approval = new DocumentApproval();
        approval.setWorkspaceId(workspace.getId());
        approval.setDealId(deal.getId());
        approval.setDocumentId(document.getId());
        approval.setStatus("pending");
        approval.setMode("sequential");
        approval.setSeparationOfDuties("strict");
        approval.setPolicyBinding("none");
        approval.setRequestedBy(requester.getId());
        approvalMapper.insert(approval);
        DocumentApprovalStep step = new DocumentApprovalStep();
        step.setWorkspaceId(workspace.getId());
        step.setApprovalId(approval.getId());
        step.setStepOrder(1);
        step.setName("Manager");
        step.setRequiredCount(1);
        step.setStatus("active");
        step.setOnExpiry("expire");
        approvalMapper.insertStep(step);
        ApprovalStepApprover approver = new ApprovalStepApprover();
        approver.setWorkspaceId(workspace.getId());
        approver.setStepId(step.getId());
        approver.setApproverKind("user");
        approver.setUserId(actor.getId());
        approvalMapper.insertStepApprover(approver);
        return new ApprovalFixture(approval, step, document);
    }

    private Notification approvalRequest(User recipient, DealDocument document) {
        Notification notification = notification(
            recipient, "document.approval_request", "document", "info");
        notification.setSourceType("deal_document");
        notification.setSourceId(document.getId());
        notification.setContextType("deal");
        notification.setContextId(document.getDealId());
        notification.setData("{\"documentId\":" + document.getId() + "}");
        notification.setDedupeKey("document.approval_request:" + suffix());
        return notification;
    }

    private Notification notification(
            User recipient, String type, String category, String severity) {
        Notification notification = new Notification();
        notification.setWorkspaceId(workspace.getId());
        notification.setRecipientId(recipient.getId());
        notification.setType(type);
        notification.setCategory(category);
        notification.setSeverity(severity);
        notification.setTemplateVersion(1);
        notification.setTitle(type);
        notification.setBody(type);
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

    private void assignApproverRole(User user) {
        WorkspaceRole role = new WorkspaceRole();
        role.setWorkspaceId(workspace.getId());
        role.setName("My Work Approver " + suffix());
        roleMapper.insertRole(role);
        roleMapper.insertPermissions(workspace.getId(), role.getId(),
            java.util.List.of("TASK_UPDATE", "DOCUMENT_APPROVE"));
        workspaceMapper.setMemberCustomRole(workspace.getId(), user.getId(), role.getId());
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private record ApprovalFixture(
        DocumentApproval approval,
        DocumentApprovalStep step,
        DealDocument document
    ) {
    }
}
