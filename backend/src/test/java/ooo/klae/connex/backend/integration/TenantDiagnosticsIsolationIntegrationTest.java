package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceMailConfig;
import ooo.klae.connex.backend.mappers.MailConfigMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.OrgMemberMapper;
import ooo.klae.connex.backend.mappers.SecretValueMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.secrets.StoredSecret;

/**
 * Full-stack diagnostics authorization, active-workspace routing, and redaction backstop.
 */
@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TenantDiagnosticsIsolationIntegrationTest {
    private static final String PASSWORD = "Diagnostics-Test-Pw1!";
    private static final String PLAINTEXT_SENTINEL = "plaintext-credential-sentinel";
    private static final String CIPHERTEXT_SENTINEL = "ciphertext-credential-sentinel";
    private static final String USERNAME_SENTINEL = "smtp-username-sentinel";

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private OrgMemberMapper orgMemberMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private MailConfigMapper mailConfigMapper;
    @Autowired private SecretValueMapper secretValueMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private final List<Integer> createdWorkspaceIds = new ArrayList<>();
    private final List<Integer> createdOrganizationIds = new ArrayList<>();
    private final List<Integer> createdUserIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        RequestContextHolder.resetRequestAttributes();
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
    }

    @AfterEach
    void removeCommittedFixtures() {
        for (Integer workspaceId : createdWorkspaceIds) {
            jdbcTemplate.update("DELETE FROM workspace_mail_config WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM secret_value WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM workspace_member WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", workspaceId);
        }
        for (Integer organizationId : createdOrganizationIds) {
            jdbcTemplate.update("DELETE FROM org_member WHERE org_id = ?", organizationId);
            jdbcTemplate.update("DELETE FROM organization WHERE id = ?", organizationId);
        }
        for (Integer userId : createdUserIds) {
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", userId);
        }
        createdWorkspaceIds.clear();
        createdOrganizationIds.clear();
        createdUserIds.clear();
    }

    @Test
    void diagnosticsAreTenantIsolatedAndCredentialMaterialIsAbsent() throws Exception {
        Organization organization = newOrganization();
        Workspace workspace = newWorkspace(organization);
        Workspace siblingWorkspace = newWorkspace(organization);
        Organization foreignOrganization = newOrganization();
        Workspace foreignWorkspace = newWorkspace(foreignOrganization);
        User actor = newUser();
        workspaceMapper.addMember(workspace.getId(), actor.getId(), "owner");
        workspaceMapper.addMember(siblingWorkspace.getId(), actor.getId(), "owner");
        orgMemberMapper.addMember(organization.getId(), actor.getId(), "owner");
        seedCredentialSentinels(workspace);

        MockHttpSession session = login(actor.getUsername());
        String workspaceResponse = okBody(mockMvc.perform(get(
                        "/api/workspaces/" + workspace.getId() + "/diagnostics")
                        .header("X-Workspace-Id", workspace.getId())
                        .session(session))
                .andReturn(), "workspace diagnostics");
        String organizationResponse = okBody(mockMvc.perform(get(
                        "/api/orgs/" + organization.getId() + "/diagnostics")
                        .header("X-Workspace-Id", workspace.getId())
                        .session(session))
                .andReturn(), "organization diagnostics");

        assertRedacted(workspaceResponse);
        assertRedacted(organizationResponse);

        mockMvc.perform(get("/api/workspaces/" + siblingWorkspace.getId() + "/diagnostics")
                        .header("X-Workspace-Id", workspace.getId())
                        .session(session))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/workspaces/" + foreignWorkspace.getId() + "/diagnostics")
                        .header("X-Workspace-Id", workspace.getId())
                        .session(session))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/orgs/" + foreignOrganization.getId() + "/diagnostics")
                        .header("X-Workspace-Id", workspace.getId())
                        .session(session))
                .andExpect(status().isForbidden());

        workspaceMapper.addMember(foreignWorkspace.getId(), actor.getId(), "member");
        mockMvc.perform(get("/api/orgs/" + organization.getId() + "/diagnostics")
                        .header("X-Workspace-Id", foreignWorkspace.getId())
                        .session(session))
                .andExpect(status().isNotFound());
    }

    private static String okBody(MvcResult result, String label) throws Exception {
        int status = result.getResponse().getStatus();
        String body = result.getResponse().getContentAsString();
        if (status != 200) {
            Exception resolved = result.getResolvedException();
            throw new AssertionError(label + " returned HTTP " + status
                    + " body=" + body
                    + " resolvedException="
                    + (resolved == null ? "none" : resolved.getClass().getName()
                            + ": " + resolved.getMessage()));
        }
        return body;
    }

    private void assertRedacted(String response) {
        String normalized = response.toLowerCase(Locale.ROOT);
        assertFalse(normalized.contains(PLAINTEXT_SENTINEL));
        assertFalse(normalized.contains(CIPHERTEXT_SENTINEL));
        assertFalse(normalized.contains(USERNAME_SENTINEL));
        assertFalse(normalized.contains("encrypted_data_key"));
        assertFalse(normalized.contains("ciphertext"));
        assertFalse(normalized.contains("password"));
        assertFalse(normalized.contains("username"));
    }

    private void seedCredentialSentinels(Workspace workspace) {
        WorkspaceMailConfig mail = new WorkspaceMailConfig();
        mail.setWorkspaceId(workspace.getId());
        mail.setEnabled(true);
        mail.setHost("smtp.example.com");
        mail.setPort(587);
        mail.setUsername(USERNAME_SENTINEL);
        mail.setPasswordEnc(PLAINTEXT_SENTINEL);
        mail.setFromAddress("sender@example.com");
        mail.setFromName("Connex");
        mail.setStarttls(true);
        mail.setAuth(true);
        mailConfigMapper.upsert(mail);

        StoredSecret secret = new StoredSecret();
        secret.setScopeType("workspace");
        secret.setScopeId(workspace.getId());
        secret.setPurpose("diagnostics_test_credential");
        secret.setKeyId("missing-diagnostics-key");
        secret.setKeyAlgorithm("AES-GCM");
        secret.setDataAlgorithm("AES-256-GCM");
        secret.setEncryptedDataKey(PLAINTEXT_SENTINEL);
        secret.setCiphertext(CIPHERTEXT_SENTINEL);
        secretValueMapper.upsert(secret);
    }

    private MockHttpSession login(String username) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}";
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(session);
        return session;
    }

    private Organization newOrganization() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization organization = new Organization();
        organization.setName("Diagnostics Org " + suffix);
        organization.setSlug("diagnostics-org-" + suffix);
        organizationMapper.insert(organization);
        createdOrganizationIds.add(organization.getId());
        return organization;
    }

    private Workspace newWorkspace(Organization organization) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Workspace workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("Diagnostics Workspace " + suffix);
        workspace.setSlug("diagnostics-workspace-" + suffix);
        workspaceMapper.insert(workspace);
        createdWorkspaceIds.add(workspace.getId());
        return workspace;
    }

    private User newUser() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername("diagnostics_" + suffix);
        user.setDisplayName("Diagnostics " + suffix);
        user.setEmail(suffix + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setTimezone("UTC");
        userMapper.insert(user);
        createdUserIds.add(user.getId());
        return user;
    }
}
