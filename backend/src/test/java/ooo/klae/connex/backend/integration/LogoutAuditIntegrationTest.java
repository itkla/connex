package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.AuditService;

/**
 * Exercises the real Spring Security logout chain and audit persistence over authenticated,
 * anonymous, repeated, foreign-workspace, and audit-sink-failure requests.
 */
@SpringBootTest
@Transactional
class LogoutAuditIntegrationTest {

    private static final String PASSWORD = "Logout-Test-Pw1!";
    private static final String USER_AGENT = "Connex logout integration test";
    private static final String COOKIE_SECRET = "opaque-cookie-secret";

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoSpyBean private AuditService auditService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(springSecurityFilterChain)
            .build();
    }

    @Test
    void successfulLogoutWritesOneScopedSecretFreeAuditEvent() throws Exception {
        Workspace workspace = newWorkspace("primary");
        User user = newMember(workspace);
        String rawSessionId = "raw-logout-session-" + UUID.randomUUID();
        MockHttpSession authenticatedSession = login(user.getUsername());
        MockHttpSession session = new MockHttpSession(context.getServletContext(), rawSessionId);
        session.deserializeState(authenticatedSession.serializeState());

        mockMvc.perform(post("/api/auth/logout")
                .session(session)
                .header("X-Workspace-Id", workspace.getId())
                .header("User-Agent", USER_AGENT)
                .cookie(new Cookie("unrelated_secret", COOKIE_SECRET))
                .with(request -> {
                    request.setRemoteAddr("198.51.100.27");
                    return request;
                }))
            .andExpect(status().isOk());

        assertThrows(IllegalStateException.class, () -> session.getAttribute("SPRING_SECURITY_CONTEXT"));
        assertEquals(1, logoutCount(user.getId()));
        LogoutAuditRow row = logoutRow(user.getId());
        assertEquals(user.getId(), row.actorId());
        assertEquals(user.getDisplayName(), row.actorLabel());
        assertEquals(workspace.getId(), row.workspaceId());
        assertEquals(workspace.getOrgId(), row.orgId());
        assertEquals(sha256Hex(rawSessionId), row.sessionId());
        assertEquals("198.51.100.27", row.ipAddress());
        assertEquals(USER_AGENT, row.userAgent());
        assertNotNull(row.requestId());
        assertFalse(row.persistedText().contains(rawSessionId));
        assertFalse(row.persistedText().contains(COOKIE_SECRET));

        mockMvc.perform(post("/api/auth/logout"))
            .andExpect(status().isOk());
        assertEquals(1, logoutCount(user.getId()));
    }

    @Test
    void anonymousAndRepeatedLogoutDoNotCreateEvents() throws Exception {
        Integer countBefore = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action = 'auth.logout'", Integer.class);
        mockMvc.perform(post("/api/auth/logout"))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/logout"))
            .andExpect(status().isOk());

        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action = 'auth.logout'", Integer.class);
        assertEquals(countBefore, count);
    }

    @Test
    void foreignWorkspaceHeaderCannotMisattributeLogout() throws Exception {
        Workspace ownWorkspace = newWorkspace("own");
        Workspace foreignWorkspace = newWorkspace("foreign");
        User user = newMember(ownWorkspace);
        MockHttpSession session = login(user.getUsername());

        mockMvc.perform(post("/api/auth/logout")
                .session(session)
                .header("X-Workspace-Id", foreignWorkspace.getId()))
            .andExpect(status().isOk());

        LogoutAuditRow row = logoutRow(user.getId());
        assertEquals(ownWorkspace.getId(), row.workspaceId());
        assertEquals(ownWorkspace.getOrgId(), row.orgId());
    }

    @Test
    void auditFailureNeverPreventsSessionDestruction() throws Exception {
        Workspace workspace = newWorkspace("failure");
        User user = newMember(workspace);
        MockHttpSession session = login(user.getUsername());
        doThrow(new IllegalStateException("audit unavailable"))
            .when(auditService)
            .recordScoped(eq("auth.logout"), eq("user"), eq(user.getId()),
                eq(workspace.getId()), eq(workspace.getOrgId()), any(), any(), isNull());

        mockMvc.perform(post("/api/auth/logout")
                .session(session)
                .header("X-Workspace-Id", workspace.getId()))
            .andExpect(status().isOk());

        assertThrows(IllegalStateException.class, () -> session.getAttribute("SPRING_SECURITY_CONTEXT"));
        assertEquals(0, logoutCount(user.getId()));
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

    private Workspace newWorkspace(String label) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization organization = new Organization();
        organization.setName("Logout " + label + " " + suffix);
        organization.setSlug("logout-" + label + "-" + suffix);
        organizationMapper.insert(organization);

        Workspace workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("Logout " + label + " " + suffix);
        workspace.setSlug("logout-workspace-" + label + "-" + suffix);
        workspaceMapper.insert(workspace);
        return workspace;
    }

    private User newMember(Workspace workspace) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername("logout_" + suffix);
        user.setDisplayName("Logout User " + suffix);
        user.setEmail("logout_" + suffix + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setTimezone("UTC");
        userMapper.insert(user);
        workspaceMapper.addMember(workspace.getId(), user.getId(), "member");
        return user;
    }

    private int logoutCount(int actorId) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action = 'auth.logout' AND actor_id = ?",
            Integer.class,
            actorId);
        return count == null ? 0 : count;
    }

    private LogoutAuditRow logoutRow(int actorId) {
        return jdbcTemplate.queryForObject("""
            SELECT actor_id, actor_label, workspace_id, org_id, session_id, ip_address,
                   user_agent, request_id,
                   CONCAT_WS('|', action, entity_type, entity_id, actor_id, actor_label,
                       target_label, outcome, summary, changes, context, ip_address,
                       user_agent, session_id, request_id) AS persisted_text
            FROM audit_log
            WHERE action = 'auth.logout' AND actor_id = ?
            ORDER BY id DESC
            LIMIT 1
            """, (resultSet, rowNumber) -> new LogoutAuditRow(
                resultSet.getInt("actor_id"),
                resultSet.getString("actor_label"),
                resultSet.getInt("workspace_id"),
                resultSet.getInt("org_id"),
                resultSet.getString("session_id"),
                resultSet.getString("ip_address"),
                resultSet.getString("user_agent"),
                resultSet.getString("request_id"),
                resultSet.getString("persisted_text")),
            actorId);
    }

    private static String sha256Hex(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private record LogoutAuditRow(
            int actorId,
            String actorLabel,
            int workspaceId,
            int orgId,
            String sessionId,
            String ipAddress,
            String userAgent,
            String requestId,
            String persistedText) {
    }
}
