package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Full security-chain proof for saved-view visibility, tenancy, pins, defaults, and CSRF. */
@SpringBootTest
@Transactional
class SavedViewIsolationIntegrationTest {
    private static final String PASSWORD = "Views-Test-Pw1!";

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
    void sharingAndTenantResolutionDoNotLeakInaccessibleViews() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace workspaceA = newWorkspace();
        Workspace workspaceB = newWorkspace();
        User owner = newMember(workspaceA);
        User recipient = newMember(workspaceA);
        User outsider = newMember(workspaceB);
        MockHttpSession ownerSession = login(owner.getUsername());
        MockHttpSession recipientSession = login(recipient.getUsername());
        MockHttpSession outsiderSession = login(outsider.getUsername());

        int privateId = create(ownerSession, workspaceA, "Private", "private");
        int sharedId = create(ownerSession, workspaceA, "Shared", "workspace");

        mockMvc.perform(get("/api/saved-views/{id}", privateId)
                .header("X-Workspace-Id", workspaceA.getId())
                .session(recipientSession))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
            .andExpect(jsonPath("$.message").value("Saved view not found"));
        mockMvc.perform(get("/api/saved-views/{id}", sharedId)
                .header("X-Workspace-Id", workspaceA.getId())
                .session(recipientSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(sharedId))
            .andExpect(jsonPath("$.ownedByCurrentUser").value(false));
        mockMvc.perform(put("/api/saved-views/{id}", sharedId)
                .header("X-Workspace-Id", workspaceA.getId())
                .session(ownerSession)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody("Renamed shared", "workspace", "latest")))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/saved-views/{id}", sharedId)
                .header("X-Workspace-Id", workspaceA.getId())
                .session(recipientSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Renamed shared"))
            .andExpect(jsonPath("$.config.query").value("latest"));
        mockMvc.perform(get("/api/saved-views/{id}", sharedId)
                .header("X-Workspace-Id", workspaceB.getId())
                .session(outsiderSession))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
            .andExpect(jsonPath("$.message").value("Saved view not found"));
        mockMvc.perform(get("/api/saved-views/{id}", sharedId)
                .header("X-Workspace-Id", workspaceB.getId())
                .session(ownerSession))
            .andExpect(status().isForbidden());
    }

    @Test
    void pinsAndDefaultsArePerUserAndCascadingDeletionReturnsNullDefault() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace workspace = newWorkspace();
        User owner = newMember(workspace);
        User recipient = newMember(workspace);
        MockHttpSession ownerSession = login(owner.getUsername());
        MockHttpSession recipientSession = login(recipient.getUsername());
        int sharedId = create(ownerSession, workspace, "Shared preferences", "workspace");

        mockMvc.perform(put("/api/saved-views/{id}/pin", sharedId)
                .header("X-Workspace-Id", workspace.getId())
                .session(recipientSession)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pinned").value(true))
            .andExpect(jsonPath("$.pinPosition").value(0));
        mockMvc.perform(put("/api/saved-views/defaults/company")
                .header("X-Workspace-Id", workspace.getId())
                .session(recipientSession)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"savedViewId\":" + sharedId + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.view.id").value(sharedId))
            .andExpect(jsonPath("$.view.default").value(true));

        mockMvc.perform(get("/api/saved-views/pins")
                .header("X-Workspace-Id", workspace.getId())
                .session(ownerSession))
            .andExpect(status().isOk())
            .andExpect(content().json("[]"));
        mockMvc.perform(get("/api/saved-views/defaults/company")
                .header("X-Workspace-Id", workspace.getId())
                .session(ownerSession))
            .andExpect(status().isOk())
            .andExpect(content().json("{\"view\":null}"));

        mockMvc.perform(delete("/api/saved-views/{id}", sharedId)
                .header("X-Workspace-Id", workspace.getId())
                .session(ownerSession)
                .with(csrf().asHeader()))
            .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/saved-views/defaults/company")
                .header("X-Workspace-Id", workspace.getId())
                .session(recipientSession))
            .andExpect(status().isOk())
            .andExpect(content().json("{\"view\":null}"));
    }

