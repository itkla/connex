package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;

import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
import ooo.klae.connex.backend.mappers.AttachmentScanMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.TenantContext;

/** Exercises every quarantine endpoint through authentication, permission, tenant and SQL boundaries. */
@SpringBootTest
@Transactional
@UnenrolledPrivilegedFixture
class AttachmentQuarantineIntegrationTest {
    private static final String PASSWORD = "Quarantine-Fixture-Pw1!";
    private static final List<String> ACTIONS = List.of("quarantine", "rescan", "release", "delete");

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter securityFilter;
    @Autowired private AttachmentMapper attachmentMapper;
    @Autowired private AttachmentScanMapper scanMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TenantContext tenantContext;

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
        assertEquals(false, scanMapper.isReadable(workspace.getId(), attachment.getUrl()));
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
        assertEquals(false, scanMapper.isReadable(workspace.getId(), sibling.getUrl()));
        assertEquals(0, jdbc.queryForObject(
            "SELECT COUNT(*) FROM object_deletion_queue WHERE workspace_id = ?", Integer.class,
            workspace.getId()));
        perform("release", sibling, session);
        assertEquals("pending", scanMapper.getById(workspace.getId(), sibling.getId()).getScanState());
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
