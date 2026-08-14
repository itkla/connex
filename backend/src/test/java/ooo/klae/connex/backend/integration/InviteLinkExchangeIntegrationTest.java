package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceInvite;
import ooo.klae.connex.backend.config.OneTimeLinkFlowCookie;
import ooo.klae.connex.backend.mappers.InviteMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.util.OneTimeTokenDigest;

/** Exercises emailed-invite exchange, purpose isolation, and token-free cross-tenant redemption. */
@SpringBootTest
@Transactional
class InviteLinkExchangeIntegrationTest {

    private static final String PASSWORD = "Invite-Exchange-Pw1!";

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private InviteMapper inviteMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(springSecurityFilterChain)
            .build();
    }

    @Test
    void exchangePersistsOnlyHashAndRedeemsWithoutTenantHeaderInfluence() throws Exception {
        Workspace invitedWorkspace = newWorkspace("target");
        Workspace foreignWorkspace = newWorkspace("foreign");
        User inviter = newUser("inviter");
        User recipient = newUser("recipient");
        workspaceMapper.addMember(invitedWorkspace.getId(), inviter.getId(), "admin");
        workspaceMapper.addMember(foreignWorkspace.getId(), recipient.getId(), "member");
        String rawToken = token("invite");
        WorkspaceInvite invite = insertInvite(
            invitedWorkspace, inviter, recipient.getEmail(), rawToken);

        assertEquals(OneTimeTokenDigest.sha256(rawToken),
            jdbcTemplate.queryForObject(
                "SELECT token_hash FROM workspace_invite WHERE id = ?", String.class, invite.getId()));
        Integer rawColumnCount = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'workspace_invite'
              AND column_name = 'token'
            """, Integer.class);
        assertEquals(0, rawColumnCount);

        MvcResult exchanged = exchange("/api/invites/exchange", rawToken, 303);
        assertEquals("/invite", exchanged.getResponse().getHeader(HttpHeaders.LOCATION));
        assertResponseSecretFree(exchanged, rawToken);
        Cookie flowCookie = flowCookie(exchanged);
        MockHttpSession session = session(exchanged);

        MvcResult replay = exchange("/api/invites/exchange", rawToken, 400);
        assertResponseSecretFree(replay, rawToken);

        String expiredToken = token("expired-invite");
        WorkspaceInvite expiredInvite = insertInvite(
            invitedWorkspace, inviter, recipient.getEmail(), expiredToken);
        jdbcTemplate.update(
            "UPDATE workspace_invite SET expires_at = DATE_SUB(UTC_TIMESTAMP(), INTERVAL 1 MINUTE) WHERE id = ?",
            expiredInvite.getId());
        MvcResult expired = exchange("/api/invites/exchange", expiredToken, 400);
        assertResponseSecretFree(expired, expiredToken);

        MvcResult wrongPurpose = exchange("/api/auth/reset-password/exchange", rawToken, 400);
        assertResponseSecretFree(wrongPurpose, rawToken);

        session = login(recipient, session);
        mockMvc.perform(get("/api/invites")
                .session(session)
                .cookie(flowCookie)
                .header("X-Workspace-Id", foreignWorkspace.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.workspaceId").value(invitedWorkspace.getId()))
            .andExpect(jsonPath("$.email").value(recipient.getEmail()))
            .andExpect(jsonPath("$.valid").value(true));

        mockMvc.perform(post("/api/invites/accept")
                .session(session)
                .cookie(flowCookie)
                .header("X-Workspace-Id", foreignWorkspace.getId())
                .with(csrf().asHeader()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(invitedWorkspace.getId()));
        assertTrue(workspaceMapper.isMember(invitedWorkspace.getId(), recipient.getId()));
        assertTrue(workspaceMapper.isMember(foreignWorkspace.getId(), recipient.getId()));

        MvcResult consumedReplay = mockMvc.perform(post("/api/invites/accept")
                .session(session)
                .cookie(flowCookie)
                .with(csrf().asHeader()))
            .andExpect(status().isBadRequest())
            .andReturn();
        assertResponseSecretFree(consumedReplay, rawToken);
        assertNoAuditSecret(rawToken);
    }

    private MvcResult exchange(String path, String rawToken, int expectedStatus) throws Exception {
        return mockMvc.perform(post(path)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + rawToken + "\"}"))
            .andExpect(status().is(expectedStatus))
            .andReturn();
    }

    private MockHttpSession login(User user, MockHttpSession session) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + user.getUsername() +
                    "\",\"password\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
        return session(result);
    }

    private WorkspaceInvite insertInvite(
            Workspace workspace, User inviter, String email, String rawToken) {
        WorkspaceInvite invite = new WorkspaceInvite();
        invite.setWorkspaceId(workspace.getId());
        invite.setEmail(email);
        invite.setRole("member");
        invite.setToken(rawToken);
        invite.setInvitedById(inviter.getId());
        inviteMapper.insert(invite);
        return invite;
    }

    private Workspace newWorkspace(String label) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization organization = new Organization();
        organization.setName("Invite Exchange " + label + " " + suffix);
        organization.setSlug("invite-exchange-" + label + "-" + suffix);
        organizationMapper.insert(organization);

        Workspace workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("Invite Exchange " + label + " " + suffix);
        workspace.setSlug("invite-exchange-workspace-" + label + "-" + suffix);
        workspaceMapper.insert(workspace);
        return workspace;
    }

    private User newUser(String label) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername("invite_exchange_" + label + "_" + suffix);
        user.setDisplayName("Invite Exchange " + label + " " + suffix);
        user.setEmail("invite_exchange_" + label + "_" + suffix + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setTimezone("UTC");
        userMapper.insert(user);
        return user;
    }

    private static String token(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "") +
            UUID.randomUUID().toString().replace("-", "");
    }

    private static MockHttpSession session(MvcResult result) {
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(session);
        return session;
    }

    private static Cookie flowCookie(MvcResult result) {
        String name = OneTimeLinkFlowCookie.WORKSPACE_INVITE;
        String header = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
            .filter(value -> value.startsWith(name + "="))
            .findFirst()
            .orElseThrow();
        assertTrue(header.contains("HttpOnly"));
        assertTrue(header.contains("SameSite=Strict"));
        assertTrue(header.contains("Path=/api/invites"));
        assertFalse(header.contains("token="));
        String value = header.substring(name.length() + 1, header.indexOf(';'));
        return new Cookie(name, value);
    }

    private static void assertResponseSecretFree(MvcResult result, String rawToken) throws Exception {
        assertFalse(result.getResponse().getContentAsString().contains(rawToken));
        for (String value : result.getResponse().getHeaderNames().stream()
                .flatMap(name -> result.getResponse().getHeaders(name).stream())
                .toList()) {
            assertFalse(value.contains(rawToken));
        }
    }

    private void assertNoAuditSecret(String rawToken) {
        Integer count = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM audit_log
            WHERE CONCAT_WS('|', action, entity_type, entity_id, actor_id, actor_label,
                target_label, outcome, summary, changes, context, ip_address,
                user_agent, session_id, request_id) LIKE ?
            """, Integer.class, "%" + rawToken + "%");
        assertEquals(0, count);
    }
}