    @Test
    void pinsAndDefaultsAreIsolatedForTheSameUserAcrossWorkspaces() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace workspaceA = newWorkspace();
        Workspace workspaceB = newWorkspace();
        User user = newMember(workspaceA);
        workspaceMapper.addMember(workspaceB.getId(), user.getId(), "member");
        MockHttpSession session = login(user.getUsername());
        int viewA = create(session, workspaceA, "Workspace A", "private");
        int viewB = create(session, workspaceB, "Workspace B", "private");

        setPreferences(session, workspaceA, viewA);
        mockMvc.perform(get("/api/saved-views/pins")
                .header("X-Workspace-Id", workspaceB.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(content().json("[]"));
        mockMvc.perform(get("/api/saved-views/defaults/company")
                .header("X-Workspace-Id", workspaceB.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(content().json("{\"view\":null}"));

        setPreferences(session, workspaceB, viewB);
        assertPreferences(session, workspaceA, viewA);
        assertPreferences(session, workspaceB, viewB);
    }

    @Test
    void authenticationWorkspaceMembershipRequiredParameterAndCsrfAreEnforced() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace memberWorkspace = newWorkspace();
        Workspace foreignWorkspace = newWorkspace();
        User member = newMember(memberWorkspace);
        MockHttpSession session = login(member.getUsername());

        mockMvc.perform(get("/api/saved-views").param("recordType", "company"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/saved-views")
                .header("X-Workspace-Id", memberWorkspace.getId())
                .session(session))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/saved-views")
                .param("recordType", "company")
                .header("X-Workspace-Id", foreignWorkspace.getId())
                .session(session))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/saved-views")
                .header("X-Workspace-Id", memberWorkspace.getId())
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody("No CSRF", "private")))
            .andExpect(status().isForbidden());
    }

    private int create(
            MockHttpSession session, Workspace workspace, String name, String visibility) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/saved-views")
                .header("X-Workspace-Id", workspace.getId())
                .session(session)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(name, visibility)))
            .andExpect(status().isCreated())
            .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.path("id").asInt();
    }

    private void setPreferences(MockHttpSession session, Workspace workspace, int viewId) throws Exception {
        mockMvc.perform(put("/api/saved-views/{id}/pin", viewId)
                .header("X-Workspace-Id", workspace.getId())
                .session(session)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk());
        mockMvc.perform(put("/api/saved-views/defaults/company")
                .header("X-Workspace-Id", workspace.getId())
                .session(session)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"savedViewId\":" + viewId + "}"))
            .andExpect(status().isOk());
    }

    private void assertPreferences(MockHttpSession session, Workspace workspace, int viewId) throws Exception {
        mockMvc.perform(get("/api/saved-views/pins")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(viewId))
            .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/api/saved-views/defaults/company")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.view.id").value(viewId));
    }

    private String createBody(String name, String visibility) throws Exception {
        return createBody(name, visibility, "");
    }

    private String createBody(String name, String visibility, String query) throws Exception {
        var config = objectMapper.createObjectNode();
        config.put("version", 1);
        config.set("filters", objectMapper.createObjectNode());
        config.put("query", query);
        var request = objectMapper.createObjectNode();
        request.put("recordType", "company");
        request.put("name", name);
        request.put("visibility", visibility);
        request.set("config", config);
        return objectMapper.writeValueAsString(request);
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

    private Workspace newWorkspace() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Workspace workspace = new Workspace();
        workspace.setName("Workspace " + suffix);
        workspace.setSlug("workspace-" + suffix);
        workspaceMapper.insert(workspace);
        return workspace;
    }

    private User newMember(Workspace workspace) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername("views_" + suffix);
        user.setDisplayName("Views " + suffix);
        user.setEmail(suffix + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setTimezone("UTC");
        userMapper.insert(user);
        workspaceMapper.addMember(workspace.getId(), user.getId(), "member");
        return user;
    }
}
