package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.Filter;

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
import ooo.klae.connex.backend.mappers.ShareMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.CompanyService;
import ooo.klae.connex.backend.services.IdentityBackfillTransaction;
import ooo.klae.connex.backend.services.PersonService;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.tenant.TenantContext;

@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DuplicateReviewIntegrationTest {

    private static final String PASSWORD = "Duplicate-Review-Pw1!";

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private RoleMapper roleMapper;
    @Autowired private CompanyMapper companyMapper;
    @Autowired private PersonMapper personMapper;
    @Autowired private ShareMapper shareMapper;
    @Autowired private CompanyService companyService;
    @Autowired private PersonService personService;
    @Autowired private IdentityBackfillTransaction identityBackfillTransaction;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TenantContext tenantContext;

    private MockMvc mockMvc;
    private Organization organization;
    private Workspace workspace;
    private User member;
    private final List<User> createdUsers = new ArrayList<>();
    private final List<Workspace> siblingWorkspaces = new ArrayList<>();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(springSecurityFilterChain)
            .build();
        String suffix = suffix();
        organization = new Organization();
        organization.setName("Duplicate review " + suffix);
        organization.setSlug("duplicate-review-" + suffix);
        organizationMapper.insert(organization);
        workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("Duplicate review " + suffix);
        workspace.setSlug("duplicate-review-" + suffix);
        workspaceMapper.insert(workspace);
        member = newMember("member", List.of(
            "COMPANY_CREATE", "COMPANY_UPDATE", "COMPANY_DELETE",
            "PERSON_CREATE", "PERSON_UPDATE", "PERSON_DELETE", "REPORT_READ"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        tenantContext.clear();
        if (workspace != null) {
            jdbcTemplate.update(
                "DELETE FROM duplicate_review_decision WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update(
                "DELETE FROM identity_collision WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update(
                "DELETE FROM person_identity WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM person WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM company WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM workspace_member WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update(
                "DELETE wrp FROM workspace_role_permission wrp "
                    + "JOIN workspace_role wr ON wr.id = wrp.workspace_role_id "
                    + "WHERE wr.workspace_id = ?",
                workspace.getId());
            jdbcTemplate.update("DELETE FROM workspace_role WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", workspace.getId());
        }
        for (Workspace sibling : siblingWorkspaces.reversed()) {
            jdbcTemplate.update("DELETE FROM company WHERE workspace_id = ?", sibling.getId());
            jdbcTemplate.update("DELETE FROM workspace_member WHERE workspace_id = ?", sibling.getId());
            jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", sibling.getId());
        }
        for (User user : createdUsers.reversed()) {
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", user.getId());
        }
        if (organization != null) {
            jdbcTemplate.update("DELETE FROM organization WHERE id = ?", organization.getId());
        }
    }

    @Test
    void listDismissReopenAndChangedEvidenceRoundTrip() throws Exception {
        authenticate(member);
        Person first = personService.create(person("First Review", "shared@example.com"));
        Person second = personService.create(person("Second Review", "SHARED@example.com"));
        clearDirectAuthentication();
        String originalFingerprint = currentFingerprint("email");
        MockHttpSession session = login(member);

        mockMvc.perform(get("/api/duplicate-reviews")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].itemType").value("pair"))
            .andExpect(jsonPath("$.items[0].recordType").value("person"))
            .andExpect(jsonPath("$.items[0].confidence").value("STRONG"))
            .andExpect(jsonPath("$.items[0].evidence.kind").value("EMAIL"))
            .andExpect(jsonPath("$.items[0].evidence.normalizedValue").doesNotExist())
            .andExpect(jsonPath("$.items[0].members.length()").value(2))
            .andExpect(jsonPath("$.items[0].members[0].ownedByActiveWorkspace").value(true))
            .andExpect(jsonPath("$.items[0].members[1].ownedByActiveWorkspace").value(true));
        mockMvc.perform(get("/api/duplicate-reviews/summary")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.personOpenCount").value(1))
            .andExpect(jsonPath("$.companyOpenCount").value(0));

        mockMvc.perform(decision(
                "dismiss", "email", first, second, originalFingerprint, "not duplicate", session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("dismissed"));

        mockMvc.perform(get("/api/duplicate-reviews")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(0));
        mockMvc.perform(get("/api/duplicate-reviews")
                .queryParam("state", "dismissed")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1));

        mockMvc.perform(decision(
                "reopen", "email", first, second, originalFingerprint, null, session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("open"));
        mockMvc.perform(decision(
                "dismiss", "email", first, second, originalFingerprint, null, session))
            .andExpect(status().isOk());

        jdbcTemplate.update(
            """
            INSERT INTO person_identity (
              workspace_id, person_id, kind, `value`, normalized_value,
              source_system, source_channel, acquired_at)
            VALUES (?, ?, 'email', ?, ?, 'csv_import', 'person.email', CURRENT_TIMESTAMP)
            """,
            workspace.getId(), second.getId(), "changed@example.com", "changed@example.com");
        authenticate(member);
        Person changed = person("First Review", "changed@example.com");
        personService.update(first.getId(), changed);
        clearDirectAuthentication();

        String changedFingerprint = currentFingerprint("email");
        assertNotEquals(originalFingerprint, changedFingerprint);
        assertEquals(1, jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM duplicate_review_decision
            WHERE workspace_id = ?
              AND evidence_fingerprint = ?
              AND state = 'dismissed'
              AND is_current = FALSE
            """,
            Integer.class,
            workspace.getId(), originalFingerprint));
        mockMvc.perform(get("/api/duplicate-reviews")
                .queryParam("state", "open")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].evidence.kind").value("EMAIL"))
            .andExpect(jsonPath("$.items[0].evidence.normalizedValue").doesNotExist());
    }

    @Test
    void companyPairRoundTripsThroughMysqlShapeAndUpsert() throws Exception {
        authenticate(member);
        Company first = companyService.createCompany(
            company("Company Review First", "first-company.example", "+1 202-555-0199"));
        Company second = companyService.createCompany(
            company("Company Review Second", "second-company.example", "+1 (202) 555-0199"));
        clearDirectAuthentication();
        String fingerprint = currentFingerprint("phone");
        MockHttpSession session = login(member);

        mockMvc.perform(get("/api/duplicate-reviews")
                .queryParam("recordType", "company")
                .queryParam("kind", "phone")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].recordType").value("company"))
            .andExpect(jsonPath("$.items[0].members[0].recordId").value(first.getId()))
            .andExpect(jsonPath("$.items[0].members[1].recordId").value(second.getId()));

        mockMvc.perform(companyDecision("dismiss", first, second, fingerprint, session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("dismissed"));
        mockMvc.perform(companyDecision("reopen", first, second, fingerprint, session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("open"));

        authenticate(member);
        Company archived = companyService.createCompany(
            company("Company Review Archived", "archived-company.example", "+1 202-555-0199"));
        companyService.archiveCompany(archived.getId());
        clearDirectAuthentication();
        mockMvc.perform(get("/api/duplicate-reviews")
                .queryParam("recordType", "company")
                .queryParam("kind", "phone")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].groupSize").value(2));
    }

    @Test
    void personSummaryIncludesEmployerSharedFromSiblingWorkspace() throws Exception {
        Workspace sibling = siblingWorkspace();
        workspaceMapper.addMember(sibling.getId(), member.getId(), "member");
        authenticate(member, sibling);
        Company sharedEmployer = company(
            "Shared Employer", "shared-employer.example", "+1 202-555-0134");
        sharedEmployer.setWorkspaceId(sibling.getId());
        companyMapper.insert(sharedEmployer);
        assertEquals(1, shareMapper.shareCompany(
            sharedEmployer.getId(), sibling.getId(), workspace.getId(), member.getId(), false));
        clearDirectAuthentication();

        authenticate(member);
        Person firstInput = person("Shared Employer First", "shared-employer@example.com");
        firstInput.setCompany(sharedEmployer);
        personService.create(firstInput);
        Person secondInput = person("Shared Employer Second", "shared-employer@example.com");
        secondInput.setCompany(sharedEmployer);
        personService.create(secondInput);
        clearDirectAuthentication();

        mockMvc.perform(get("/api/duplicate-reviews")
                .queryParam("recordType", "person")
                .queryParam("kind", "email")
                .header("X-Workspace-Id", workspace.getId())
                .session(login(member)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].members[0].companyName")
                .value("Shared Employer"))
            .andExpect(jsonPath("$.items[0].members[1].companyName")
                .value("Shared Employer"));
    }

    @Test
    void dismissesEmailAndPhoneEvidenceIndependentlyForTheSamePair() throws Exception {
        authenticate(member);
        Person first = person("Multi-evidence First", "multi@example.com");
        first.setPhone("+1 202-555-0123");
        first = personService.create(first);
        Person second = person("Multi-evidence Second", "MULTI@example.com");
        second.setPhone("+1 (202) 555-0123");
        second = personService.create(second);
        clearDirectAuthentication();
        String emailFingerprint = currentFingerprint("email");
        MockHttpSession session = login(member);

        mockMvc.perform(get("/api/duplicate-reviews")
                .queryParam("recordType", "person")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(2));

        mockMvc.perform(decision(
                "dismiss", "email", first, second, emailFingerprint, null, session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.evidence.kind").value("EMAIL"));

        mockMvc.perform(get("/api/duplicate-reviews")
                .queryParam("recordType", "person")
                .queryParam("state", "open")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].evidence.kind").value("PHONE"));
        mockMvc.perform(get("/api/duplicate-reviews")
                .queryParam("recordType", "person")
                .queryParam("kind", "email")
                .queryParam("state", "dismissed")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void reportOnlyRoleCanReadButCannotDismissAndNoPermissionRoleCannotRead() throws Exception {
        authenticate(member);
        Person first = personService.create(person("Permission First", "permission@example.com"));
        Person second = personService.create(person("Permission Second", "permission@example.com"));
        clearDirectAuthentication();
        String fingerprint = currentFingerprint("email");
        User reportOnly = newMember("member", List.of("REPORT_READ"));
        User denied = newMember("member", List.of("GOAL_READ"));

        MockHttpSession reportSession = login(reportOnly);
        mockMvc.perform(get("/api/duplicate-reviews")
                .header("X-Workspace-Id", workspace.getId())
                .session(reportSession))
            .andExpect(status().isOk());
        mockMvc.perform(decision(
                "dismiss", "email", first, second, fingerprint, null, reportSession))
            .andExpect(status().isForbidden());

        MockHttpSession deniedSession = login(denied);
        mockMvc.perform(get("/api/duplicate-reviews")
                .header("X-Workspace-Id", workspace.getId())
                .session(deniedSession))
            .andExpect(status().isForbidden());
        mockMvc.perform(decision(
                "dismiss", "email", first, second, fingerprint, null, deniedSession))
            .andExpect(status().isForbidden());
    }

    @Test
    void archivedOrRestrictedMemberRemovesPairFromVisibleQueue() throws Exception {
        authenticate(member);
        Person first = personService.create(person("Visibility First", "visibility@example.com"));
        Person second = personService.create(person("Visibility Second", "visibility@example.com"));
        clearDirectAuthentication();
        MockHttpSession session = login(member);

        authenticate(member);
        personService.archive(first.getId());
        clearDirectAuthentication();
        mockMvc.perform(get("/api/duplicate-reviews")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(0));

        authenticate(member);
        personService.restore(first.getId());
        personService.updateProcessingRestrictions(second.getId(), true, false);
        clearDirectAuthentication();
        mockMvc.perform(get("/api/duplicate-reviews")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void restrictionRecomputesPairCardinalityWithoutLeakingHiddenMember() throws Exception {
        authenticate(member);
        Person first = personService.create(person("Cardinality First", "cardinality@example.com"));
        Person second = personService.create(person("Cardinality Second", "cardinality@example.com"));
        Person restricted = personService.create(
            person("Cardinality Restricted", "cardinality@example.com"));
        personService.updateProcessingRestrictions(restricted.getId(), true, false);
        clearDirectAuthentication();
        MockHttpSession session = login(member);

        mockMvc.perform(get("/api/duplicate-reviews")
                .queryParam("recordType", "person")
                .queryParam("kind", "email")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].groupSize").value(2))
            .andExpect(jsonPath("$.items[0].members[0].recordId").value(first.getId()))
            .andExpect(jsonPath("$.items[0].members[1].recordId").value(second.getId()));
        mockMvc.perform(get("/api/duplicate-reviews/summary")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.personOpenCount").value(1));
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM duplicate_review_decision"
                + " WHERE workspace_id = ? AND is_current = TRUE",
            Integer.class,
            workspace.getId()));
    }

    @Test
    void pairExpansionTransitionsAtTwentyOneAndBackToTwentyVisibleMembers() throws Exception {
        authenticate(member);
        Person firstMember = null;
        for (int index = 0; index < 20; index++) {
            Person person = new Person();
            person.setWorkspaceId(workspace.getId());
            person.setName("Shared switchboard " + index);
            personMapper.insert(person);
            if (firstMember == null) {
                firstMember = person;
            }
            jdbcTemplate.update(
                """
                INSERT INTO person_identity (
                  workspace_id, person_id, kind, `value`, normalized_value,
                  source_system, source_channel, acquired_at)
                VALUES (?, ?, 'phone', ?, ?, 'csv_import', 'person.phone', CURRENT_TIMESTAMP)
                """,
                workspace.getId(), person.getId(), "+1 555 0100", "+15550100");
        }
        identityBackfillTransaction.rebuildCollisionReport("default", workspace.getId());
        clearDirectAuthentication();
        MockHttpSession session = login(member);

        mockMvc.perform(get("/api/duplicate-reviews")
                .queryParam("recordType", "person")
                .queryParam("kind", "phone")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(190))
            .andExpect(jsonPath("$.items[0].itemType").value("pair"))
            .andExpect(jsonPath("$.items[0].groupSize").value(20));
        assertEquals(190, currentDecisionCount());

        authenticate(member);
        Person twentyFirst = new Person();
        twentyFirst.setWorkspaceId(workspace.getId());
        twentyFirst.setName("Shared switchboard 20");
        personMapper.insert(twentyFirst);
        jdbcTemplate.update(
            """
            INSERT INTO person_identity (
              workspace_id, person_id, kind, `value`, normalized_value,
              source_system, source_channel, acquired_at)
            VALUES (?, ?, 'phone', ?, ?, 'csv_import', 'person.phone', CURRENT_TIMESTAMP)
            """,
            workspace.getId(), twentyFirst.getId(), "+1 555 0100", "+15550100");
        identityBackfillTransaction.rebuildCollisionReport("default", workspace.getId());
        clearDirectAuthentication();

        mockMvc.perform(get("/api/duplicate-reviews")
                .queryParam("recordType", "person")
                .queryParam("kind", "phone")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].itemType").value("oversized_group"))
            .andExpect(jsonPath("$.items[0].groupSize").value(21))
            .andExpect(jsonPath("$.items[0].members").isEmpty())
            .andExpect(jsonPath("$.items[0].membersTruncated").value(true));
        assertEquals(1, currentDecisionCount());

        assertNotNull(firstMember);
        authenticate(member);
        personService.archive(firstMember.getId());
        clearDirectAuthentication();
        mockMvc.perform(get("/api/duplicate-reviews")
                .queryParam("recordType", "person")
                .queryParam("kind", "phone")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(190))
            .andExpect(jsonPath("$.items[0].itemType").value("pair"))
            .andExpect(jsonPath("$.items[0].groupSize").value(20));
        assertEquals(190, currentDecisionCount());
    }

    @Test
    void restrictedThenArchivedMemberStaysTwentyOneUntilRestoreAndUnrestrict() throws Exception {
        authenticate(member);
        List<Person> people = createExternalIdPeople(22, "restricted-archive-review");
        identityBackfillTransaction.rebuildCollisionReport("default", workspace.getId());
        Person restricted = people.getFirst();
        clearDirectAuthentication();
        MockHttpSession session = login(member);

        assertOversizedGroup(session, 22);

        authenticate(member);
        personService.updateProcessingRestrictions(restricted.getId(), true, false);
        clearDirectAuthentication();
        assertOversizedGroup(session, 21);

        authenticate(member);
        personService.archive(restricted.getId());
        clearDirectAuthentication();
        assertOversizedGroup(session, 21);

        authenticate(member);
        Person restored = personService.restore(restricted.getId());
        assertNotNull(restored.getSuspendedAt());
        clearDirectAuthentication();
        assertOversizedGroup(session, 21);

        authenticate(member);
        personService.updateProcessingRestrictions(restricted.getId(), false, false);
        clearDirectAuthentication();
        assertOversizedGroup(session, 22);
    }

    @Test
    void unrestrictThenArchiveAndRestoreRejoinsExactlyOnce() throws Exception {
        authenticate(member);
        List<Person> people = createExternalIdPeople(22, "unrestrict-archive-restore-review");
        identityBackfillTransaction.rebuildCollisionReport("default", workspace.getId());
        Person toggled = people.getFirst();
        clearDirectAuthentication();
        MockHttpSession session = login(member);

        assertOversizedGroup(session, 22);

        authenticate(member);
        personService.updateProcessingRestrictions(toggled.getId(), true, false);
        clearDirectAuthentication();
        assertOversizedGroup(session, 21);

        authenticate(member);
        personService.updateProcessingRestrictions(toggled.getId(), false, false);
        clearDirectAuthentication();
        assertOversizedGroup(session, 22);

        authenticate(member);
        personService.archive(toggled.getId());
        clearDirectAuthentication();
        assertOversizedGroup(session, 21);

        authenticate(member);
        personService.restore(toggled.getId());
        clearDirectAuthentication();
        assertOversizedGroup(session, 22);
    }

    @Test
    void companyExternalIdArchiveUpdatesOnlyTheSurvivingPairCardinality() throws Exception {
        authenticate(member);
        List<Company> companies = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            Company company = new Company();
            company.setWorkspaceId(workspace.getId());
            company.setName("External company " + index);
            companyMapper.insert(company);
            companies.add(company);
            jdbcTemplate.update(
                """
                INSERT INTO company_identity (
                  workspace_id, company_id, kind, `value`, normalized_value,
                  source_system, source_channel, source_external_id, acquired_at)
                VALUES (?, ?, 'external_id', ?, ?, 'csv_import', 'company.external_id', ?,
                        CURRENT_TIMESTAMP)
                """,
                workspace.getId(),
                company.getId(),
                "external-company-review",
                "external-company-review",
                "external-company-review");
        }
        identityBackfillTransaction.rebuildCollisionReport("default", workspace.getId());
        companyService.archiveCompany(companies.getFirst().getId());
        clearDirectAuthentication();

        mockMvc.perform(get("/api/duplicate-reviews")
                .queryParam("recordType", "company")
                .queryParam("kind", "external_id")
                .header("X-Workspace-Id", workspace.getId())
                .session(login(member)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].itemType").value("pair"))
            .andExpect(jsonPath("$.items[0].groupSize").value(2))
            .andExpect(jsonPath("$.items[0].evidence.kind").value("EXTERNAL_ID"))
            .andExpect(jsonPath("$.items[0].evidence.normalizedValue").doesNotExist());
        assertEquals(1, currentDecisionCount());
    }

    @Test
    void mysqlPaginationReturnsStableSecondPageAndExactTotal() throws Exception {
        authenticate(member);
        for (int group = 0; group < 3; group++) {
            String email = "page-" + group + "@example.com";
            personService.create(person("Page " + group + " First", email));
            personService.create(person("Page " + group + " Second", email));
        }
        clearDirectAuthentication();
        MockHttpSession session = login(member);

        mockMvc.perform(get("/api/duplicate-reviews")
                .queryParam("recordType", "person")
                .queryParam("kind", "email")
                .queryParam("page", "1")
                .queryParam("size", "2")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(3))
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[*].evidence.normalizedValue").doesNotExist());
        mockMvc.perform(get("/api/duplicate-reviews")
                .queryParam("recordType", "person")
                .queryParam("kind", "email")
                .queryParam("page", "2")
                .queryParam("size", "2")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(3))
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].evidence.normalizedValue").doesNotExist());
    }

    @Test
    void queueIndexMatchesFilteredStableOrdering() {
        String indexColumns = jdbcTemplate.queryForObject(
            """
            SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX)
            FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'duplicate_review_decision'
              AND INDEX_NAME = 'idx_duplicate_review_queue'
            """,
            String.class);
        assertEquals("workspace_id,is_current,state,detected_at,id", indexColumns);
        List<String> descendingColumns = jdbcTemplate.queryForList(
            """
            SELECT COLUMN_NAME
            FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'duplicate_review_decision'
              AND INDEX_NAME = 'idx_duplicate_review_queue'
              AND COLLATION = 'D'
            ORDER BY SEQ_IN_INDEX
            """,
            String.class);
        assertEquals(List.of("detected_at", "id"), descendingColumns);
    }

    @Test
    void decisionSchemaStoresNoNormalizedIdentityValue() {
        List<String> columns = jdbcTemplate.queryForList(
            """
            SELECT COLUMN_NAME
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'duplicate_review_decision'
            """,
            String.class);
        assertTrue(columns.stream().noneMatch(column -> column.contains("normalized")));
        assertFalse(columns.contains("value"));
        assertTrue(columns.contains("evidence_fingerprint"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder decision(
            String action,
            String kind,
            Person first,
            Person second,
            String fingerprint,
            String note,
            MockHttpSession session) {
        String noteJson = note == null ? "null" : "\"" + note + "\"";
        return post("/api/duplicate-reviews/" + action)
            .with(csrf().asHeader())
            .header("X-Workspace-Id", workspace.getId())
            .session(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "recordType": "person",
                  "kind": "%s",
                  "recordIdA": %d,
                  "recordIdB": %d,
                  "evidenceFingerprint": "%s",
                  "note": %s
                }
                """.formatted(kind, first.getId(), second.getId(), fingerprint, noteJson));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder companyDecision(
            String action,
            Company first,
            Company second,
            String fingerprint,
            MockHttpSession session) {
        return post("/api/duplicate-reviews/" + action)
            .with(csrf().asHeader())
            .header("X-Workspace-Id", workspace.getId())
            .session(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "recordType": "company",
                  "kind": "phone",
                  "recordIdA": %d,
                  "recordIdB": %d,
                  "evidenceFingerprint": "%s",
                  "note": null
                }
                """.formatted(first.getId(), second.getId(), fingerprint));
    }

    private int currentDecisionCount() {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM duplicate_review_decision"
                + " WHERE workspace_id = ? AND is_current = TRUE",
            Integer.class,
            workspace.getId());
    }

    private int currentCollisionSize() {
        return jdbcTemplate.queryForObject(
            "SELECT collision_size FROM duplicate_review_decision"
                + " WHERE workspace_id = ? AND is_current = TRUE",
            Integer.class,
            workspace.getId());
    }

    private void assertOversizedGroup(MockHttpSession session, int groupSize) throws Exception {
        mockMvc.perform(get("/api/duplicate-reviews")
                .queryParam("recordType", "person")
                .queryParam("kind", "external_id")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].itemType").value("oversized_group"))
            .andExpect(jsonPath("$.items[0].groupSize").value(groupSize))
            .andExpect(jsonPath("$.items[0].evidence.kind").value("EXTERNAL_ID"))
            .andExpect(jsonPath("$.items[0].evidence.normalizedValue").doesNotExist());
        assertEquals(1, currentDecisionCount());
        assertEquals(groupSize, currentCollisionSize());
    }

    private List<Person> createExternalIdPeople(int count, String externalId) {
        List<Person> people = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            Person person = new Person();
            person.setWorkspaceId(workspace.getId());
            person.setName("External identity " + index);
            personMapper.insert(person);
            people.add(person);
            jdbcTemplate.update(
                """
                INSERT INTO person_identity (
                  workspace_id, person_id, kind, `value`, normalized_value,
                  source_system, source_channel, source_external_id, acquired_at)
                VALUES (?, ?, 'external_id', ?, ?, 'csv_import', 'person.external_id', ?,
                        CURRENT_TIMESTAMP)
                """,
                workspace.getId(), person.getId(), externalId, externalId, externalId);
        }
        return people;
    }

    private String currentFingerprint(String kind) {
        String fingerprint = jdbcTemplate.queryForObject(
            """
            SELECT evidence_fingerprint
            FROM duplicate_review_decision
            WHERE workspace_id = ? AND kind = ? AND is_current = TRUE
            ORDER BY id DESC
            LIMIT 1
            """,
            String.class,
            workspace.getId(), kind);
        return java.util.Objects.requireNonNull(fingerprint);
    }

    private User newMember(String role, List<String> permissions) {
        String suffix = suffix();
        User user = new User();
        user.setUsername("duplicate_review_" + suffix);
        user.setDisplayName("Duplicate Review " + suffix);
        user.setEmail(suffix + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setTimezone("UTC");
        userMapper.insert(user);
        createdUsers.add(user);
        workspaceMapper.addMember(workspace.getId(), user.getId(), role);
        if (permissions != null) {
            WorkspaceRole customRole = new WorkspaceRole();
            customRole.setWorkspaceId(workspace.getId());
            customRole.setName("Duplicate review role " + suffix);
            authenticate(user);
            try {
                roleMapper.insertRole(customRole);
                roleMapper.insertPermissions(workspace.getId(), customRole.getId(), permissions);
                workspaceMapper.setMemberCustomRole(
                    workspace.getId(), user.getId(), customRole.getId());
            } finally {
                clearDirectAuthentication();
            }
        }
        return user;
    }

    private Person person(String name, String email) {
        Person person = new Person();
        person.setName(name);
        person.setEmail(email);
        person.setPhone(null);
        person.setTitle("Reviewer");
        return person;
    }

    private Company company(String name, String website, String phone) {
        Company company = new Company();
        company.setName(name);
        company.setWebsite(website);
        company.setPhone(phone);
        company.setIndustry("Services");
        return company;
    }

    private Workspace siblingWorkspace() {
        Workspace sibling = new Workspace();
        String suffix = suffix();
        sibling.setOrgId(organization.getId());
        sibling.setName("Duplicate review sibling " + suffix);
        sibling.setSlug("duplicate-review-sibling-" + suffix);
        workspaceMapper.insert(sibling);
        siblingWorkspaces.add(sibling);
        return sibling;
    }

    private void authenticate(User user) {
        authenticate(user, workspace);
    }

    private void authenticate(User user, Workspace activeWorkspace) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession().setAttribute(
            SessionSecurityService.AUTHENTICATED_AT_ATTR, System.currentTimeMillis());
        request.getSession().setAttribute(
            SessionSecurityService.AUTHENTICATED_USER_ATTR, user.getId());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        tenantContext.set(
            activeWorkspace.getId(), organization.getId(), user.getId(), "member", null);
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
