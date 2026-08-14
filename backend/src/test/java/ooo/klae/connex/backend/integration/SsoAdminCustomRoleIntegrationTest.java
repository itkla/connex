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
import ooo.klae.connex.backend.mappers.OrgMemberMapper;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

/**
 * Full-stack authorization backstop for org SSO configuration (#316). SSO admin
 * endpoints live under {@code /api/auth/**} (excluded from tenant resolution) and
 * are now gated on org membership, not workspace permissions. This proves the
 * escalation is closed — a workspace owner, and a custom role carrying the now
 * inert {@code SSO_MANAGE} workspace permission, are both refused unless they are
 * an org administrator — while a genuine org admin is allowed. The org-membership
 * read runs cleanly on the unresolved-context auth plane (the mapper is
 * control-plane classified).
 */
@SpringBootTest
@Transactional
@UnenrolledPrivilegedFixture
class SsoAdminCustomRoleIntegrationTest {

    private static final String PASSWORD = "Sso-Role-Pw1!";

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private UserMapper userMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private RoleMapper roleMapper;
    @Autowired private OrgMemberMapper orgMemberMapper;
    @Autowired private PasswordEncoder passwordEncoder;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(springSecurityFilterChain)
            .build();
    }

    @Test
    void workspaceOwnerWithoutOrgMembership_isForbiddenOnSsoConfig() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace workspace = newWorkspace();
        User owner = newUser();
        workspaceMapper.addMember(workspace.getId(), owner.getId(), "owner");

        MockHttpSession session = login(owner.getUsername());
        getSsoConfig(session, workspace.getId()).andExpect(status().isForbidden());
    }

    @Test
    void customRoleSsoManager_isForbiddenWithoutOrgMembership() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace workspace = newWorkspace();
        User ssoManager = newUser();
        workspaceMapper.addMember(workspace.getId(), ssoManager.getId(), "member");
        WorkspaceRole role = new WorkspaceRole();
        role.setWorkspaceId(workspace.getId());
        role.setName("SSO Manager " + UUID.randomUUID().toString().substring(0, 8));
        roleMapper.insertRole(role);
        roleMapper.insertPermissions(workspace.getId(), role.getId(), List.of("SSO_MANAGE"));
        workspaceMapper.setMemberCustomRole(workspace.getId(), ssoManager.getId(), role.getId());

        MockHttpSession session = login(ssoManager.getUsername());
        getSsoConfig(session, workspace.getId()).andExpect(status().isForbidden());
    }

    @Test
    void orgAdmin_canReadSsoConfig() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace workspace = newWorkspace();
        User admin = newUser();
        workspaceMapper.addMember(workspace.getId(), admin.getId(), "member");
        int orgId = workspaceMapper.getOrgId(workspace.getId());
        orgMemberMapper.addMember(orgId, admin.getId(), "admin");

        MockHttpSession session = login(admin.getUsername());
        getSsoConfig(session, workspace.getId()).andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions getSsoConfig(MockHttpSession session, int workspaceId)
            throws Exception {
        return mockMvc.perform(get("/api/auth/sso/config")
            .param("workspaceId", String.valueOf(workspaceId))
            .session(session));
    }

    private Workspace newWorkspace() {
        String slug = "ws-" + UUID.randomUUID().toString().substring(0, 8);
        Workspace workspace = new Workspace();
        workspace.setName(slug);
        workspace.setSlug(slug);
        workspaceMapper.insert(workspace);
        return workspace;
    }

    private User newUser() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername("user_" + suffix);
        user.setDisplayName("User " + suffix);
        user.setEmail(suffix + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setTimezone("UTC");
        userMapper.insert(user);
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
        assertNotNull(session, "login did not establish a session for " + username);
        return session;
    }
}
