package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import jakarta.servlet.Filter;

import org.hamcrest.Matchers;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;

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

@SpringBootTest
@Transactional
class DuplicatePreflightIntegrationTest {

    private static final String PASSWORD = "Duplicate-Preflight-Pw1!";

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private RoleMapper roleMapper;
    @Autowired private PersonMapper personMapper;
    @Autowired private CompanyMapper companyMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(springSecurityFilterChain)
            .build();
    }

    @Test
    void anonymousAndMissingCsrfRequestsAreRejected() throws Exception {
        mockMvc.perform(post("/api/duplicate-preflight/persons")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(personRequest("probe@example.com")))
            .andExpect(status().isUnauthorized());

        Workspace workspace = newWorkspace(newOrganization(), "csrf");
        User user = newMember(workspace, "owner");
        mockMvc.perform(post("/api/duplicate-preflight/persons")
                .header("X-Workspace-Id", workspace.getId())
                .session(login(user.getUsername()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(personRequest("probe@example.com")))
            .andExpect(status().isForbidden());
    }

    @Test
    void customRoleWithoutCreatePermissionIsForbidden() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace workspace = newWorkspace(newOrganization(), "permission");
        User denied = newMember(workspace, "member");
        WorkspaceRole role = new WorkspaceRole();
        role.setWorkspaceId(workspace.getId());
        role.setName("Preflight denied " + suffix());
        roleMapper.insertRole(role);
        roleMapper.insertPermissions(workspace.getId(), role.getId(), List.of("GOAL_READ"));
        workspaceMapper.setMemberCustomRole(workspace.getId(), denied.getId(), role.getId());

        mockMvc.perform(post("/api/duplicate-preflight/persons")
                .with(csrf().asHeader())
                .header("X-Workspace-Id", workspace.getId())
                .session(login(denied.getUsername()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(personRequest("probe@example.com")))
            .andExpect(status().isForbidden());
    }

    @Test
    void personPreflightReturnsOwnedAndSameOrgSharedButNoInvisibleRecords() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Organization organization = newOrganization();
        Workspace current = newWorkspace(organization, "current");
        Workspace sibling = newWorkspace(organization, "sibling");
        Workspace foreign = newWorkspace(newOrganization(), "foreign");
        User reader = newMember(current, "owner");
        Person owned = newPerson(current, "Owned Visible");
        Person shared = newPerson(sibling, "Shared Visible");
        Person unshared = newPerson(sibling, "Sibling Invisible");
        Person crossOrg = newPerson(foreign, "Cross Org Invisible");
        Person suspended = newPerson(current, "Suspended Invisible");
        insertPersonIdentity(owned, "probe@example.com");
        insertPersonIdentity(shared, "probe@example.com");
        insertPersonIdentity(unshared, "probe@example.com");
        insertPersonIdentity(crossOrg, "probe@example.com");
        insertPersonIdentity(suspended, "probe@example.com");
        personMapper.updateProcessingRestrictions(
            current.getId(), suspended.getId(), true, false);
        insertPersonShare(shared, current, reader);
        insertPersonShare(crossOrg, current, reader);
        MockHttpSession session = login(reader.getUsername());

        mockMvc.perform(post("/api/duplicate-preflight/persons")
                .with(csrf().asHeader())
                .header("X-Workspace-Id", current.getId())
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(personRequest("PROBE@EXAMPLE.COM")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.recordType").value("person"))
            .andExpect(jsonPath("$.candidates.length()").value(2))
            .andExpect(jsonPath("$.candidates[*].name",
                Matchers.containsInAnyOrder("Owned Visible", "Shared Visible")))
            .andExpect(jsonPath("$.candidates[*].strength",
                Matchers.everyItem(Matchers.is("STRONG"))))
            .andExpect(jsonPath("$.candidates[*].ownedByActiveWorkspace",
                Matchers.containsInAnyOrder(true, false)))
            .andExpect(content().string(Matchers.not(
                Matchers.containsString("Sibling Invisible"))))
            .andExpect(content().string(Matchers.not(
                Matchers.containsString("Cross Org Invisible"))))
            .andExpect(content().string(Matchers.not(
                Matchers.containsString("Suspended Invisible"))))
            .andExpect(content().string(Matchers.not(
                Matchers.containsString("source_system"))))
            .andExpect(content().string(Matchers.not(
                Matchers.containsString("recordWorkspaceId"))));

        mockMvc.perform(post("/api/duplicate-preflight/persons")
                .with(csrf().asHeader())
                .header("X-Workspace-Id", sibling.getId())
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(personRequest("probe@example.com")))
            .andExpect(status().isForbidden());
    }

    @Test
    void companyPreflightAppliesTheSameVisibilityCeiling() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Organization organization = newOrganization();
        Workspace current = newWorkspace(organization, "company-current");
        Workspace sibling = newWorkspace(organization, "company-sibling");
        Workspace foreign = newWorkspace(newOrganization(), "company-foreign");
        User reader = newMember(current, "owner");
        Company owned = newCompany(current, "Owned Company");
        Company shared = newCompany(sibling, "Shared Company");
        Company unshared = newCompany(sibling, "Sibling Company Invisible");
        Company crossOrg = newCompany(foreign, "Cross Org Company Invisible");
        insertCompanyIdentity(owned, "probe.example");
        insertCompanyIdentity(shared, "probe.example");
        insertCompanyIdentity(unshared, "probe.example");
        insertCompanyIdentity(crossOrg, "probe.example");
        insertCompanyShare(shared, current, reader);
        insertCompanyShare(crossOrg, current, reader);

        mockMvc.perform(post("/api/duplicate-preflight/companies")
                .with(csrf().asHeader())
                .header("X-Workspace-Id", current.getId())
                .session(login(reader.getUsername()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "name": null,
                      "websites": ["https://www.probe.example"],
                      "phones": [],
                      "externalIds": []
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.candidates.length()").value(2))
            .andExpect(jsonPath("$.candidates[*].name",
                Matchers.containsInAnyOrder("Owned Company", "Shared Company")))
            .andExpect(content().string(Matchers.not(
                Matchers.containsString("Sibling Company Invisible"))))
            .andExpect(content().string(Matchers.not(
                Matchers.containsString("Cross Org Company Invisible"))));
    }

    private Organization newOrganization() {
        String suffix = suffix();
        Organization organization = new Organization();
        organization.setName("Preflight Org " + suffix);
        organization.setSlug("preflight-org-" + suffix);
        organizationMapper.insert(organization);
        return organization;
    }

    private Workspace newWorkspace(Organization organization, String prefix) {
        String suffix = suffix();
        Workspace workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("Preflight " + prefix + " " + suffix);
        workspace.setSlug("preflight-" + prefix + "-" + suffix);
        workspaceMapper.insert(workspace);
        return workspace;
    }

    private User newMember(Workspace workspace, String role) {
        String suffix = suffix();
        User user = new User();
        user.setUsername("preflight_" + suffix);
        user.setDisplayName("Preflight " + suffix);
        user.setEmail(suffix + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setTimezone("UTC");
        userMapper.insert(user);
        workspaceMapper.addMember(workspace.getId(), user.getId(), role);
        return user;
    }

    private Person newPerson(Workspace workspace, String name) {
        Person person = new Person();
        person.setWorkspaceId(workspace.getId());
        person.setName(name);
        personMapper.insert(person);
        return person;
    }

    private Company newCompany(Workspace workspace, String name) {
        Company company = new Company();
        company.setWorkspaceId(workspace.getId());
        company.setName(name);
        companyMapper.insert(company);
        return company;
    }

    private void insertPersonIdentity(Person person, String normalizedValue) {
        jdbcTemplate.update(
            """
            INSERT INTO person_identity (
              workspace_id, person_id, kind, `value`, normalized_value,
              source_system, source_channel, acquired_at
            )
            VALUES (?, ?, 'email', ?, ?, 'test', 'person.email', CURRENT_TIMESTAMP)
            """,
            person.getWorkspaceId(),
            person.getId(),
            normalizedValue,
            normalizedValue);
    }

    private void insertCompanyIdentity(Company company, String normalizedValue) {
        jdbcTemplate.update(
            """
            INSERT INTO company_identity (
              workspace_id, company_id, kind, `value`, normalized_value,
              source_system, source_channel, acquired_at
            )
            VALUES (?, ?, 'domain', ?, ?, 'test', 'company.website', CURRENT_TIMESTAMP)
            """,
            company.getWorkspaceId(),
            company.getId(),
            normalizedValue,
            normalizedValue);
    }

    private void insertPersonShare(Person person, Workspace grantee, User actor) {
        jdbcTemplate.update(
            """
            INSERT INTO person_share (person_id, workspace_id, granted_by, can_edit)
            VALUES (?, ?, ?, FALSE)
            """,
            person.getId(),
            grantee.getId(),
            actor.getId());
    }

    private void insertCompanyShare(Company company, Workspace grantee, User actor) {
        jdbcTemplate.update(
            """
            INSERT INTO company_share (company_id, workspace_id, granted_by, can_edit)
            VALUES (?, ?, ?, FALSE)
            """,
            company.getId(),
            grantee.getId(),
            actor.getId());
    }

    private MockHttpSession login(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(session);
        return session;
    }

    private static String personRequest(String email) {
        return """
            {
              "name": null,
              "emails": ["%s"],
              "phones": [],
              "externalIds": []
            }
            """.formatted(email);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
