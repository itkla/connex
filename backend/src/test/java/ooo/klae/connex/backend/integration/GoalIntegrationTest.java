package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import tools.jackson.databind.ObjectMapper;

/** Full-stack HTTP coverage for report-goal CRUD, RBAC, validation, and tenant isolation. */
@SpringBootTest
@Transactional
@UnenrolledPrivilegedFixture
class GoalIntegrationTest {
    private static final String PASSWORD = "Goal-Test-Pw1!";

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private UserMapper userMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private RoleMapper roleMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
    }

    @Test
    void managerCanCreateReadUpdateAndDeleteGoals() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace workspace = newWorkspace();
        User manager = newMember(workspace, "admin");
        MockHttpSession session = login(manager.getUsername());

        int goalId = createGoal(session, workspace, goalBody(manager.getId(), "100000.00", "USD"));

        mockMvc.perform(get("/api/goals/{id}", goalId)
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ownerId").value(manager.getId()))
            .andExpect(jsonPath("$.ownerLabel").value(manager.getDisplayName()))
            .andExpect(jsonPath("$.targetValue").value(100000.00));

        mockMvc.perform(get("/api/goals")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(put("/api/goals/{id}", goalId)
                .header("X-Workspace-Id", workspace.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(goalBody(null, "125000.00", "USD"))
                .session(session)
                .with(csrf().asHeader()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ownerId").doesNotExist())
            .andExpect(jsonPath("$.ownerLabel").doesNotExist())
            .andExpect(jsonPath("$.targetValue").value(125000.00));

        mockMvc.perform(delete("/api/goals/{id}", goalId)
                .header("X-Workspace-Id", workspace.getId())
                .session(session)
                .with(csrf().asHeader()))
            .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/goals/{id}", goalId)
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isNotFound());
    }

    @Test
    void readerCanListButCannotCreate() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace workspace = newWorkspace();
        User manager = newMember(workspace, "admin");
        createGoal(login(manager.getUsername()), workspace, goalBody(null, "1000.00", "USD"));

        User reader = newMember(workspace, "member");
        WorkspaceRole role = new WorkspaceRole();
        role.setWorkspaceId(workspace.getId());
        role.setName("Goal Reader " + UUID.randomUUID().toString().substring(0, 8));
        roleMapper.insertRole(role);
        roleMapper.insertPermissions(workspace.getId(), role.getId(), List.of("GOAL_READ"));
        workspaceMapper.setMemberCustomRole(workspace.getId(), reader.getId(), role.getId());
        MockHttpSession readerSession = login(reader.getUsername());

        mockMvc.perform(get("/api/goals")
                .header("X-Workspace-Id", workspace.getId())
                .session(readerSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(get("/api/permissions/effective")
                .header("X-Workspace-Id", workspace.getId())
                .session(readerSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@ == 'GOAL_READ')]").isNotEmpty())
            .andExpect(jsonPath("$[?(@ == 'GOAL_MANAGE')]").isEmpty());

        mockMvc.perform(post("/api/goals")
                .header("X-Workspace-Id", workspace.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(goalBody(reader.getId(), "1000.00", "USD"))
                .session(readerSession)
                .with(csrf().asHeader()))
            .andExpect(status().isForbidden());
    }

    @Test
    void duplicateInvalidOwnerAndCrossWorkspaceAccessAreRejected() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace workspace = newWorkspace();
        User manager = newMember(workspace, "admin");
        MockHttpSession session = login(manager.getUsername());
        int goalId = createGoal(session, workspace, goalBody(null, "1000.00", "USD"));

        mockMvc.perform(post("/api/goals")
                .header("X-Workspace-Id", workspace.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(goalBody(null, "2000.00", "USD"))
                .session(session)
                .with(csrf().asHeader()))
            .andExpect(status().isConflict());

        Workspace other = newWorkspace();
        User outsider = newMember(other, "admin");
        mockMvc.perform(post("/api/goals")
                .header("X-Workspace-Id", workspace.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(goalBody(outsider.getId(), "1000.00", "USD"))
                .session(session)
                .with(csrf().asHeader()))
            .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/goals")
                .header("X-Workspace-Id", other.getId())
                .session(session))
            .andExpect(status().isForbidden());

        workspaceMapper.addMember(other.getId(), manager.getId(), "admin");
        mockMvc.perform(get("/api/goals")
                .header("X-Workspace-Id", other.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/api/goals/{id}", goalId)
                .header("X-Workspace-Id", other.getId())
                .session(session))
            .andExpect(status().isNotFound());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentWorkspaceGoalCreationReturnsOneConflict() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace workspace = newWorkspace();
        User manager = newMember(workspace, "admin");
        MockHttpSession firstSession = login(manager.getUsername());
        MockHttpSession secondSession = login(manager.getUsername());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(() -> concurrentCreateStatus(
                    firstSession, workspace, ready, start));
            Future<Integer> second = executor.submit(() -> concurrentCreateStatus(
                    secondSession, workspace, ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            List<Integer> statuses = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))
                    .stream()
                    .sorted()
                    .toList();
            assertEquals(List.of(201, 409), statuses);
        } finally {
            start.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
            jdbcTemplate.update("DELETE FROM report_goal WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM workspace_member WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", manager.getId());
        }
    }

    private int concurrentCreateStatus(
            MockHttpSession session,
            Workspace workspace,
            CountDownLatch ready,
            CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent goal requests did not start");
        }
        return mockMvc.perform(post("/api/goals")
                .header("X-Workspace-Id", workspace.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(goalBody(null, "1000.00", "USD"))
                .session(session)
                .with(csrf().asHeader()))
            .andReturn()
            .getResponse()
            .getStatus();
    }

    private int createGoal(MockHttpSession session, Workspace workspace, String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/goals")
                .header("X-Workspace-Id", workspace.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .session(session)
                .with(csrf().asHeader()))
            .andExpect(status().isCreated())
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asInt();
    }

    private static String goalBody(Integer ownerId, String target, String currency) {
        String owner = ownerId == null ? "null" : ownerId.toString();
        return """
            {
              "ownerId": %s,
              "metric": "won_revenue",
              "periodType": "month",
              "periodStart": "2026-07-01",
              "targetValue": %s,
              "currency": "%s"
            }
            """.formatted(owner, target, currency);
    }

    private Workspace newWorkspace() {
        String slug = "goal-" + UUID.randomUUID().toString().substring(0, 8);
        Workspace workspace = new Workspace();
        workspace.setName(slug);
        workspace.setSlug(slug);
        workspaceMapper.insert(workspace);
        return workspace;
    }

    private User newMember(Workspace workspace, String role) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername("goal_" + suffix);
        user.setDisplayName("Goal " + suffix);
        user.setEmail(suffix + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setTimezone("UTC");
        userMapper.insert(user);
        workspaceMapper.addMember(workspace.getId(), user.getId(), role);
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
        assertNotNull(session);
        return session;
    }
}
