package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

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
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;

import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

@SpringBootTest(properties = "connex.record-creation.guided-cutover-enabled=false")
@Transactional
class LegacyRecordCreationConflictIntegrationTest {
    private static final String PASSWORD = "Legacy-Conflict-Pw1!";
    private static final String REQUIRED_MESSAGE =
        "Possible duplicates must be reviewed before creation";
    private static final String STALE_MESSAGE =
        "Duplicate candidates changed before creation; review them again";

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private RoleMapper roleMapper;
    @Autowired private PersonMapper personMapper;
    @Autowired private CompanyMapper companyMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final List<Integer> committedWorkspaceIds = new ArrayList<>();
    private final List<Integer> committedOrganizationIds = new ArrayList<>();

    private MockMvc mockMvc;
    private MockHttpSession session;
    private Workspace workspace;

    @BeforeEach
    void setUp() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(springSecurityFilterChain)
            .build();
        String suffix = unique();
        Organization organization = new Organization();
        organization.setName("Legacy conflict " + suffix);
        organization.setSlug("legacy-conflict-" + suffix);
        committed(() -> organizationMapper.insert(organization));
        committedOrganizationIds.add(organization.getId());
        workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("Legacy conflict " + suffix);
        workspace.setSlug("legacy-conflict-" + suffix);
        committed(() -> workspaceMapper.insert(workspace));
        committedWorkspaceIds.add(workspace.getId());
        User actor = new User();
        actor.setUsername("legacy_conflict_" + suffix);
        actor.setDisplayName("Legacy conflict " + suffix);
        actor.setEmail(suffix + "@example.com");
        actor.setPasswordHash(passwordEncoder.encode(PASSWORD));
        actor.setTimezone("UTC");
        userMapper.insert(actor);
        workspaceMapper.addMember(workspace.getId(), actor.getId(), "member");
        WorkspaceRole role = new WorkspaceRole();
        role.setWorkspaceId(workspace.getId());
        role.setName("Legacy creator " + suffix);
        roleMapper.insertRole(role);
        roleMapper.insertPermissions(
            workspace.getId(), role.getId(), List.of("PERSON_CREATE", "COMPANY_CREATE"));
        workspaceMapper.setMemberCustomRole(workspace.getId(), actor.getId(), role.getId());
        session = login(actor.getUsername());
    }

    @AfterTransaction
    void removeCommittedControlFixtures() {
        committed(() -> {
            committedWorkspaceIds.forEach(id ->
                jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", id));
            committedOrganizationIds.forEach(id ->
                jdbcTemplate.update("DELETE FROM organization WHERE id = ?", id));
            return null;
        });
        committedWorkspaceIds.clear();
        committedOrganizationIds.clear();
    }

    @Test
    void personDuplicateConflictRetainsLegacyBodyWhileCutoverIsOff() throws Exception {
        Person existing = new Person();
        existing.setWorkspaceId(workspace.getId());
        existing.setName("Legacy duplicate person");
        personMapper.insert(existing);

        perform("/api/persons", """
            {"name":" legacy duplicate person "}
            """)
            .andExpect(status().isConflict())
            .andExpect(content().json(
                "{\"message\":\"" + REQUIRED_MESSAGE + "\"}",
                JsonCompareMode.STRICT))
            .andExpect(jsonPath("$.message").value(REQUIRED_MESSAGE))
            .andExpect(jsonPath("$.code").doesNotExist())
            .andExpect(jsonPath("$.fieldErrors").doesNotExist());

        assertEquals(1, count("person"));
    }

    @Test
    void companyDuplicateConflictRetainsLegacyBodyWhileCutoverIsOff() throws Exception {
        Company existing = new Company();
        existing.setWorkspaceId(workspace.getId());
        existing.setName("Legacy duplicate company");
        companyMapper.insert(existing);

        perform("/api/companies", """
            {"name":" legacy duplicate company "}
            """)
            .andExpect(status().isConflict())
            .andExpect(content().json(
                "{\"message\":\"" + REQUIRED_MESSAGE + "\"}",
                JsonCompareMode.STRICT))
            .andExpect(jsonPath("$.message").value(REQUIRED_MESSAGE))
            .andExpect(jsonPath("$.code").doesNotExist())
            .andExpect(jsonPath("$.fieldErrors").doesNotExist());

        assertEquals(1, count("company"));
    }

    @Test
    void stalePersonDuplicateTokenRetainsExactLegacyBodyWhileCutoverIsOff() throws Exception {
        Person existing = new Person();
        existing.setWorkspaceId(workspace.getId());
        existing.setName("Legacy stale duplicate person");
        personMapper.insert(existing);

        perform("/api/persons", """
            {"name":" legacy stale duplicate person ","duplicateReviewToken":"%s"}
            """.formatted("f".repeat(64)))
            .andExpect(status().isConflict())
            .andExpect(content().string("{\"message\":\"" + STALE_MESSAGE + "\"}"))
            .andExpect(jsonPath("$.code").doesNotExist())
            .andExpect(jsonPath("$.fieldErrors").doesNotExist());

        assertEquals(1, count("person"));
    }

    private org.springframework.test.web.servlet.ResultActions perform(String path, String body)
            throws Exception {
        return mockMvc.perform(post(path)
            .with(csrf().asHeader())
            .header("X-Workspace-Id", workspace.getId())
            .session(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
    }

    private MockHttpSession login(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "username", username, "password", PASSWORD))))
            .andExpect(status().isOk())
            .andReturn();
        MockHttpSession authenticated = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(authenticated);
        return authenticated;
    }

    private <T> T committed(Supplier<T> work) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction.execute(status -> work.get());
    }

    private int count(String table) {
        return switch (table) {
            case "person" -> personMapper.getAllPersons(workspace.getId()).size();
            case "company" -> companyMapper.getAllCompanies(workspace.getId()).size();
            default -> throw new IllegalArgumentException("Unexpected table");
        };
    }

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
