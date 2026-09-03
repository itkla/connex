package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.Filter;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.IdentityBackfillTransaction;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.tenant.TenantContext;

@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DuplicateReviewTenantIsolationIntegrationTest {

    private static final String PASSWORD = "Duplicate-Isolation-Pw1!";

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private PersonMapper personMapper;
    @Autowired private IdentityBackfillTransaction identityBackfillTransaction;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TenantContext tenantContext;

    private final List<Organization> organizations = new ArrayList<>();
    private final List<Workspace> workspaces = new ArrayList<>();
    private final List<User> users = new ArrayList<>();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(springSecurityFilterChain)
            .build();
    }

    @AfterEach
    void tearDown() {
        clearDirectAuthentication();
        for (Workspace workspace : workspaces.reversed()) {
            int workspaceId = workspace.getId();
            jdbcTemplate.update(
                "DELETE FROM duplicate_review_decision WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM identity_collision WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM person_identity WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM person WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM workspace_member WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", workspaceId);
        }
        for (User user : users.reversed()) {
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", user.getId());
        }
        for (Organization organization : organizations.reversed()) {
            jdbcTemplate.update("DELETE FROM organization WHERE id = ?", organization.getId());
        }
    }

    @Test
    void otherWorkspaceOrOrganizationCannotListDismissOrReopenForeignPair() throws Exception {
        Organization owningOrganization = organization("Owning");
        Workspace owningWorkspace = workspace(owningOrganization, "Owning");
        User owningMember = member(owningWorkspace);
        authenticateAs(owningMember, owningWorkspace);
        Person first = person(owningWorkspace, "Tenant Secret First");
        Person second = person(owningWorkspace, "Tenant Secret Second");
        identity(first, "secret@example.com");
        identity(second, "secret@example.com");
        identityBackfillTransaction.rebuildCollisionReport("default", owningWorkspace.getId());
        clearDirectAuthentication();
        String fingerprint = java.util.Objects.requireNonNull(jdbcTemplate.queryForObject(
            """
            SELECT evidence_fingerprint
            FROM duplicate_review_decision
            WHERE workspace_id = ? AND is_current = TRUE
            """,
            String.class,
            owningWorkspace.getId()));

        Workspace siblingWorkspace = workspace(owningOrganization, "Sibling");
        assertCannotReachPair(
            owningWorkspace, siblingWorkspace, member(siblingWorkspace), first, second, fingerprint);

        Workspace foreignWorkspace = workspace(organization("Foreign"), "Foreign");
        assertCannotReachPair(
            owningWorkspace, foreignWorkspace, member(foreignWorkspace), first, second, fingerprint);
    }

    private void assertCannotReachPair(
            Workspace owningWorkspace,
            Workspace callerWorkspace,
            User caller,
            Person first,
            Person second,
            String fingerprint) throws Exception {
        MockHttpSession session = login(caller);

        mockMvc.perform(get("/api/duplicate-reviews")
                .header("X-Workspace-Id", callerWorkspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(0))
            .andExpect(content().string(Matchers.not(
                Matchers.containsString("Tenant Secret"))));

        mockMvc.perform(get("/api/duplicate-reviews")
                .header("X-Workspace-Id", owningWorkspace.getId())
                .session(session))
            .andExpect(status().isForbidden())
            .andExpect(content().string(Matchers.not(
                Matchers.containsString("Tenant Secret"))));

        for (String action : List.of("dismiss", "reopen")) {
            mockMvc.perform(post("/api/duplicate-reviews/" + action)
                    .with(csrf().asHeader())
                    .header("X-Workspace-Id", callerWorkspace.getId())
                    .session(session)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "recordType": "person",
                          "kind": "email",
                          "recordIdA": %d,
                          "recordIdB": %d,
                          "evidenceFingerprint": "%s",
                          "note": null
                        }
                        """.formatted(first.getId(), second.getId(), fingerprint)))
                .andExpect(status().isConflict())
                .andExpect(content().string(Matchers.not(
                    Matchers.containsString("Tenant Secret"))));
        }
    }

    private Organization organization(String prefix) {
        String suffix = suffix();
        Organization organization = new Organization();
        organization.setName(prefix + " duplicate isolation " + suffix);
        organization.setSlug(prefix.toLowerCase() + "-duplicate-isolation-" + suffix);
        organizationMapper.insert(organization);
        organizations.add(organization);
        return organization;
    }

    private Workspace workspace(Organization organization, String prefix) {
        String suffix = suffix();
        Workspace workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName(prefix + " duplicate isolation " + suffix);
        workspace.setSlug(prefix.toLowerCase() + "-duplicate-isolation-" + suffix);
        workspaceMapper.insert(workspace);
        workspaces.add(workspace);
        return workspace;
    }

    private User member(Workspace workspace) {
        String suffix = suffix();
        User user = new User();
        user.setUsername("duplicate_isolation_" + suffix);
        user.setDisplayName("Duplicate isolation " + suffix);
        user.setEmail(suffix + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setTimezone("UTC");
        userMapper.insert(user);
        workspaceMapper.addMember(workspace.getId(), user.getId(), "member");
        users.add(user);
        return user;
    }

    private Person person(Workspace workspace, String name) {
        Person person = new Person();
        person.setWorkspaceId(workspace.getId());
        person.setName(name);
        personMapper.insert(person);
        return person;
    }

    private void identity(Person person, String value) {
        jdbcTemplate.update(
            """
            INSERT INTO person_identity (
              workspace_id, person_id, kind, `value`, normalized_value,
              source_system, source_channel, acquired_at)
            VALUES (?, ?, 'email', ?, ?, 'csv_import', 'person.email', CURRENT_TIMESTAMP)
            """,
            person.getWorkspaceId(), person.getId(), value, value);
    }

    private void authenticateAs(User user, Workspace workspace) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession().setAttribute(
            SessionSecurityService.AUTHENTICATED_AT_ATTR, System.currentTimeMillis());
        request.getSession().setAttribute(
            SessionSecurityService.AUTHENTICATED_USER_ATTR, user.getId());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        tenantContext.set(
            workspace.getId(), workspace.getOrgId(), user.getId(), "member", null);
    }

    private void clearDirectAuthentication() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        tenantContext.clear();
    }

    private MockHttpSession login(User user) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + user.getUsername()
                    + "\",\"password\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(session);
        return session;
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
