package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

/**
 * Regression backstop for the auth-plane / custom-role interaction: SSO admin
 * endpoints live under {@code /api/auth/**}, which is excluded from tenant
 * resolution, so {@code WorkspaceService.permissionsFor} runs there with an
 * unresolved {@link ooo.klae.connex.backend.tenant.TenantContext}. A member
 * whose {@code SSO_MANAGE} comes from a custom role takes the
 * {@code RoleMapper.findPermissions} branch — which sits in the scoped
 * interceptor registry and must therefore stay statement-exempt, or every
 * custom-role SSO admin gets a spurious 403 (the built-in owner/admin path
 * never touches RoleMapper and cannot catch this).
 */
@SpringBootTest
@Transactional
class SsoAdminCustomRoleIntegrationTest {

    private static final String PASSWORD = "Sso-Role-Pw1!";

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private UserMapper userMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private RoleMapper roleMapper;
    @Autowired private PasswordEncoder passwordEncoder;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(springSecurityFilterChain)
            .build();
    }

    @Test
    void customRoleSsoManagerCanReadSsoConfigOnTheAuthPlane() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        String slug = "ws-" + UUID.randomUUID().toString().substring(0, 8);
        Workspace workspace = new Workspace();
        workspace.setName(slug);
        workspace.setSlug(slug);
        workspaceMapper.insert(workspace);

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User ssoManager = new User();
        ssoManager.setUsername("user_" + suffix);
        ssoManager.setDisplayName("User " + suffix);
        ssoManager.setEmail(suffix + "@example.com");
        ssoManager.setPasswordHash(passwordEncoder.encode(PASSWORD));
        ssoManager.setTimezone("UTC");
        userMapper.insert(ssoManager);
        workspaceMapper.addMember(workspace.getId(), ssoManager.getId(), "member");

        WorkspaceRole role = new WorkspaceRole();
        role.setWorkspaceId(workspace.getId());
        role.setName("SSO Manager " + suffix);
        roleMapper.insertRole(role);
        roleMapper.insertPermissions(workspace.getId(), role.getId(), List.of("SSO_MANAGE"));
        workspaceMapper.setMemberCustomRole(workspace.getId(), ssoManager.getId(), role.getId());

        MockHttpSession session = login(ssoManager.getUsername());
        mockMvc.perform(get("/api/auth/sso/config")
                .param("workspaceId", String.valueOf(workspace.getId()))
                .session(session))
            .andExpect(status().isOk());
    }

    private MockHttpSession login(String username) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}";
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(session, "login did not establish a session for " + username);
        return session;
    }
}
