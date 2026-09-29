package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.mappers.OrgMemberMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.InviteEmailService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.TenantContext;

/**
 * The mailbox-proof refusals are conditioned on the instance actually running registration
 * verification. With the feature off no account can ever obtain proof — no token is issued and
 * resend is a no-op — so an {@code email_verified = 0} row left behind by an earlier enabled
 * period must stay invitable through every membership path instead of becoming unreachable.
 */
@SpringBootTest(properties = {
    "connex.registration-verification.enabled=false",
    "connex.workspaces.allow-self-service-creation=false"
})
class IdentityInvitationWithoutVerificationIntegrationTest {
    private static final String PASSWORD = "IdentityProof9!";

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private OrgMemberMapper orgMemberMapper;
    @Autowired private RoleMapper roleMapper;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private TenantContext tenantContext;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoBean private InviteEmailService inviteEmail;

    private MockMvc mockMvc;
    private Workspace target;
    private User manager;
    private MockHttpSession managerSession;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(springSecurityFilterChain)
            .build();
        target = newWorkspace();
        manager = memberManager(target);
        managerSession = login(manager);
        PublicApiTestSecuritySupport.stepUp(managerSession, manager.getId());
    }

    @Test
    void inviteByEmailStillReachesAnUnverifiedAccountInApp() throws Exception {
        User legacy = unverifiedUser();

        invite("invites", legacy.getEmail())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.invite").doesNotExist())
            .andExpect(jsonPath("$.member.id").value(legacy.getId()));
        verifyNoInteractions(inviteEmail);

        mockMvc.perform(post("/api/workspaces/{id}/accept", target.getId())
                .session(login(legacy)).with(csrf().asHeader()))
            .andExpect(status().isOk());
        assertTrue(workspaceMapper.isMember(target.getId(), legacy.getId()));
    }

    @Test
    void membersEndpointStillAddsAnUnverifiedAccount() throws Exception {
        User legacy = unverifiedUser();

        invite("members", legacy.getEmail())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(legacy.getId()));

        assertEquals("pending", workspaceMapper.getMember(target.getId(), legacy.getId()).getStatus());
    }

    @Test
    void organizationRoleWriteStillAcceptsAnUnverifiedAccount() throws Exception {
        User legacy = unverifiedUser();
        orgMemberMapper.addMember(target.getOrgId(), manager.getId(), "owner");

        mockMvc.perform(put("/api/orgs/{id}/members/{userId}", target.getOrgId(), legacy.getId())
                .session(managerSession).with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("orgRole", "admin"))))
            .andExpect(status().isNoContent());
        assertEquals("admin", orgMemberMapper.getRole(target.getOrgId(), legacy.getId()));
    }

    private ResultActions invite(String path, String address) throws Exception {
        return mockMvc.perform(post("/api/workspaces/{id}/" + path, target.getId())
            .session(managerSession).header("X-Workspace-Id", target.getId())
            .with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("email", address, "role", "member"))));
    }

    private MockHttpSession login(User user) throws Exception {
        return session(mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    Map.of("username", user.getUsername(), "password", PASSWORD))))
            .andExpect(status().isOk()).andReturn());
    }

    private User memberManager(Workspace workspace) {
        User user = newUser(true);
        workspaceMapper.addMember(workspace.getId(), user.getId(), "member");
        tenantContext.set(workspace.getId(), workspace.getOrgId(), user.getId(), "member", null);
        try {
            WorkspaceRole role = new WorkspaceRole();
            role.setWorkspaceId(workspace.getId());
            role.setName("Member manager " + unique());
            roleMapper.insertRole(role);
            List<String> permissions = new ArrayList<>(workspaceService.builtInRoles().stream()
                .filter(candidate -> "member".equals(candidate.getName())).findFirst().orElseThrow().getPermissions());
            permissions.add(Permission.MEMBER_MANAGE.name());
            roleMapper.insertPermissions(workspace.getId(), role.getId(), permissions);
            workspaceMapper.setMemberCustomRole(workspace.getId(), user.getId(), role.getId());
        } finally {
            tenantContext.clear();
        }
        PublicApiTestSecuritySupport.enrollPasskey(jdbcTemplate, user);
        return user;
    }

    private Workspace newWorkspace() {
        Organization organization = new Organization();
        organization.setName("Identity trust off " + unique());
        organization.setSlug("identity-trust-off-" + unique());
        organizationMapper.insert(organization);
        Workspace workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("Identity trust off " + unique());
        workspace.setSlug("identity-trust-off-" + unique());
        workspaceMapper.insert(workspace);
        return workspace;
    }

    private User unverifiedUser() {
        User user = newUser(false);
        assertFalse(userMapper.getUserById(user.getId()).isEmailVerified(),
            "the fixture must reproduce a row left unverified by an earlier enabled period");
        return user;
    }

    private User newUser(boolean emailVerified) {
        User user = new User();
        user.setUsername("identity_" + unique());
        user.setDisplayName("Identity " + unique());
        user.setEmail(unique() + "@example.com");
        user.setEmailVerified(emailVerified);
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setTimezone("UTC");
        userMapper.insert(user);
        return user;
    }

    private static MockHttpSession session(MvcResult result) {
        return assertInstanceOf(MockHttpSession.class, result.getRequest().getSession(false));
    }

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
