package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.Cookie;
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
import ooo.klae.connex.backend.mappers.OrgMemberMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.tenant.TenantExportGrantCookie;

/** Full-stack organization-role backstop for tenant export and teardown. */
@SpringBootTest(properties = "connex.tenant-lifecycle.teardown-settle-delay=0s")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TenantLifecycleHttpAuthorizationIntegrationTest {
    private static final String PASSWORD = "Tenant-Lifecycle-Pw1!";

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private UserMapper userMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private OrgMemberMapper orgMemberMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private final List<Integer> organizationIds = new ArrayList<>();
    private final List<Integer> workspaceIds = new ArrayList<>();
    private final List<Integer> userIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(springSecurityFilterChain)
            .build();
    }

    @AfterEach
    void cleanCommittedFixtures() {
        for (int orgId : organizationIds.reversed()) {
            jdbcTemplate.update("DELETE FROM tenant_operation_lease WHERE org_id = ?", orgId);
        }
        for (int workspaceId : workspaceIds.reversed()) {
            jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", workspaceId);
        }
        for (int orgId : organizationIds.reversed()) {
            jdbcTemplate.update("DELETE FROM organization WHERE id = ?", orgId);
        }
        for (int userId : userIds.reversed()) {
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", userId);
        }
    }

    @Test
    void workspaceOwnerWithoutOrgRoleCannotReachExportOrEitherTeardownSurface() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace workspace = newWorkspace();
        User workspaceOwner = newUser();
        workspaceMapper.addMember(workspace.getId(), workspaceOwner.getId(), "owner");
        int orgId = workspace.getOrgId();
        MockHttpSession session = login(workspaceOwner.getUsername());

        mockMvc.perform(post(exportPath(orgId, workspace.getId()))
                .header("X-Workspace-Id", workspace.getId())
                .session(session)
                .with(csrf().asHeader()))
            .andExpect(status().isForbidden());
        mockMvc.perform(get(exportPath(orgId, workspace.getId()))
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isForbidden());
        mockMvc.perform(get(exportPath(orgId, workspace.getId()))
                .cookie(new Cookie("connex_tenant_export_grant", "a".repeat(64)))
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isForbidden())
            .andExpect(content().string("Requires an organization administrator role"));
        mockMvc.perform(get(exportPath(orgId, Integer.MAX_VALUE))
                .cookie(new Cookie("connex_tenant_export_grant", "a".repeat(64)))
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isForbidden())
            .andExpect(content().string("Requires an organization administrator role"));
        mockMvc.perform(delete("/api/orgs/" + orgId + "/workspaces/" + workspace.getId())
                .header("X-Workspace-Id", workspace.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirmation\":\"" + workspace.getSlug() + "\"}")
                .session(session)
                .with(csrf().asHeader()))
            .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/orgs/" + orgId)
                .header("X-Workspace-Id", workspace.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirmation\":\"org\"}")
                .session(session)
                .with(csrf().asHeader()))
            .andExpect(status().isForbidden());
    }

    @Test
    void exportPostAndLegacyGetBothRequireRecentWebAuthnForAnOrgAdmin() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace workspace = newWorkspace();
        User admin = newUser();
        workspaceMapper.addMember(workspace.getId(), admin.getId(), "owner");
        int orgId = workspace.getOrgId();
        orgMemberMapper.addMember(orgId, admin.getId(), "admin");
        MockHttpSession session = login(admin.getUsername());

        mockMvc.perform(post(exportPath(orgId, workspace.getId()))
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isForbidden());
        mockMvc.perform(post(exportPath(orgId, workspace.getId()))
                .header("X-Workspace-Id", workspace.getId())
                .session(session)
                .with(csrf().asHeader()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("RECENT_AUTHENTICATION_REQUIRED"));
        mockMvc.perform(get(exportPath(orgId, workspace.getId()))
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("RECENT_AUTHENTICATION_REQUIRED"));
    }

    @Test
    void exportGrantCookieRedeemsOnAHeaderlessBrowserDownload() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace workspace = newWorkspace();
        User admin = newUser();
        workspaceMapper.addMember(workspace.getId(), admin.getId(), "owner");
        int orgId = workspace.getOrgId();
        orgMemberMapper.addMember(orgId, admin.getId(), "admin");
        MockHttpSession session = login(admin.getUsername());
        session.setAttribute(
            SessionSecurityService.WEBAUTHN_STEP_UP_AT_ATTR,
            System.currentTimeMillis());
        session.setAttribute(
            SessionSecurityService.WEBAUTHN_STEP_UP_USER_ATTR,
            admin.getId());

        MvcResult grantResult = mockMvc.perform(post(exportPath(orgId, workspace.getId()))
                .header("X-Workspace-Id", workspace.getId())
                .session(session)
                .with(csrf().asHeader()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.downloadPath").value(exportPath(orgId, workspace.getId())))
            .andReturn();
        String setCookie = grantResult.getResponse().getHeader("Set-Cookie");
        assertNotNull(setCookie);
        String grantPrefix = TenantExportGrantCookie.NAME + "=";
        int grantEnd = setCookie.indexOf(';');
        assertTrue(setCookie.startsWith(grantPrefix));
        assertTrue(grantEnd > grantPrefix.length());
        String rawGrant = setCookie.substring(grantPrefix.length(), grantEnd);

        MvcResult downloadResult = mockMvc.perform(get(exportPath(orgId, workspace.getId()))
                .cookie(new Cookie(TenantExportGrantCookie.NAME, rawGrant))
                .session(session))
            .andExpect(request().asyncStarted())
            .andReturn();

        mockMvc.perform(asyncDispatch(downloadResult))
            .andExpect(status().isOk());
    }

    private Workspace newWorkspace() {
        String slug = "lifecycle-http-" + UUID.randomUUID().toString().substring(0, 8);
        Organization organization = new Organization();
        organization.setName(slug);
        organization.setSlug(slug);
        organizationMapper.insert(organization);
        organizationIds.add(organization.getId());
        Workspace workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName(slug);
        workspace.setSlug(slug);
        workspaceMapper.insert(workspace);
        workspaceIds.add(workspace.getId());
        return workspace;
    }

    private User newUser() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername("lifecycle_http_" + suffix);
        user.setDisplayName("Lifecycle HTTP " + suffix);
        user.setEmail(suffix + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setTimezone("UTC");
        userMapper.insert(user);
        userIds.add(user.getId());
        return user;
    }

    private MockHttpSession login(String username) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}";
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(session, "login did not establish a tenant lifecycle session");
        return session;
    }

    private static String exportPath(int orgId, int workspaceId) {
        return "/api/orgs/" + orgId + "/workspaces/" + workspaceId + "/export";
    }
}
