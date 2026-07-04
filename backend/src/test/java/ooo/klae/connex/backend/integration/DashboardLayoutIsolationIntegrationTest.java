package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

/**
 * Full-stack isolation backstop for the per-user dashboard layout: exercises the real controller →
 * security filter chain → {@code TenantResolutionInterceptor} → service → mapper path over HTTP
 * (MockMvc with the actual login + session + CSRF), asserting a layout is private to its owner
 * within a workspace, scoped per workspace for the same user, and unreachable for a non-member
 * workspace or an unauthenticated caller. Complements the mapper/service unit isolation tests.
 */
@SpringBootTest
@Transactional
class DashboardLayoutIsolationIntegrationTest {

    private static final String PASSWORD = "Dash-Test-Pw1!";

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private UserMapper userMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private PasswordEncoder passwordEncoder;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(springSecurityFilterChain)
            .build();
    }

    @Test
    void layoutIsPrivatePerUserWithinAWorkspace() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace ws = newWorkspace();
        User alice = newMember(ws);
        User carol = newMember(ws);

        MockHttpSession aliceSession = login(alice.getUsername());
        saveLayout(aliceSession, ws, "alice");

        MockHttpSession carolSession = login(carol.getUsername());
        saveLayout(carolSession, ws, "carol");

        mockMvc.perform(get("/api/dashboard-layout")
                .header("X-Workspace-Id", ws.getId())
                .session(aliceSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.layout.marker").value("alice"));

        mockMvc.perform(get("/api/dashboard-layout")
                .header("X-Workspace-Id", ws.getId())
                .session(carolSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.layout.marker").value("carol"));
    }

    @Test
    void layoutIsScopedPerWorkspaceForTheSameUser() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace wsA = newWorkspace();
        Workspace wsB = newWorkspace();
        User alice = newMember(wsA);
        workspaceMapper.addMember(wsB.getId(), alice.getId(), "member");

        MockHttpSession session = login(alice.getUsername());
        saveLayout(session, wsA, "in-a");
        saveLayout(session, wsB, "in-b");

        mockMvc.perform(get("/api/dashboard-layout")
                .header("X-Workspace-Id", wsA.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.layout.marker").value("in-a"));

        mockMvc.perform(get("/api/dashboard-layout")
                .header("X-Workspace-Id", wsB.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.layout.marker").value("in-b"));
    }

    @Test
    void nonMemberWorkspaceIsRejected() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace wsA = newWorkspace();
        Workspace wsB = newWorkspace();
        User alice = newMember(wsA);

        MockHttpSession session = login(alice.getUsername());
        mockMvc.perform(get("/api/dashboard-layout")
                .header("X-Workspace-Id", wsB.getId())
                .session(session))
            .andExpect(status().isForbidden());
    }

    @Test
    void unauthenticatedIsRejected() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        mockMvc.perform(get("/api/dashboard-layout"))
            .andExpect(status().is4xxClientError());
    }

    @Test
    void putWithoutLayoutIsRejected() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace ws = newWorkspace();
        User alice = newMember(ws);
        MockHttpSession session = login(alice.getUsername());

        mockMvc.perform(put("/api/dashboard-layout")
                .header("X-Workspace-Id", ws.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .session(session)
                .with(csrf()))
            .andExpect(status().isBadRequest());
    }

    @Test
    void resetRemovesLayout() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace ws = newWorkspace();
        User alice = newMember(ws);
        MockHttpSession session = login(alice.getUsername());
        saveLayout(session, ws, "alice");

        mockMvc.perform(delete("/api/dashboard-layout")
                .header("X-Workspace-Id", ws.getId())
                .session(session)
                .with(csrf()))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/dashboard-layout")
                .header("X-Workspace-Id", ws.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.layout").doesNotExist());
    }

    private void saveLayout(MockHttpSession session, Workspace workspace, String marker) throws Exception {
        String body = "{\"layout\":{\"version\":\"1\",\"marker\":\"" + marker + "\"}}";
        mockMvc.perform(put("/api/dashboard-layout")
                .header("X-Workspace-Id", workspace.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .session(session)
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.layout.marker").value(marker));
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

    private Workspace newWorkspace() {
        String slug = "ws-" + UUID.randomUUID().toString().substring(0, 8);
        Workspace workspace = new Workspace();
        workspace.setName(slug);
        workspace.setSlug(slug);
        workspaceMapper.insert(workspace);
        return workspace;
    }

    private User newMember(Workspace workspace) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername("user_" + suffix);
        user.setDisplayName("User " + suffix);
        user.setEmail(suffix + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setTimezone("UTC");
        userMapper.insert(user);
        workspaceMapper.addMember(workspace.getId(), user.getId(), "member");
        return user;
    }
}
