package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.WorkspaceCookie;

/** Proves stale browser selections cannot redirect note writes across organizations after revocation. */
@SpringBootTest
class StaleWorkspaceRecoveryIntegrationTest {
    private static final String PASSWORD = "Stale-Workspace-Pw1!";

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private UserMapper userMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private NoteMapper noteMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private TenantContext tenantContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        clearContexts();
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(springSecurityFilterChain)
            .build();
    }

    @AfterEach
    void clearContexts() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        tenantContext.clear();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void revokedWorkspaceNoteWriteIsRejectedBeforeRecovery(boolean matchingHeader) throws Exception {
        Workspace workspaceA = newWorkspaceInNewOrganization();
        Workspace workspaceB = newWorkspaceInNewOrganization();
        User author = newUser();
        User reader = newUser();
        workspaceMapper.addMember(workspaceA.getId(), author.getId(), "member");
        workspaceMapper.addMember(workspaceB.getId(), author.getId(), "member");
        workspaceMapper.addMember(workspaceA.getId(), reader.getId(), "member");
        workspaceMapper.setLastActiveWorkspaceId(author.getId(), workspaceB.getId());
        MockHttpSession authorSession = login(author);
        MockHttpSession readerSession = login(reader);

        RequestContextHolder.resetRequestAttributes();
        assertEquals(1, workspaceMapper.removeMember(workspaceB.getId(), author.getId()));
        assertNull(workspaceMapper.getRole(workspaceB.getId(), author.getId()));

        Cookie staleCookie = new Cookie(WorkspaceCookie.NAME, Integer.toString(workspaceB.getId()));
        MockHttpServletRequestBuilder request = post("/api/notes")
            .cookie(staleCookie)
            .session(authorSession)
            .with(csrf().asHeader())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"content\":\"B-confidential\",\"visibility\":\"workspace\"}");
        if (matchingHeader) {
            request.header("X-Workspace-Id", workspaceB.getId());
        }
        mockMvc.perform(request)
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.message").value("Not a member of workspace " + workspaceB.getId()))
            .andExpect(cookie().doesNotExist(WorkspaceCookie.NAME));

        RequestContextHolder.resetRequestAttributes();
        assertTrue(noteMapper.getAllNotes(workspaceA.getId()).isEmpty());
        assertTrue(noteMapper.getAllNotes(workspaceB.getId()).isEmpty());
        assertEquals(workspaceB.getId(), workspaceMapper.getLastActiveWorkspaceId(author.getId()));

        mockMvc.perform(get("/api/notes/page")
                .header("X-Workspace-Id", workspaceA.getId())
                .param("q", "B-confidential")
                .session(readerSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isEmpty())
            .andExpect(jsonPath("$.total").value(0));

        mockMvc.perform(get("/api/auth/me")
                .cookie(staleCookie)
                .header("X-Workspace-Id", workspaceB.getId())
                .session(authorSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(author.getId()));

        mockMvc.perform(get("/api/workspaces")
                .cookie(staleCookie)
                .header("X-Workspace-Id", workspaceB.getId())
                .session(authorSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.activeWorkspaceId").value(workspaceA.getId()))
            .andExpect(cookie().value(WorkspaceCookie.NAME, Integer.toString(workspaceA.getId())));

        mockMvc.perform(post("/api/notes")
                .cookie(new Cookie(WorkspaceCookie.NAME, Integer.toString(workspaceA.getId())))
                .header("X-Workspace-Id", workspaceA.getId())
                .session(authorSession)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"Explicitly selected A\",\"visibility\":\"workspace\"}"))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/notes/page")
                .header("X-Workspace-Id", workspaceA.getId())
                .param("q", "Explicitly selected A")
                .session(readerSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1));
    }

    private MockHttpSession login(User user) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + user.getUsername()
                    + "\",\"password\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
        return assertInstanceOf(MockHttpSession.class, result.getRequest().getSession(false));
    }

    private Workspace newWorkspaceInNewOrganization() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization organization = new Organization();
        organization.setName("Stale workspace org " + suffix);
        organization.setSlug("stale-workspace-org-" + suffix);
        organizationMapper.insert(organization);
        Workspace workspace = new Workspace();
        workspace.setName("Stale workspace " + suffix);
        workspace.setSlug("stale-workspace-" + suffix);
        workspace.setOrgId(organization.getId());
        workspaceMapper.insert(workspace);
        return workspace;
    }

    private User newUser() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername("stale_workspace_" + suffix);
        user.setDisplayName("Stale workspace user " + suffix);
        user.setEmail(suffix + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setTimezone("UTC");
        userMapper.insert(user);
        return user;
    }
}
