package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * Full-stack HTTP coverage for the deal analytics aggregate endpoints and their range validation.
 */
@SpringBootTest
@Transactional
class DealAnalyticsIntegrationTest {

    private static final String PASSWORD = "Analytics-Test-Pw1!";

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
    void aggregateEndpointsBindParametersAndSerializeResponses() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace workspace = newWorkspace();
        User user = newMember(workspace);
        MockHttpSession session = login(user.getUsername());

        mockMvc.perform(get("/api/deals/kpis")
                .header("X-Workspace-Id", workspace.getId())
                .param("currency", " ")
                .param("range", "30d")
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.wonRevenue").value(0.0))
            .andExpect(jsonPath("$.newPipeline").value(0.0))
            .andExpect(jsonPath("$.wonSeries.length()").value(12))
            .andExpect(jsonPath("$.newPipelineSeries.length()").value(12))
            .andExpect(jsonPath("$.winRateSeries.length()").value(12))
            .andExpect(jsonPath("$.avgCycleSeries.length()").value(12));

        mockMvc.perform(get("/api/deals/pipeline-value")
                .header("X-Workspace-Id", workspace.getId())
                .param("range", "12m")
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/api/deals/aging")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/api/deals/top")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.topOpen.length()").value(0))
            .andExpect(jsonPath("$.topWon.length()").value(0));
    }

    @Test
    void rangeValidationAndAuthenticationFailClosed() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace workspace = newWorkspace();
        User user = newMember(workspace);
        MockHttpSession session = login(user.getUsername());

        mockMvc.perform(get("/api/deals/kpis")
                .header("X-Workspace-Id", workspace.getId())
                .param("range", "7d")
                .session(session))
            .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/deals/pipeline-value")
                .header("X-Workspace-Id", workspace.getId())
                .param("range", "all")
                .session(session))
            .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/deals/kpis"))
            .andExpect(status().is4xxClientError());
    }

    @Test
    void nonMemberWorkspaceIsRejected() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace memberWorkspace = newWorkspace();
        Workspace foreignWorkspace = newWorkspace();
        User user = newMember(memberWorkspace);
        MockHttpSession session = login(user.getUsername());

        mockMvc.perform(get("/api/deals/kpis")
                .header("X-Workspace-Id", foreignWorkspace.getId())
                .session(session))
            .andExpect(status().isForbidden());
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
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Workspace workspace = new Workspace();
        workspace.setName("Analytics " + suffix);
        workspace.setSlug("analytics-" + suffix);
        workspaceMapper.insert(workspace);
        return workspace;
    }

    private User newMember(Workspace workspace) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername("analytics_" + suffix);
        user.setDisplayName("Analytics " + suffix);
        user.setEmail(suffix + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setTimezone("UTC");
        userMapper.insert(user);
        workspaceMapper.addMember(workspace.getId(), user.getId(), "member");
        return user;
    }
}
