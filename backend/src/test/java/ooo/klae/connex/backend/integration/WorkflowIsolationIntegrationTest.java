package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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

import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

/** Exercises workflow authorization and tenant isolation through the real HTTP stack. */
@SpringBootTest
@Transactional
class WorkflowIsolationIntegrationTest {

    private static final String PASSWORD = "Workflow-Test-Pw1!";

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private UserMapper userMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(springSecurityFilterChain)
            .build();
    }

    @Test
    void lifecycleHttpContractEnforcesTenantRbacAuthenticationAndCsrf() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace ownerWorkspace = newWorkspace();
        Workspace foreignWorkspace = newWorkspace();
        User owner = newMember(ownerWorkspace, "owner");
        User reader = newMember(ownerWorkspace, "member");
        User foreignOwner = newMember(foreignWorkspace, "owner");
        MockHttpSession ownerSession = login(owner.getUsername());

        MvcResult created = mockMvc.perform(post("/api/workflows")
                .header("X-Workspace-Id", ownerWorkspace.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody())
                .session(ownerSession)
                .with(csrf().asHeader()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("Isolation workflow"))
            .andReturn();
        int workflowId = objectMapper.readTree(
            created.getResponse().getContentAsString()).get("id").intValue();

        mockMvc.perform(get("/api/workflows/" + workflowId)
                .header("X-Workspace-Id", ownerWorkspace.getId())
                .session(ownerSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(workflowId));

        MockHttpSession foreignSession = login(foreignOwner.getUsername());
        mockMvc.perform(get("/api/workflows/" + workflowId)
                .header("X-Workspace-Id", foreignWorkspace.getId())
                .session(foreignSession))
            .andExpect(status().isNotFound());

        MockHttpSession readerSession = login(reader.getUsername());
        mockMvc.perform(get("/api/workflows/" + workflowId)
                .header("X-Workspace-Id", ownerWorkspace.getId())
                .session(readerSession))
            .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/workflows/" + workflowId)
                .header("X-Workspace-Id", ownerWorkspace.getId()))
            .andExpect(status().is4xxClientError());

        mockMvc.perform(post("/api/workflows")
                .header("X-Workspace-Id", ownerWorkspace.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody())
                .session(ownerSession))
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
        String slug = "workflow-" + UUID.randomUUID().toString().substring(0, 8);
        Workspace workspace = new Workspace();
        workspace.setName(slug);
        workspace.setSlug(slug);
        workspaceMapper.insert(workspace);
        return workspace;
    }

    private User newMember(Workspace workspace, String role) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername("workflow_" + suffix);
        user.setDisplayName("Workflow " + suffix);
        user.setEmail(suffix + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setTimezone("UTC");
        userMapper.insert(user);
        workspaceMapper.addMember(workspace.getId(), user.getId(), role);
        return user;
    }

    private static String createBody() {
        return """
            {
              "name":"Isolation workflow",
              "recordType":"deal",
              "executionMode":"user",
              "definition":{"schemaVersion":1,"entryNodeId":null,"nodes":[],"edges":[]},
              "canvas":{"positions":{},"viewport":{"x":0,"y":0,"zoom":1}}
            }
            """;
    }
}
