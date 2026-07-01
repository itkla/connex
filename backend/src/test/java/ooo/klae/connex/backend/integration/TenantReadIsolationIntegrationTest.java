package ooo.klae.connex.backend.integration;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneOffset;
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

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.ShareMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

/**
 * Full-stack read-isolation backstop (#89): exercises the real controller →
 * security filter chain → {@code TenantResolutionInterceptor} → service → mapper
 * path over HTTP (MockMvc with the actual login + session), asserting a member of
 * one workspace cannot read another workspace's record and cannot pin a workspace
 * they don't belong to. Complements the mapper-unit isolation tests and the
 * read-scope architecture test.
 */
@SpringBootTest
@Transactional
class TenantReadIsolationIntegrationTest {

    private static final String PASSWORD = "Tenant-Test-Pw1!";

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private UserMapper userMapper;
    @Autowired private CompanyMapper companyMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ShareMapper shareMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(springSecurityFilterChain)
            .build();
    }

    @Test
    void memberCannotReadAnotherWorkspacesCompanyOverHttp() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace wsA = newWorkspace();
        Workspace wsB = newWorkspace();
        User alice = newMember(wsA);
        User bob = newMember(wsB);
        Company companyA = newCompany(wsA);

        MockHttpSession aliceSession = login(alice.getUsername());
        mockMvc.perform(get("/api/companies/" + companyA.getId())
                .header("X-Workspace-Id", wsA.getId())
                .session(aliceSession))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/companies/" + companyA.getId())
                .header("X-Workspace-Id", wsB.getId())
                .session(aliceSession))
            .andExpect(status().isForbidden());

        MockHttpSession bobSession = login(bob.getUsername());
        mockMvc.perform(get("/api/companies/" + companyA.getId())
                .header("X-Workspace-Id", wsB.getId())
                .session(bobSession))
            .andExpect(status().isNotFound());
    }

    @Test
    void sharedCompanyBecomesReadableOnlyInTheGrantedWorkspace() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace wsA = newWorkspace();
        Workspace wsB = newWorkspace();
        User alice = newMember(wsA);
        User bob = newMember(wsB);
        Company companyA = newCompany(wsA);

        MockHttpSession bobSession = login(bob.getUsername());
        mockMvc.perform(get("/api/companies/" + companyA.getId())
                .header("X-Workspace-Id", wsB.getId())
                .session(bobSession))
            .andExpect(status().isNotFound());

        shareMapper.shareCompany(companyA.getId(), wsB.getId(), alice.getId(), false);

        mockMvc.perform(get("/api/companies/" + companyA.getId())
                .header("X-Workspace-Id", wsB.getId())
                .session(bobSession))
            .andExpect(status().isOk());
    }

    @Test
    void mapReplayIsScopedToTheActiveWorkspace() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace wsA = newWorkspace();
        Workspace wsB = newWorkspace();
        User alice = newMember(wsA);
        Company companyA = newCompany(wsA);
        Company companyB = newCompany(wsB);

        LocalDate to = LocalDate.now(ZoneOffset.UTC);
        LocalDate from = to.minusWeeks(8);
        String range = "?from=" + from + "&to=" + to + "&granularity=weekly";

        MockHttpSession aliceSession = login(alice.getUsername());

        mockMvc.perform(get("/api/map/replay" + range)
                .header("X-Workspace-Id", wsA.getId())
                .session(aliceSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.frames[*].companies[*].id", hasItem(companyA.getId())))
            .andExpect(jsonPath("$.frames[*].companies[*].id", not(hasItem(companyB.getId()))));

        mockMvc.perform(get("/api/map/replay" + range)
                .header("X-Workspace-Id", wsB.getId())
                .session(aliceSession))
            .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/map/replay" + range))
            .andExpect(status().is4xxClientError());

        mockMvc.perform(get("/api/map/replay?from=" + to + "&to=" + from + "&granularity=weekly")
                .header("X-Workspace-Id", wsA.getId())
                .session(aliceSession))
            .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/map/replay?from=" + from + "&to=" + to + "&granularity=daily")
                .header("X-Workspace-Id", wsA.getId())
                .session(aliceSession))
            .andExpect(status().isBadRequest());
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

    private Company newCompany(Workspace workspace) {
        Company company = new Company();
        company.setName("Company " + UUID.randomUUID().toString().substring(0, 8));
        company.setWorkspaceId(workspace.getId());
        companyMapper.insert(company);
        return company;
    }
}
