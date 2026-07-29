package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.dto.IdentityCollisionMemberQuery;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.IdentityCollisionMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import tools.jackson.databind.ObjectMapper;

/**
 * Full security-filter-chain coverage for the workspace collision report endpoint.
 */
@SpringBootTest
@Transactional(isolation = Isolation.REPEATABLE_READ)
class IdentityCollisionIntegrationTest {

    private static final String PASSWORD = "Identity-Collision-Pw1!";
    private static final LocalDateTime REBUILT_AT =
        LocalDateTime.of(2026, 7, 25, 13, 0);

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private UserMapper userMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private RoleMapper roleMapper;
    @Autowired private CompanyMapper companyMapper;
    @Autowired private PersonMapper personMapper;
    @Autowired private IdentityCollisionMapper collisionMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(springSecurityFilterChain)
            .build();
    }

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/identity-collisions"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void customRoleWithoutReportReadIsForbidden() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace workspace = newWorkspace("identity-denied");
        User denied = newMember(workspace, "member");
        WorkspaceRole role = new WorkspaceRole();
        role.setWorkspaceId(workspace.getId());
        role.setName("Identity denied " + suffix());
        roleMapper.insertRole(role);
        roleMapper.insertPermissions(workspace.getId(), role.getId(), List.of("GOAL_READ"));
        workspaceMapper.setMemberCustomRole(workspace.getId(), denied.getId(), role.getId());

        mockMvc.perform(get("/api/identity-collisions")
                .header("X-Workspace-Id", workspace.getId())
                .session(login(denied.getUsername())))
            .andExpect(status().isForbidden());
    }

    @Test
    void permittedMemberSeesOnlyCurrentWorkspaceAndNoRawProvenance() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace current = newWorkspace("identity-current");
        Workspace other = newWorkspace("identity-foreign");
        User reader = newMember(current, "member");
        workspaceMapper.addMember(other.getId(), reader.getId(), "member");
        createPersonEmailGroup(
            current,
            "visible@example.com",
            List.of("Visible Alpha", "Visible Beta"),
            List.of("Visible@Example.com", "VISIBLE@example.com"));
        createCompanyDomainGroup(
            current,
            "company-visible.example",
            List.of("Visible Company One", "Visible Company Two"));
        createPersonEmailGroup(
            other,
            "foreign-secret@example.com",
            List.of("Foreign Secret One", "Foreign Secret Two"),
            List.of("Foreign-Secret@Example.com", "foreign-secret@example.com"));
        rebuild(current.getId());
        rebuild(other.getId());
        MockHttpSession session = login(reader.getUsername());

        mockMvc.perform(get("/api/identity-collisions")
                .header("X-Workspace-Id", current.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(2))
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[*].normalizedValue",
                Matchers.hasItems("visible@example.com", "company-visible.example")))
            .andExpect(jsonPath(
                "$.items[?(@.normalizedValue == 'visible@example.com')].collisionSize",
                Matchers.contains(2)))
            .andExpect(content().string(Matchers.containsString("Visible Alpha")))
            .andExpect(content().string(Matchers.not(Matchers.containsString("foreign-secret@example.com"))))
            .andExpect(content().string(Matchers.not(Matchers.containsString("Foreign Secret One"))))
            .andExpect(content().string(Matchers.not(Matchers.containsString("Visible@Example.com"))))
            .andExpect(content().string(Matchers.not(Matchers.containsString("sourceSystem"))))
            .andExpect(content().string(Matchers.not(Matchers.containsString("sourceRowRef"))));

        mockMvc.perform(get("/api/identity-collisions")
                .header("X-Workspace-Id", other.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(content().string(Matchers.containsString("foreign-secret@example.com")))
            .andExpect(content().string(Matchers.not(Matchers.containsString("visible@example.com"))));
    }

    @Test
    void filtersValidationAndPaginationAreEnforced() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace workspace = newWorkspace("identity-filter");
        User reader = newMember(workspace, "member");
        createPersonEmailGroup(
            workspace,
            "filter@example.com",
            List.of("Filter One", "Filter Two"),
            List.of("Filter@One.com", "filter@two.com"));
        createCompanyDomainGroup(
            workspace,
            "filter.example",
            List.of("Filter Company One", "Filter Company Two"));
        rebuild(workspace.getId());
        MockHttpSession session = login(reader.getUsername());

        mockMvc.perform(get("/api/identity-collisions")
                .queryParam("recordType", "person")
                .queryParam("kind", "email")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].recordType").value("person"))
            .andExpect(jsonPath("$.items[0].kind").value("email"));

        mockMvc.perform(get("/api/identity-collisions")
                .queryParam("recordType", "company")
                .queryParam("kind", "domain")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].recordType").value("company"));

        mockMvc.perform(get("/api/identity-collisions")
                .queryParam("recordType", "person")
                .queryParam("kind", "domain")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/identity-collisions")
                .queryParam("recordType", "deal")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/identity-collisions")
                .queryParam("page", "0")
                .queryParam("size", "101")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isBadRequest());
    }

    @Test
    void groupPaginationReturnsFirstMiddleAndExactOutOfRangePages() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace workspace = newWorkspace("identity-page");
        User reader = newMember(workspace, "member");
        for (String normalizedValue : List.of(
                "alpha.example",
                "beta.example",
                "gamma.example",
                "omega.example",
                "zeta.example")) {
            createCompanyDomainGroup(
                workspace,
                normalizedValue,
                List.of(normalizedValue + " one", normalizedValue + " two"));
        }
        rebuild(workspace.getId());
        MockHttpSession session = login(reader.getUsername());

        mockMvc.perform(get("/api/identity-collisions")
                .queryParam("recordType", "company")
                .queryParam("kind", "domain")
                .queryParam("page", "1")
                .queryParam("size", "2")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(5))
            .andExpect(jsonPath("$.items[0].normalizedValue").value("alpha.example"))
            .andExpect(jsonPath("$.items[1].normalizedValue").value("beta.example"));

        mockMvc.perform(get("/api/identity-collisions")
                .queryParam("recordType", "company")
                .queryParam("kind", "domain")
                .queryParam("page", "2")
                .queryParam("size", "2")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(5))
            .andExpect(jsonPath("$.items[0].normalizedValue").value("gamma.example"))
            .andExpect(jsonPath("$.items[1].normalizedValue").value("omega.example"));

        mockMvc.perform(get("/api/identity-collisions")
                .queryParam("recordType", "company")
                .queryParam("kind", "domain")
                .queryParam("page", "4")
                .queryParam("size", "2")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(5))
            .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void suspendedAndProvisionCeasedMembersSuppressTwoRecordGroups() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace workspace = newWorkspace("identity-restricted");
        User reader = newMember(workspace, "member");
        List<Person> suspended = createPersonEmailGroup(
            workspace,
            "suspended@example.com",
            List.of("Suspended One", "Suspended Two"),
            List.of("suspended-one@example.com", "suspended-two@example.com"));
        List<Person> ceased = createPersonEmailGroup(
            workspace,
            "ceased@example.com",
            List.of("Ceased One", "Ceased Two"),
            List.of("ceased-one@example.com", "ceased-two@example.com"));
        rebuild(workspace.getId());
        personMapper.updateProcessingRestrictions(
            workspace.getId(), suspended.getFirst().getId(), true, false);
        personMapper.updateProcessingRestrictions(
            workspace.getId(), ceased.getFirst().getId(), false, true);

        mockMvc.perform(get("/api/identity-collisions")
                .header("X-Workspace-Id", workspace.getId())
                .session(login(reader.getUsername())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(0))
            .andExpect(jsonPath("$.items").isEmpty())
            .andExpect(content().string(Matchers.not(Matchers.containsString("Suspended Two"))))
            .andExpect(content().string(Matchers.not(Matchers.containsString("Ceased Two"))));
    }

    @Test
    void unauthenticatedMemberPageRequestIsRejected() throws Exception {
        mockMvc.perform(memberQueryRequest(
                "company", "domain", "anything.example", 0, 50, true))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void memberPageRejectsUnsupportedMediaTypeWithExactSanitized415() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace workspace = newWorkspace("identity-member-media");
        User reader = newMember(workspace, "member");

        mockMvc.perform(post("/api/identity-collisions/members/query")
                .contentType(MediaType.TEXT_PLAIN)
                .content("secret non-json body")
                .header("X-Workspace-Id", workspace.getId())
                .session(login(reader.getUsername()))
                .with(csrf().asHeader()))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(content().string("Unsupported media type"));
    }

    @Test
    void memberPageRequiresCsrfReportReadAndValidatedJsonBody() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace workspace = newWorkspace("identity-member-rbac");
        User denied = newMember(workspace, "member");
        WorkspaceRole role = new WorkspaceRole();
        role.setWorkspaceId(workspace.getId());
        role.setName("Identity member denied " + suffix());
        roleMapper.insertRole(role);
        roleMapper.insertPermissions(workspace.getId(), role.getId(), List.of("GOAL_READ"));
        workspaceMapper.setMemberCustomRole(workspace.getId(), denied.getId(), role.getId());
        User reader = newMember(workspace, "member");

        mockMvc.perform(memberQueryRequest(
                "company", "domain", "anything.example", 0, 50, true)
                .header("X-Workspace-Id", workspace.getId())
                .session(login(denied.getUsername())))
            .andExpect(status().isForbidden());

        MockHttpSession session = login(reader.getUsername());
        mockMvc.perform(memberQueryRequest(
                "company", "domain", null, 0, 50, true)
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isBadRequest());
        mockMvc.perform(memberQueryRequest(
                "person", "domain", "anything.example", 0, 50, true)
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isBadRequest());
        mockMvc.perform(memberQueryRequest(
                "company", "domain", "anything.example", 0, 101, true)
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isBadRequest());

        mockMvc.perform(memberQueryRequest(
                "company", "domain", "anything.example", 0, 50, false)
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/identity-collisions/members/query")
                .queryParam("normalizedValue", "must-not-be-routed")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isMethodNotAllowed());

        mockMvc.perform(get("/api/identity-collisions/members")
                .queryParam("recordType", "company")
                .queryParam("kind", "domain")
                .queryParam("normalizedValue", "not-personal.example")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isNotFound());
    }

    @Test
    void oversizedGroupsFlagTruncationAndStayFullyReachableThroughTheMemberCursor() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace workspace = newWorkspace("identity-truncated");
        User reader = newMember(workspace, "member");
        List<String> names = new java.util.ArrayList<>();
        for (int index = 0; index < 25; index++) {
            names.add("Crowded Company " + index);
        }
        createCompanyDomainGroup(workspace, "crowded.example", names);
        rebuild(workspace.getId());
        MockHttpSession session = login(reader.getUsername());
        List<Integer> memberIds = jdbcTemplate.queryForList(
            """
            SELECT company_id
            FROM company_identity
            WHERE workspace_id = ? AND kind = 'domain' AND normalized_value = 'crowded.example'
            ORDER BY company_id
            """,
            Integer.class,
            workspace.getId());

        mockMvc.perform(get("/api/identity-collisions")
                .queryParam("recordType", "company")
                .queryParam("kind", "domain")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].collisionSize").value(25))
            .andExpect(jsonPath("$.items[0].members.length()").value(20))
            .andExpect(jsonPath("$.items[0].membersTruncated").value(true))
            .andExpect(jsonPath("$.items[0].members[0].recordId").value(memberIds.getFirst()))
            .andExpect(jsonPath("$.items[0].members[19].recordId").value(memberIds.get(19)));

        mockMvc.perform(memberQueryRequest(
                "company", "domain", "crowded.example", 0, 20, true)
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").doesNotExist())
            .andExpect(jsonPath("$.hasMore").value(true))
            .andExpect(jsonPath("$.nextAfterRecordId").value(memberIds.get(19)))
            .andExpect(jsonPath("$.items.length()").value(20))
            .andExpect(jsonPath("$.items[0].recordId").value(memberIds.getFirst()))
            .andExpect(jsonPath("$.items[19].recordId").value(memberIds.get(19)));

        mockMvc.perform(memberQueryRequest(
                "company", "domain", "crowded.example", memberIds.get(19), 20, true)
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").doesNotExist())
            .andExpect(jsonPath("$.hasMore").value(false))
            .andExpect(jsonPath("$.nextAfterRecordId").value(Matchers.nullValue()))
            .andExpect(jsonPath("$.items.length()").value(5))
            .andExpect(jsonPath("$.items[0].recordId").value(memberIds.get(20)))
            .andExpect(jsonPath("$.items[4].recordId").value(memberIds.get(24)))
            .andExpect(content().string(Matchers.containsString("Crowded Company 24")));
    }

    @Test
    void continuationWithOneRowSuffixRemainsVisibleWhenTheGroupStillCollides() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace workspace = newWorkspace("identity-one-row-suffix");
        User reader = newMember(workspace, "member");
        createCompanyDomainGroup(
            workspace,
            "one-row-suffix.example",
            List.of("Suffix Company One", "Suffix Company Two", "Suffix Company Three"));
        rebuild(workspace.getId());
        List<Integer> memberIds = jdbcTemplate.queryForList(
            """
            SELECT company_id
            FROM company_identity
            WHERE workspace_id = ?
              AND kind = 'domain'
              AND normalized_value = 'one-row-suffix.example'
            ORDER BY company_id
            """,
            Integer.class,
            workspace.getId());

        mockMvc.perform(memberQueryRequest(
                "company",
                "domain",
                "one-row-suffix.example",
                memberIds.get(1),
                50,
                true)
                .header("X-Workspace-Id", workspace.getId())
                .session(login(reader.getUsername())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].recordId").value(memberIds.get(2)))
            .andExpect(jsonPath("$.hasMore").value(false))
            .andExpect(jsonPath("$.nextAfterRecordId").value(Matchers.nullValue()));
    }

    @Test
    void memberPagesNeverCrossWorkspaces() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace current = newWorkspace("identity-member-current");
        Workspace other = newWorkspace("identity-member-foreign");
        User reader = newMember(current, "member");
        workspaceMapper.addMember(other.getId(), reader.getId(), "member");
        createCompanyDomainGroup(
            current, "shared-value.example", List.of("Local Member One", "Local Member Two"));
        createCompanyDomainGroup(
            other,
            "shared-value.example",
            List.of("Foreign Member One", "Foreign Member Two", "Foreign Member Three"));
        rebuild(current.getId());
        rebuild(other.getId());
        MockHttpSession session = login(reader.getUsername());

        mockMvc.perform(memberQueryRequest(
                "company", "domain", "shared-value.example", 0, 50, true)
                .header("X-Workspace-Id", current.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hasMore").value(false))
            .andExpect(jsonPath("$.nextAfterRecordId").value(Matchers.nullValue()))
            .andExpect(content().string(Matchers.containsString("Local Member One")))
            .andExpect(content().string(Matchers.not(Matchers.containsString("Foreign Member"))));

        mockMvc.perform(memberQueryRequest(
                "company", "domain", "shared-value.example", 0, 50, true)
                .header("X-Workspace-Id", other.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(3))
            .andExpect(jsonPath("$.hasMore").value(false))
            .andExpect(content().string(Matchers.not(Matchers.containsString("Local Member"))));
    }

    @Test
    void memberPagesStaySilentForSuppressedAndNonCollidingValues() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace workspace = newWorkspace("identity-member-suppressed");
        User reader = newMember(workspace, "member");
        List<Person> restricted = createPersonEmailGroup(
            workspace,
            "member-suppressed@example.com",
            List.of("Member Suppressed One", "Member Suppressed Two"),
            List.of("member-suppressed-one@example.com", "member-suppressed-two@example.com"));
        rebuild(workspace.getId());
        personMapper.updateProcessingRestrictions(
            workspace.getId(), restricted.getFirst().getId(), true, false);
        MockHttpSession session = login(reader.getUsername());

        mockMvc.perform(memberQueryRequest(
                "person", "email", "member-suppressed@example.com", 0, 50, true)
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isEmpty())
            .andExpect(jsonPath("$.hasMore").value(false))
            .andExpect(jsonPath("$.nextAfterRecordId").value(Matchers.nullValue()))
            .andExpect(content().string(Matchers.not(Matchers.containsString("Member Suppressed Two"))));

        mockMvc.perform(memberQueryRequest(
                "company", "domain", "never-collided.example", 0, 50, true)
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isEmpty())
            .andExpect(jsonPath("$.hasMore").value(false))
            .andExpect(jsonPath("$.nextAfterRecordId").value(Matchers.nullValue()));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void memberContinuationReappliesCommittedProcessingRestrictionsOnEveryRequest()
            throws Exception {
        RequestContextHolder.resetRequestAttributes();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CommittedRestrictionFixture fixture = Objects.requireNonNull(
            transaction.execute(status -> {
                Workspace workspace = newWorkspace("identity-live-restriction");
                User reader = newMember(workspace, "member");
                List<Person> people = createPersonEmailGroup(
                    workspace,
                    "live-restriction@example.com",
                    List.of("Live Restriction One", "Live Restriction Two",
                        "Live Restriction Three"),
                    List.of("live-one@example.com", "live-two@example.com",
                        "live-three@example.com"));
                rebuild(workspace.getId());
                return new CommittedRestrictionFixture(workspace, reader, people);
            }),
            "committed restriction fixture");
        try {
            MockHttpSession session = login(fixture.reader().getUsername());
            Person first = fixture.people().getFirst();
            Person second = fixture.people().get(1);
            Person third = fixture.people().get(2);

            mockMvc.perform(memberQueryRequest(
                    "person", "email", "live-restriction@example.com", 0, 1, true)
                    .header("X-Workspace-Id", fixture.workspace().getId())
                    .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].recordId").value(first.getId()))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.nextAfterRecordId").value(first.getId()));

            transaction.executeWithoutResult(status ->
                personMapper.updateProcessingRestrictions(
                    fixture.workspace().getId(), second.getId(), true, false));

            mockMvc.perform(memberQueryRequest(
                    "person",
                    "email",
                    "live-restriction@example.com",
                    first.getId(),
                    10,
                    true)
                    .header("X-Workspace-Id", fixture.workspace().getId())
                    .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].recordId").value(third.getId()))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(content().string(
                    Matchers.not(Matchers.containsString("Live Restriction Two"))));

            transaction.executeWithoutResult(status ->
                personMapper.updateProcessingRestrictions(
                    fixture.workspace().getId(), first.getId(), false, true));

            mockMvc.perform(memberQueryRequest(
                    "person",
                    "email",
                    "live-restriction@example.com",
                    first.getId(),
                    50,
                    true)
                    .header("X-Workspace-Id", fixture.workspace().getId())
                    .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.nextAfterRecordId").value(Matchers.nullValue()));
        } finally {
            transaction.executeWithoutResult(status -> deleteFixture(fixture));
        }
    }

    private void rebuild(int workspaceId) {
        collisionMapper.deleteForWorkspace(workspaceId);
        collisionMapper.insertPersonCollisionMembers(workspaceId, REBUILT_AT);
        collisionMapper.insertCompanyCollisionMembers(workspaceId, REBUILT_AT);
    }

    private List<Person> createPersonEmailGroup(
            Workspace workspace,
            String normalizedValue,
            List<String> names,
            List<String> rawValues) {
        Company company = newCompany(workspace, "https://" + suffix() + ".example.com");
        java.util.ArrayList<Person> people = new java.util.ArrayList<>();
        for (int index = 0; index < names.size(); index++) {
            Person person = newPerson(
                workspace,
                company,
                names.get(index),
                rawValues.get(index),
                "090-1234-" + String.format("%04d", index + 1));
            insertPersonIdentity(person, rawValues.get(index), normalizedValue);
            people.add(person);
        }
        return List.copyOf(people);
    }

    private void createCompanyDomainGroup(
            Workspace workspace,
            String normalizedValue,
            List<String> names) {
        for (int index = 0; index < names.size(); index++) {
            Company company = newCompany(
                workspace,
                "https://" + index + "." + normalizedValue);
            company.setName(names.get(index));
            companyMapper.update(company);
            jdbcTemplate.update(
                """
                INSERT INTO company_identity (
                  workspace_id, company_id, kind, `value`, normalized_value,
                  source_system, source_channel, acquired_at
                )
                VALUES (?, ?, 'domain', ?, ?, 'csv_import', 'company.website', CURRENT_TIMESTAMP)
                """,
                workspace.getId(),
                company.getId(),
                company.getWebsite(),
                normalizedValue);
        }
    }

    private void insertPersonIdentity(
            Person person, String rawValue, String normalizedValue) {
        jdbcTemplate.update(
            """
            INSERT INTO person_identity (
              workspace_id, person_id, kind, `value`, normalized_value,
              source_system, source_channel, source_row_ref, acquired_at
            )
            VALUES (?, ?, 'email', ?, ?, 'csv_import', 'person.email', ?, CURRENT_TIMESTAMP)
            """,
            person.getWorkspaceId(),
            person.getId(),
            rawValue,
            normalizedValue,
            "person:" + person.getId());
    }

    private Workspace newWorkspace(String prefix) {
        String suffix = suffix();
        Workspace workspace = new Workspace();
        workspace.setName(prefix + "-" + suffix);
        workspace.setSlug(prefix + "-" + suffix);
        workspaceMapper.insert(workspace);
        return workspace;
    }

    private User newMember(Workspace workspace, String role) {
        String suffix = suffix();
        User user = new User();
        user.setUsername("identity_" + suffix);
        user.setDisplayName("Identity " + suffix);
        user.setEmail(suffix + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setTimezone("UTC");
        userMapper.insert(user);
        workspaceMapper.addMember(workspace.getId(), user.getId(), role);
        return user;
    }

    private Company newCompany(Workspace workspace, String website) {
        Company company = new Company();
        company.setWorkspaceId(workspace.getId());
        company.setName("Company " + suffix());
        company.setWebsite(website);
        company.setIndustry("Tech");
        company.setPhone("+81-90-1234-5678");
        company.setAddress("Tokyo");
        companyMapper.insert(company);
        return company;
    }

    private Person newPerson(
            Workspace workspace,
            Company company,
            String name,
            String email,
            String phone) {
        Person person = new Person();
        person.setWorkspaceId(workspace.getId());
        person.setName(name);
        person.setEmail(email);
        person.setPhone(phone);
        person.setCompany(company);
        person.setTitle("Engineer");
        personMapper.insert(person);
        return person;
    }

    private MockHttpServletRequestBuilder memberQueryRequest(
            String recordType,
            String kind,
            String normalizedValue,
            int afterRecordId,
            int size,
            boolean withCsrf) {
        IdentityCollisionMemberQuery query = new IdentityCollisionMemberQuery();
        query.setRecordType(recordType);
        query.setKind(kind);
        query.setNormalizedValue(normalizedValue);
        query.setAfterRecordId(afterRecordId);
        query.setSize(size);
        MockHttpServletRequestBuilder request =
            post("/api/identity-collisions/members/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(query));
        return withCsrf ? request.with(csrf().asHeader()) : request;
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

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private void deleteFixture(CommittedRestrictionFixture fixture) {
        int workspaceId = fixture.workspace().getId();
        jdbcTemplate.update("DELETE FROM identity_collision WHERE workspace_id = ?", workspaceId);
        jdbcTemplate.update("DELETE FROM person_identity WHERE workspace_id = ?", workspaceId);
        jdbcTemplate.update("DELETE FROM person WHERE workspace_id = ?", workspaceId);
        jdbcTemplate.update("DELETE FROM company WHERE workspace_id = ?", workspaceId);
        jdbcTemplate.update("DELETE FROM workspace_member WHERE workspace_id = ?", workspaceId);
        jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", workspaceId);
        jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", fixture.reader().getId());
    }

    private record CommittedRestrictionFixture(
        Workspace workspace,
        User reader,
        List<Person> people) {
    }
}
