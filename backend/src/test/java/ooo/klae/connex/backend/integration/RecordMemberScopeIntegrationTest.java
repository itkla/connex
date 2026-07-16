package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

/** Full-stack HTTP coverage for company and contact owner member scopes. */
@SpringBootTest
@Transactional
class RecordMemberScopeIntegrationTest {
    private static final String PASSWORD = "Record-Scope-Test-Pw1!";

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private UserMapper userMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private CompanyMapper companyMapper;
    @Autowired private PersonMapper personMapper;
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
    void scopeModesKeepCompanyAndContactPagesOnTheSameOwnerSets() throws Exception {
        Fixture fixture = fixture();

        assertScopedTotals(fixture, null, null, 5);
        assertScopedTotals(fixture, "me", null, 1);
        assertScopedTotals(fixture, "members", List.of(fixture.firstMember().getId()), 2);
        assertScopedTotals(fixture, "members",
            List.of(fixture.firstMember().getId(), fixture.secondMember().getId()), 3);
        assertScopedTotals(fixture, "unassigned", null, 1);
        assertScopedTotals(fixture, "members", List.of(fixture.emptyMember().getId()), 0);

        JsonNode companies = json(fixture, "/api/companies/page",
            "scope", "members", "memberIds", Integer.toString(fixture.firstMember().getId()));
        JsonNode persons = json(fixture, "/api/persons/page",
            "scope", "members", "memberIds", Integer.toString(fixture.firstMember().getId()));
        assertEquals(fixture.firstMember().getId(), companies.path("items").path(0).path("ownerId").asInt());
        assertEquals(fixture.firstMember().getId(), persons.path("items").path(0).path("ownerId").asInt());
    }

    @Test
    void ownerFacetsRemainAllTeamWhenScopeParametersArePresent() throws Exception {
        Fixture fixture = fixture();

        JsonNode companyFacets = json(fixture, "/api/companies/facets",
            "scope", "me", "memberIds", Integer.toString(fixture.firstMember().getId()));
        JsonNode personFacets = json(fixture, "/api/persons/facets",
            "scope", "me", "memberIds", Integer.toString(fixture.firstMember().getId()));

        assertEquals(5, facetTotal(companyFacets.path("owners")));
        assertEquals(5, facetTotal(personFacets.path("owners")));
        assertEquals(2, facetCount(companyFacets.path("owners"), fixture.firstMember().getId()));
        assertEquals(2, facetCount(personFacets.path("owners"), fixture.firstMember().getId()));
        assertEquals(1, facetCount(companyFacets.path("owners"), "__empty__"));
        assertEquals(1, facetCount(personFacets.path("owners"), "__empty__"));
    }

    private void assertScopedTotals(Fixture fixture, String scope, List<Integer> memberIds,
            int expected) throws Exception {
        List<String> parameters = new ArrayList<>();
        if (scope != null) {
            parameters.add("scope");
            parameters.add(scope);
        }
        if (memberIds != null) {
            for (Integer memberId : memberIds) {
                parameters.add("memberIds");
                parameters.add(Integer.toString(memberId));
            }
        }
        String[] params = parameters.toArray(String[]::new);
        assertEquals(expected, json(fixture, "/api/companies/page", params).path("total").asInt());
        assertEquals(expected, json(fixture, "/api/persons/page", params).path("total").asInt());
    }

    private JsonNode json(Fixture fixture, String path, String... parameters) throws Exception {
        MockHttpServletRequestBuilder request = get(path)
            .header("X-Workspace-Id", fixture.workspace().getId())
            .session(fixture.session());
        for (int index = 0; index < parameters.length; index += 2) {
            request.param(parameters[index], parameters[index + 1]);
        }
        String body = mockMvc.perform(request)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        return objectMapper.readTree(body);
    }

    private long facetTotal(JsonNode facets) {
        long total = 0;
        for (JsonNode facet : facets) {
            total += facet.path("count").asLong();
        }
        return total;
    }

    private long facetCount(JsonNode facets, int memberId) {
        return facetCount(facets, Integer.toString(memberId));
    }

    private long facetCount(JsonNode facets, String key) {
        for (JsonNode facet : facets) {
            if (key.equals(facet.path("key").asText())) {
                return facet.path("count").asLong();
            }
        }
        return 0;
    }

    private Fixture fixture() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace workspace = newWorkspace();
        User currentUser = newUser();
        User firstMember = newUser();
        User secondMember = newUser();
        User emptyMember = newUser();
        workspaceMapper.addMember(workspace.getId(), currentUser.getId(), "owner");
        workspaceMapper.addMember(workspace.getId(), firstMember.getId(), "member");
        workspaceMapper.addMember(workspace.getId(), secondMember.getId(), "member");
        workspaceMapper.addMember(workspace.getId(), emptyMember.getId(), "member");

        newRecords(workspace, currentUser.getId(), "Mine");
        newRecords(workspace, firstMember.getId(), "First A");
        newRecords(workspace, firstMember.getId(), "First B");
        newRecords(workspace, secondMember.getId(), "Second");
        newRecords(workspace, null, "Unassigned");

        return new Fixture(workspace, firstMember, secondMember, emptyMember,
            login(currentUser.getUsername()));
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
        workspace.setName("Record Scope " + suffix);
        workspace.setSlug("record-scope-" + suffix);
        workspaceMapper.insert(workspace);
        return workspace;
    }

    private User newUser() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername("record_scope_" + suffix);
        user.setDisplayName("Record Scope " + suffix);
        user.setEmail(suffix + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setTimezone("UTC");
        userMapper.insert(user);
        return user;
    }

    private void newRecords(Workspace workspace, Integer ownerId, String name) {
        Company company = new Company();
        company.setWorkspaceId(workspace.getId());
        company.setOwnerId(ownerId);
        company.setName("Scope Company " + name);
        companyMapper.insert(company);

        Person person = new Person();
        person.setWorkspaceId(workspace.getId());
        person.setOwnerId(ownerId);
        person.setName("Scope Person " + name);
        person.setEmail(UUID.randomUUID() + "@example.com");
        personMapper.insert(person);
    }

    private record Fixture(
            Workspace workspace,
            User firstMember,
            User secondMember,
            User emptyMember,
            MockHttpSession session) {
    }
}
