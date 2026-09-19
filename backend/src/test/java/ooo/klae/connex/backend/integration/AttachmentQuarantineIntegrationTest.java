package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;

import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.dto.CsrfBootstrapDto;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
import ooo.klae.connex.backend.mappers.AttachmentScanMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.tenant.TenantContext;
import tools.jackson.databind.ObjectMapper;

/** Exercises every quarantine endpoint through authentication, permission, tenant and SQL boundaries. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"server.address=127.0.0.1", "server.servlet.session.cookie.secure=false"})
@Transactional
@UnenrolledPrivilegedFixture
class AttachmentQuarantineIntegrationTest {
    private static final String PASSWORD = "Quarantine-Fixture-Pw1!";
    private static final List<String> ACTIONS = List.of("quarantine", "rescan", "release", "delete");
    private static final List<String> DENIED_STATES = List.of("quarantined", "infected", "unscannable");
    private static final String QUARANTINE_REQUIRED =
        "Requires the ATTACHMENT_QUARANTINE_MANAGE permission in this workspace";

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter securityFilter;
    @Autowired private AttachmentMapper attachmentMapper;
    @Autowired private AttachmentScanMapper scanMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private RoleMapper roleMapper;
    @Autowired private AiChatMapper chatMapper;
    @MockitoSpyBean private AuditService auditService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TenantContext tenantContext;
    @Autowired private ObjectMapper objectMapper;
    @LocalServerPort private int port;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        clearContext();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(securityFilter).build();
    }

    @AfterEach
    void clearContext() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
        tenantContext.clear();
    }

    @Test
    void unmanagedReferenceLifecycleReturnsExplicitBadRequestWithoutMutation() throws Exception {
        Workspace workspace = workspace(organization());
        Attachment attachment = attachment(workspace);
        MockHttpSession session = login(member(workspace, "admin"));
        for (String url : List.of("https://external.example/file.txt", "/api/attachments/content/invalid.txt")) {
            jdbc.update("UPDATE attachment SET url = ? WHERE id = ?", url, attachment.getId());
            for (String action : ACTIONS) {
                mockMvc.perform(request(action, attachment.getId()).session(session)
                        .with(csrf().asHeader()).header("X-Workspace-Id", workspace.getId()))
                    .andExpect(status().isBadRequest())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message")
                        .value("Quarantine lifecycle is not applicable to unmanaged attachment references"));
            }
            assertEquals("pending", scanMapper.getById(workspace.getId(), attachment.getId()).getScanState());
            assertEquals(0, eventCount(attachment));
        }
    }

    @Test
    void allEndpointsDenyForeignWorkspaceAndForeignOrganizationWithoutChangingAttachment()
            throws Exception {
        Organization organization = organization();
        Workspace owning = workspace(organization);
        Attachment attachment = attachment(owning);
        Workspace sibling = workspace(organization);
        Workspace foreign = workspace(organization());

        for (Workspace callerWorkspace : List.of(sibling, foreign)) {
            User actor = member(callerWorkspace, "admin");
            MockHttpSession session = login(actor);
            for (String action : ACTIONS) {
                mockMvc.perform(request(action, attachment.getId())
                        .session(session).with(csrf().asHeader())
                        .header("X-Workspace-Id", callerWorkspace.getId()))
                    .andExpect(status().isNotFound());
                mockMvc.perform(request(action, attachment.getId())
                        .session(session).with(csrf().asHeader())
                        .header("X-Workspace-Id", owning.getId()))
                    .andExpect(status().isForbidden());
            }
        }

        assertEquals("pending", scanMapper.getById(owning.getId(), attachment.getId()).getScanState());
        assertEquals(0, eventCount(attachment));
    }

    @Test
    void ordinaryAttachmentPermissionsCannotManageQuarantineAndAnonymousRequestsAreDenied()
            throws Exception {
        Workspace workspace = workspace(organization());
        Attachment attachment = attachment(workspace);
        MockHttpSession memberSession = login(member(workspace, "member"));

        for (String action : ACTIONS) {
            mockMvc.perform(request(action, attachment.getId())
                    .session(memberSession).with(csrf().asHeader())
                    .header("X-Workspace-Id", workspace.getId()))
                .andExpect(status().isForbidden());
            mockMvc.perform(request(action, attachment.getId()).with(csrf().asHeader())
                    .header("X-Workspace-Id", workspace.getId()))
                .andExpect(status().isUnauthorized());
        }

        assertEquals("pending", scanMapper.getById(workspace.getId(), attachment.getId()).getScanState());
        assertEquals(0, eventCount(attachment));
    }

    @Test
    void administratorCanQuarantineRequestRescanAndReleaseThenDeleteWithScopedAudit()
            throws Exception {
        Workspace workspace = workspace(organization());
        Attachment attachment = attachment(workspace);
        MockHttpSession session = login(member(workspace, "admin"));

        perform("quarantine", attachment, session);
        assertEquals("quarantined", scanMapper.getById(workspace.getId(), attachment.getId()).getScanState());
        perform("rescan", attachment, session);
        assertEquals("pending", scanMapper.getById(workspace.getId(), attachment.getId()).getScanState());
        perform("quarantine", attachment, session);
        perform("release", attachment, session);
        Attachment awaiting = scanMapper.getById(workspace.getId(), attachment.getId());
        assertEquals("pending", awaiting.getScanState());
        assertNull(awaiting.getScanOwner());
        assertEquals(false, scanMapper.isReadable(workspace.getId(), attachment.getUrl(), false));
        perform("quarantine", attachment, session);
        perform("delete", attachment, session);

        assertNull(scanMapper.getById(workspace.getId(), attachment.getId()));
        assertEquals(6, eventCount(attachment));
        assertEquals(1, jdbc.queryForObject(
            "SELECT COUNT(*) FROM object_deletion_queue WHERE workspace_id = ? AND object_key LIKE ?",
            Integer.class, workspace.getId(), "%" + attachment.getUrl().substring(
                attachment.getUrl().lastIndexOf('/') + 1)));
    }

    @Test
    void deletingOneQuarantinedAliasCannotRestoreReadabilityOfTheRemainingAlias() throws Exception {
        Workspace workspace = workspace(organization());
        Attachment first = attachment(workspace);
        Attachment sibling = new Attachment();
        sibling.setWorkspaceId(workspace.getId());
        sibling.setEntityType("user");
        sibling.setEntityId(1);
        sibling.setFileName("retained-alias.txt");
        sibling.setUrl(first.getUrl());
        sibling.setContentType("text/plain");
        sibling.setSize(3L);
        attachmentMapper.insert(sibling);
        MockHttpSession session = login(member(workspace, "admin"));

        perform("quarantine", first, session);
        assertEquals("quarantined", scanMapper.getById(workspace.getId(), sibling.getId()).getScanState());
        perform("delete", first, session);

        assertNull(scanMapper.getById(workspace.getId(), first.getId()));
        assertEquals("quarantined", scanMapper.getById(workspace.getId(), sibling.getId()).getScanState());
        assertEquals(false, scanMapper.isReadable(workspace.getId(), sibling.getUrl(), false));
        assertEquals(0, jdbc.queryForObject(
            "SELECT COUNT(*) FROM object_deletion_queue WHERE workspace_id = ?", Integer.class,
            workspace.getId()));
        perform("release", sibling, session);
        assertEquals("pending", scanMapper.getById(workspace.getId(), sibling.getId()).getScanState());
    }

    @Test
    void realHttpRequestsEnforceSessionsCsrfAndWorkspaceBeforeQuarantineLifecycle() throws Exception {
        Organization organization = organization();
        Workspace owning = workspace(organization);
        Workspace foreign = workspace(organization);
        User admin = member(owning, "admin");
        Attachment attachment = attachment(owning);
        TestTransaction.flagForCommit();
        TestTransaction.end();

        try (HttpClient authenticated = httpClient(); HttpClient anonymous = httpClient()) {
            HttpResponse<String> login = authenticated.send(
                HttpRequest.newBuilder(httpUri("/api/auth/login"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(
                        java.util.Map.of("username", admin.getUsername(), "password", PASSWORD))))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, login.statusCode());
            CsrfBootstrapDto authenticatedCsrf = httpCsrf(authenticated);
            CsrfBootstrapDto anonymousCsrf = httpCsrf(anonymous);
            try {
                for (String action : ACTIONS) {
                    String method = "delete".equals(action) ? "DELETE" : "POST";
                    String path = "/api/attachments/" + attachment.getId() + "/"
                        + ("delete".equals(action) ? "quarantine" : action);
                    assertEquals(401, http(anonymous, method, path, owning.getId(), anonymousCsrf).statusCode());
                    assertEquals(403, http(authenticated, method, path, foreign.getId(), authenticatedCsrf).statusCode());
                    assertEquals(403, authenticated.send(HttpRequest.newBuilder(httpUri(path))
                        .timeout(Duration.ofSeconds(15))
                        .header("X-Workspace-Id", Integer.toString(owning.getId()))
                        .method(method, HttpRequest.BodyPublishers.noBody()).build(),
                        HttpResponse.BodyHandlers.ofString()).statusCode());
                }
                assertEquals(0, eventCount(attachment));
                for (String action : List.of("quarantine", "rescan", "quarantine", "release", "quarantine")) {
                    HttpResponse<String> response = http(authenticated, "POST",
                        "/api/attachments/" + attachment.getId() + "/" + action,
                        owning.getId(), authenticatedCsrf);
                    assertEquals(204, response.statusCode());
                    assertEquals("", response.body());
                    assertEquals("quarantine".equals(action) ? "quarantined" : "pending",
                        scanMapper.getById(owning.getId(), attachment.getId()).getScanState());
                }
                assertEquals(204, http(authenticated, "DELETE",
                    "/api/attachments/" + attachment.getId() + "/quarantine",
                    owning.getId(), authenticatedCsrf).statusCode());
                assertNull(scanMapper.getById(owning.getId(), attachment.getId()));
                assertEquals(6, eventCount(attachment));
            } finally {
                http(authenticated, "POST", "/api/auth/logout", owning.getId(), authenticatedCsrf);
                http(anonymous, "POST", "/api/auth/logout", owning.getId(), anonymousCsrf);
            }
        } finally {
            clearContext();
            jdbc.update("DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME = ?", admin.getUsername());
            for (Workspace fixture : List.of(owning, foreign)) {
                jdbc.update("DELETE FROM object_deletion_queue WHERE workspace_id = ?", fixture.getId());
                jdbc.update("DELETE FROM attachment WHERE workspace_id = ?", fixture.getId());
                jdbc.update("DELETE FROM workspace_member WHERE workspace_id = ?", fixture.getId());
                jdbc.update("DELETE FROM workspace WHERE id = ?", fixture.getId());
            }
            jdbc.update("DELETE FROM app_user WHERE id = ?", admin.getId());
            jdbc.update("DELETE FROM organization WHERE id = ?", organization.getId());
        }
    }

    @Test
    void ordinaryDeletionOfDeniedAttachmentRequiresQuarantineAuthority() throws Exception {
        Workspace workspace = workspace(organization());
        MockHttpSession memberSession = login(member(workspace, "member"));

        for (String state : DENIED_STATES) {
            Attachment attachment = attachment(workspace);
            scanState(attachment, state);
            mockMvc.perform(deleteAttachment(attachment, memberSession, attachment.getWorkspaceId()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(QUARANTINE_REQUIRED));
            assertEquals(state, scanMapper.getById(workspace.getId(), attachment.getId()).getScanState());
            assertEquals(0, attachmentAuditCount(attachment, "%"));
        }
        assertEquals(0, deletionQueueCount(workspace));
    }

    @Test
    void assistantDeletionOfDeniedAttachmentRequiresQuarantineAuthority() throws Exception {
        Workspace workspace = workspace(organization());
        User assistantUser = customRoleMember(workspace, List.of("AI_USE", "ATTACHMENT_DELETE"));
        AiChatSession chat = chatSession(workspace, assistantUser);
        MockHttpSession memberSession = login(assistantUser);
        Attachment pending = assistantAttachment(workspace, chat);
        mockMvc.perform(deleteAssistantAttachment(chat, pending, memberSession, pending.getWorkspaceId()))
            .andExpect(status().isNoContent());
        assertNull(scanMapper.getById(workspace.getId(), pending.getId()));
        assertEquals(1, attachmentAuditCount(pending, "attachment.delete"));
        assertEquals(0, attachmentAuditCount(pending, "malware.%"));
        int queuedBeforeRefusals = deletionQueueCount(workspace);

        for (String state : DENIED_STATES) {
            Attachment attachment = assistantAttachment(workspace, chat);
            scanState(attachment, state);
            mockMvc.perform(deleteAssistantAttachment(
                    chat, attachment, memberSession, attachment.getWorkspaceId()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(QUARANTINE_REQUIRED));
            assertEquals(state, scanMapper.getById(workspace.getId(), attachment.getId()).getScanState());
            assertEquals(0, attachmentAuditCount(attachment, "%"));
        }
        assertEquals(queuedBeforeRefusals, deletionQueueCount(workspace));
    }

    @Test
    void preVerdictManagedAndExternalAttachmentsRemainDeletableByMembers() throws Exception {
        Workspace workspace = workspace(organization());
        MockHttpSession memberSession = login(member(workspace, "member"));
        Attachment external = attachment(workspace);
        jdbc.update("UPDATE attachment SET url = ? WHERE id = ?",
            "https://external.example/" + UUID.randomUUID() + ".txt", external.getId());
        List<Attachment> deletable = new ArrayList<>(List.of(external));
        for (String state : List.of("pending", "scanning", "error", "clean")) {
            Attachment attachment = attachment(workspace);
            scanState(attachment, state);
            deletable.add(attachment);
        }

        for (Attachment attachment : deletable) {
            mockMvc.perform(deleteAttachment(attachment, memberSession, attachment.getWorkspaceId()))
                .andExpect(status().isOk());
            assertNull(scanMapper.getById(workspace.getId(), attachment.getId()));
            assertEquals(1, attachmentAuditCount(attachment, "attachment.delete"));
            assertEquals(0, attachmentAuditCount(attachment, "malware.%"));
        }
    }

    @Test
    void administratorDeletesDeniedAttachmentsOnBothRoutesThroughStrictQuarantineAudit() throws Exception {
        Workspace workspace = workspace(organization());
        User admin = member(workspace, "admin");
        AiChatSession chat = chatSession(workspace, admin);
        Attachment generic = attachment(workspace);
        scanState(generic, "infected");
        Attachment assistant = assistantAttachment(workspace, chat);
        scanState(assistant, "unscannable");
        MockHttpSession session = login(admin);

        mockMvc.perform(deleteAttachment(generic, session, generic.getWorkspaceId()))
            .andExpect(status().isOk());
        mockMvc.perform(deleteAssistantAttachment(chat, assistant, session, assistant.getWorkspaceId()))
            .andExpect(status().isNoContent());

        for (Attachment attachment : List.of(generic, assistant)) {
            assertNull(scanMapper.getById(workspace.getId(), attachment.getId()));
            assertEquals(1, attachmentAuditCount(attachment, "malware.quarantine_deleted"));
            assertEquals(0, attachmentAuditCount(attachment, "attachment.delete"));
            verify(auditService).recordStrict(eq("malware.quarantine_deleted"), eq("attachment"),
                eq(attachment.getId()), any(), any(), any());
        }
        assertEquals(2, deletionQueueCount(workspace));
    }

    @Test
    void foreignWorkspaceCallersCannotDeleteDeniedAttachmentsOnEitherRoute() throws Exception {
        Organization organization = organization();
        Workspace owning = workspace(organization);
        AiChatSession chat = chatSession(owning, member(owning, "admin"));
        Attachment generic = attachment(owning);
        scanState(generic, "quarantined");
        Attachment assistant = assistantAttachment(owning, chat);
        scanState(assistant, "quarantined");

        for (Workspace callerWorkspace : List.of(workspace(organization), workspace(organization()))) {
            MockHttpSession session = login(member(callerWorkspace, "admin"));
            mockMvc.perform(deleteAttachment(generic, session, callerWorkspace.getId()))
                .andExpect(status().isNotFound());
            mockMvc.perform(deleteAssistantAttachment(chat, assistant, session, callerWorkspace.getId()))
                .andExpect(status().isNotFound());
            mockMvc.perform(deleteAttachment(generic, session, owning.getId()))
                .andExpect(status().isForbidden());
            mockMvc.perform(deleteAssistantAttachment(chat, assistant, session, owning.getId()))
                .andExpect(status().isForbidden());
        }

        for (Attachment attachment : List.of(generic, assistant)) {
            assertEquals("quarantined", scanMapper.getById(owning.getId(), attachment.getId()).getScanState());
            assertEquals(0, attachmentAuditCount(attachment, "%"));
        }
        assertEquals(0, deletionQueueCount(owning));
    }

    @Test
    void failedStrictAuditRollsBackDeniedDeletionAndQueuedBytesOnBothRoutes() throws Exception {
        Organization organization = organization();
        Workspace workspace = workspace(organization);
        User admin = member(workspace, "admin");
        AiChatSession chat = chatSession(workspace, admin);
        Attachment generic = attachment(workspace);
        scanState(generic, "quarantined");
        Attachment assistant = assistantAttachment(workspace, chat);
        scanState(assistant, "infected");
        TestTransaction.flagForCommit();
        TestTransaction.end();
        doThrow(new DataAccessResourceFailureException("audit append failed")).when(auditService)
            .recordStrict(eq("malware.quarantine_deleted"), any(), any(), any(), any(), any());

        try {
            MockHttpSession session = login(admin);
            mockMvc.perform(deleteAttachment(generic, session, generic.getWorkspaceId()))
                .andExpect(status().isInternalServerError());
            mockMvc.perform(deleteAssistantAttachment(chat, assistant, session, assistant.getWorkspaceId()))
                .andExpect(status().isInternalServerError());

            verify(auditService, times(2)).recordStrict(eq("malware.quarantine_deleted"),
                any(), any(), any(), any(), any());
            assertEquals("quarantined", scanMapper.getById(workspace.getId(), generic.getId()).getScanState());
            assertEquals("infected", scanMapper.getById(workspace.getId(), assistant.getId()).getScanState());
            assertEquals(0, attachmentAuditCount(generic, "%"));
            assertEquals(0, attachmentAuditCount(assistant, "%"));
            assertEquals(0, deletionQueueCount(workspace));
        } finally {
            clearContext();
            jdbc.update("DELETE FROM object_deletion_queue WHERE workspace_id = ?", workspace.getId());
            jdbc.update("DELETE FROM attachment WHERE workspace_id = ?", workspace.getId());
            jdbc.update("DELETE FROM ai_chat_session WHERE workspace_id = ?", workspace.getId());
            jdbc.update("DELETE FROM workspace_member WHERE workspace_id = ?", workspace.getId());
            jdbc.update("DELETE FROM workspace WHERE id = ?", workspace.getId());
            jdbc.update("DELETE FROM app_user WHERE id = ?", admin.getId());
            jdbc.update("DELETE FROM organization WHERE id = ?", organization.getId());
        }
    }

    private HttpClient httpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }

    private URI httpUri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private CsrfBootstrapDto httpCsrf(HttpClient client) throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(httpUri("/api/auth/csrf"))
            .timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        CsrfBootstrapDto csrf = objectMapper.readValue(response.body(), CsrfBootstrapDto.class);
        assertNotNull(csrf);
        assertNotNull(csrf.headerName());
        assertNotNull(csrf.token());
        return csrf;
    }

    private HttpResponse<String> http(HttpClient client, String method, String path,
            int workspaceId, CsrfBootstrapDto csrf) throws Exception {
        return client.send(HttpRequest.newBuilder(httpUri(path)).timeout(Duration.ofSeconds(15))
            .header("X-Workspace-Id", Integer.toString(workspaceId))
            .header(csrf.headerName(), csrf.token())
            .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    private void perform(String action, Attachment attachment, MockHttpSession session) throws Exception {
        mockMvc.perform(request(action, attachment.getId()).session(session).with(csrf().asHeader())
                .header("X-Workspace-Id", attachment.getWorkspaceId()))
            .andExpect(status().isNoContent());
    }

    private MockHttpServletRequestBuilder request(String action, int id) {
        return "delete".equals(action)
            ? delete("/api/attachments/{id}/quarantine", id)
            : post("/api/attachments/{id}/{action}", id, action);
    }

    private MockHttpServletRequestBuilder deleteAttachment(
            Attachment attachment, MockHttpSession session, int workspaceId) {
        return delete("/api/attachments/{id}", attachment.getId())
            .session(session).with(csrf().asHeader()).header("X-Workspace-Id", workspaceId);
    }

    private MockHttpServletRequestBuilder deleteAssistantAttachment(
            AiChatSession chat, Attachment attachment, MockHttpSession session, int workspaceId) {
        return delete("/api/ai/assistant/sessions/{sessionId}/attachments/{attachmentId}",
                chat.getId(), attachment.getId())
            .session(session).with(csrf().asHeader()).header("X-Workspace-Id", workspaceId);
    }

    private void scanState(Attachment attachment, String state) {
        assertEquals(1, jdbc.update("UPDATE attachment SET scan_state = ? WHERE id = ?",
            state, attachment.getId()));
    }

    private int attachmentAuditCount(Attachment attachment, String actionPattern) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE workspace_id = ? AND entity_type = 'attachment'"
                + " AND entity_id = ? AND action LIKE ?",
            Integer.class, attachment.getWorkspaceId(), attachment.getId(), actionPattern);
        assertNotNull(count);
        return count;
    }

    private int deletionQueueCount(Workspace workspace) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM object_deletion_queue WHERE workspace_id = ?",
            Integer.class, workspace.getId());
        assertNotNull(count);
        return count;
    }

    private User customRoleMember(Workspace workspace, List<String> permissions) {
        User user = member(workspace, "member");
        WorkspaceRole role = new WorkspaceRole();
        role.setWorkspaceId(workspace.getId());
        role.setName("Quarantine assistant " + unique());
        roleMapper.insertRole(role);
        roleMapper.insertPermissions(workspace.getId(), role.getId(), permissions);
        workspaceMapper.setMemberCustomRole(workspace.getId(), user.getId(), role.getId());
        return user;
    }

    private AiChatSession chatSession(Workspace workspace, User owner) {
        AiChatSession chat = new AiChatSession();
        chat.setWorkspaceId(workspace.getId());
        chat.setCreatedByUserId(owner.getId());
        chat.setTitle("Quarantine assistant");
        chat.setVisibility("private");
        chat.setStatus("active");
        chatMapper.insertSession(chat);
        return chat;
    }

    private Attachment assistantAttachment(Workspace workspace, AiChatSession chat) {
        Attachment attachment = new Attachment();
        attachment.setWorkspaceId(workspace.getId());
        attachment.setEntityType("ai_chat_session");
        attachment.setEntityId(chat.getId());
        attachment.setFileName("assistant.txt");
        attachment.setUrl("/api/attachments/content/" + UUID.randomUUID() + ".txt");
        attachment.setContentType("text/plain");
        attachment.setSize(3L);
        attachmentMapper.insert(attachment);
        return attachment;
    }

    private int eventCount(Attachment attachment) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE workspace_id = ? AND entity_id = ? AND action LIKE 'malware.%'",
            Integer.class, attachment.getWorkspaceId(), attachment.getId());
        assertNotNull(count);
        return count;
    }

    private Organization organization() {
        Organization organization = new Organization();
        organization.setName("Quarantine " + unique());
        organization.setSlug("quarantine-org-" + unique());
        organizationMapper.insert(organization);
        return organization;
    }

    private Workspace workspace(Organization organization) {
        Workspace workspace = new Workspace();
        workspace.setName("Quarantine " + unique());
        workspace.setSlug("quarantine-" + unique());
        workspace.setOrgId(organization.getId());
        workspaceMapper.insert(workspace);
        return workspace;
    }

    private User member(Workspace workspace, String role) {
        String suffix = unique();
        User user = new User();
        user.setUsername("quarantine_" + suffix);
        user.setDisplayName("Quarantine fixture");
        user.setEmail(suffix + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setTimezone("UTC");
        userMapper.insert(user);
        workspaceMapper.addMember(workspace.getId(), user.getId(), role);
        return user;
    }

    private Attachment attachment(Workspace workspace) {
        Attachment attachment = new Attachment();
        attachment.setWorkspaceId(workspace.getId());
        attachment.setEntityType("user");
        attachment.setEntityId(1);
        attachment.setFileName("retained.txt");
        attachment.setUrl("/api/attachments/content/" + UUID.randomUUID() + ".txt");
        attachment.setContentType("text/plain");
        attachment.setSize(3L);
        attachmentMapper.insert(attachment);
        return attachment;
    }

    private MockHttpSession login(User user) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + user.getUsername()
                    + "\",\"password\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isOk()).andReturn();
        if (!(result.getRequest().getSession(false) instanceof MockHttpSession session)) {
            throw new AssertionError("Quarantine fixture login did not create a session");
        }
        return session;
    }

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
