package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

/** Exercises filtered page SQL through real session, workspace resolution and HTTP validation. */
@SpringBootTest
@Transactional
class ActivityListPagingIntegrationTest {
    private static final String PASSWORD = "List-Page-Pw1!";
    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter security;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private CompanyMapper companyMapper;
    @Autowired private PersonMapper personMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private ooo.klae.connex.backend.mappers.PipelineMapper pipelineMapper;
    @Autowired private ooo.klae.connex.backend.mappers.DealMapper dealMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private org.mybatis.spring.SqlSessionTemplate sqlSession;
    private MockMvc mvc;
    private Workspace workspace;
    private Workspace sibling;
    private User actor;
    private Company company;
    private Person person;
    private Person other;

    @BeforeEach
    void setUp() {
        RequestContextHolder.resetRequestAttributes();
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
        Organization org = new Organization();
        org.setName("List test " + unique());
        org.setSlug("list-org-" + unique());
        organizationMapper.insert(org);
        workspace = workspace(org);
        sibling = workspace(org);
        actor = new User();
        actor.setUsername("list_" + unique());
        actor.setDisplayName("List actor");
        actor.setEmail(unique() + "@example.com");
        actor.setPasswordHash(passwordEncoder.encode(PASSWORD));
        actor.setTimezone("Asia/Tokyo");
        userMapper.insert(actor);
        workspaceMapper.addMember(workspace.getId(), actor.getId(), "member");
        workspaceMapper.addMember(sibling.getId(), actor.getId(), "member");
        company = company(workspace);
        person = person(workspace, company);
        other = person(workspace, company(workspace));
    }

    @Test
    void listsAndMemberOptionsRequireAuthenticationAndWorkspaceMembership() throws Exception {
        for (String path : List.of("/api/activities/page", "/api/tasks/page", "/api/users/options", "/api/persons/references")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).session(login()).header("X-Workspace-Id", Integer.MAX_VALUE))
                .andExpect(status().isForbidden());
        }
    }

    @Test
    void recordContextAndIntersectingFiltersApplyBeforePagingAndCounting() throws Exception {
        activity(workspace, person, "Match one", "Call");
        activity(workspace, person, "Match two", "Call");
        activity(workspace, person, "Different type", "Email");
        activity(workspace, other, "Unrelated", "Call");
        activity(sibling, person, "Match other workspace", "Call");
        task(workspace, person, "Match one", false, "2000-01-01");
        task(workspace, person, "Match two", false, "2000-01-01");
        task(workspace, other, "Unrelated", false, "2000-01-01");
        task(sibling, person, "Match other workspace", false, "2000-01-01");
        var session = login();
        for (String kind : List.of("activities", "tasks")) {
            var first = mvc.perform(get("/api/" + kind + "/page").session(session)
                    .header("X-Workspace-Id", workspace.getId()).param("companyId", String.valueOf(company.getId()))
                    .param("persons", String.valueOf(person.getId())).param("q", "Match")
                    .param("type", "Call").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items.length()").value(1)).andReturn();
            String firstBody = first.getResponse().getContentAsString();
            var second = mvc.perform(get("/api/" + kind + "/page").session(session)
                    .header("X-Workspace-Id", workspace.getId()).param("companyId", String.valueOf(company.getId()))
                    .param("persons", String.valueOf(person.getId())).param("q", "Match")
                    .param("type", "Call").param("size", "1").param("page", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items.length()").value(1)).andReturn();
            org.junit.jupiter.api.Assertions.assertNotEquals(firstBody, second.getResponse().getContentAsString());
            mvc.perform(get("/api/" + kind + "/page").session(session).header("X-Workspace-Id", sibling.getId())
                    .param("personId", String.valueOf(person.getId())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        }
    }

    @Test
    void companyContextIncludesDealOnlyLinksWithoutCountingDualLinksTwice() throws Exception {
        var pipeline = new ooo.klae.connex.backend.beans.Pipeline();
        pipeline.setWorkspaceId(workspace.getId());
        pipeline.setName("List pipeline");
        pipelineMapper.insertPipeline(pipeline);
        var stage = new ooo.klae.connex.backend.beans.Stage();
        stage.setWorkspaceId(workspace.getId());
        stage.setPipeline(pipeline);
        stage.setName("List stage");
        stage.setPosition(0);
        pipelineMapper.insertStage(stage);
        var deal = new ooo.klae.connex.backend.beans.Deal();
        deal.setWorkspaceId(workspace.getId());
        deal.setName("List deal");
        deal.setCompanyId(company.getId());
        deal.setPipelineId(pipeline.getId());
        deal.setStageId(stage.getId());
        deal.setValue(java.math.BigDecimal.ONE);
        deal.setCurrency("JPY");
        dealMapper.insert(deal);
        for (Integer contactId : java.util.Arrays.asList(null, person.getId())) {
            jdbc.update("INSERT INTO activity(workspace_id, created_by_id, person_id, deal_id, type, subject) VALUES (?, ?, ?, ?, 'Call', 'Deal link')",
                workspace.getId(), actor.getId(), contactId, deal.getId());
            jdbc.update("INSERT INTO task(workspace_id, assigned_to_id, person_id, deal_id, description, completed, status) VALUES (?, ?, ?, ?, 'Deal link', FALSE, 'todo')",
                workspace.getId(), actor.getId(), contactId, deal.getId());
        }
        var session = login();
        for (String kind : List.of("activities", "tasks")) {
            mvc.perform(get("/api/" + kind + "/page").session(session).header("X-Workspace-Id", workspace.getId())
                    .param("companyId", String.valueOf(company.getId())).param("dealId", String.valueOf(deal.getId())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items.length()").value(2));
        }
    }

    @Test
    void memberPickerReadsAreBoundedAndExcludePendingAndOtherWorkspaceMembers() throws Exception {
        for (int i = 0; i < 55; i++) {
            User member = new User();
            member.setUsername("option_" + unique());
            member.setDisplayName("Picker " + i);
            member.setEmail(unique() + "@example.com");
            member.setPasswordHash(actor.getPasswordHash());
            userMapper.insert(member);
            workspaceMapper.addMember(i == 54 ? sibling.getId() : workspace.getId(), member.getId(), "member");
            if (i == 53) jdbc.update("UPDATE workspace_member SET status = 'pending' WHERE workspace_id = ? AND user_id = ?",
                workspace.getId(), member.getId());
        }
        var session = login();
        mvc.perform(get("/api/users/options").session(session).header("X-Workspace-Id", workspace.getId()).param("q", "Picker"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(50));
        for (String label : List.of("Picker 53", "Picker 54")) {
            mvc.perform(get("/api/users/options").session(session).header("X-Workspace-Id", workspace.getId()).param("q", label))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        }
    }

    @Test
    void memberPickerDoesNotDisclosePendingProfilesThroughEitherSearchField() throws Exception {
        User pending = new User();
        pending.setUsername("pending_" + unique());
        pending.setDisplayName("Invited profile " + unique());
        pending.setEmail(unique() + "@example.com");
        pending.setPasswordHash(actor.getPasswordHash());
        userMapper.insert(pending);
        workspaceMapper.addMember(workspace.getId(), pending.getId(), "member");
        workspaceMapper.addMember(sibling.getId(), pending.getId(), "member");
        jdbc.update("UPDATE workspace_member SET status = 'pending' WHERE workspace_id = ? AND user_id = ?",
            workspace.getId(), pending.getId());
        var session = login();
        for (String query : List.of(pending.getDisplayName(), pending.getUsername())) {
            mvc.perform(get("/api/users/options").session(session).header("X-Workspace-Id", workspace.getId())
                    .param("q", query))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        }
        mvc.perform(get("/api/users/options").session(session).header("X-Workspace-Id", workspace.getId()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(actor.getId()));
        mvc.perform(get("/api/users/options").session(session).header("X-Workspace-Id", sibling.getId())
                .param("q", pending.getUsername()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(pending.getId()));
    }

    /** Fresh HTTP reads do not share the rollback fixture's MyBatis cache after direct JDBC changes. */
    @Test
    void archivedAndUnsharedLinkedContactsAreExcludedFromRowsAndCounts() throws Exception {
        Person shared = person(sibling, company(sibling));
        jdbc.update("INSERT INTO person_share(person_id, workspace_id, granted_by) VALUES (?, ?, ?)",
            shared.getId(), workspace.getId(), actor.getId());
        activity(workspace, shared, "Shared", "Call");
        task(workspace, shared, "Shared", false, "2000-01-01");
        var session = login();
        for (String kind : List.of("activities", "tasks")) {
            mvc.perform(get("/api/" + kind + "/page").session(session).header("X-Workspace-Id", workspace.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        }
        jdbc.update("DELETE FROM person_share WHERE person_id = ? AND workspace_id = ?", shared.getId(), workspace.getId());
        activity(workspace, person, "Archived", "Call");
        task(workspace, person, "Archived", false, "2000-01-01");
        jdbc.update("UPDATE person SET archived_at = CURRENT_TIMESTAMP WHERE id = ?", person.getId());
        sqlSession.clearCache();
        for (String kind : List.of("activities", "tasks")) {
            mvc.perform(get("/api/" + kind + "/page").session(session).header("X-Workspace-Id", workspace.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.items").isEmpty());
        }
    }

    @Test
    void taskQueueUsesTheActorsCalendarAndKeepsContextWhenSwitchingQueues() throws Exception {
        String today = LocalDate.now(ZoneId.of("Asia/Tokyo")).toString();
        task(workspace, person, "Today", false, today);
        task(workspace, person, "Overdue", false, "2000-01-01");
        task(workspace, person, "Completed", true, today);
        task(workspace, other, "Other company", false, today);
        for (String queue : List.of("dueToday", "overdue", "completed")) {
            mvc.perform(get("/api/tasks/page").session(login()).header("X-Workspace-Id", workspace.getId())
                    .param("companyId", String.valueOf(company.getId())).param("queue", queue))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        }
    }

    @Test
    void bodySearchTreatsWildcardsLiterallyAndDoesNotMatchInternalReferenceLabels() throws Exception {
        activity(workspace, person, "Body search", "Call");
        jdbc.update("UPDATE activity SET notes = 'Body only 100%_ [Hidden target](person:999999)' WHERE workspace_id = ?",
            workspace.getId());
        task(workspace, person, "Body only 100%_ [Hidden target](person:999999)", false, "2000-01-01");
        var session = login();
        for (String kind : List.of("activities", "tasks")) {
            mvc.perform(get("/api/" + kind + "/page").session(session).header("X-Workspace-Id", workspace.getId())
                    .param("q", "100%_"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
            mvc.perform(get("/api/" + kind + "/page").session(session).header("X-Workspace-Id", workspace.getId())
                    .param("q", "Hidden target"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        }
    }

    @Test
    void queryValidationFailsBeforeAnUnboundedOrMalformedRead() throws Exception {
        var session = login();
        for (String kind : List.of("activities", "tasks")) {
            for (String key : List.of("companyId", "personId", "dealId")) {
                mvc.perform(get("/api/" + kind + "/page").session(session).header("X-Workspace-Id", workspace.getId())
                        .param(key, "-1")).andExpect(status().isBadRequest());
            }
            mvc.perform(get("/api/" + kind + "/page").session(session).header("X-Workspace-Id", workspace.getId())
                    .param("persons", IntStream.rangeClosed(1, 51).mapToObj(String::valueOf).toArray(String[]::new)))
                .andExpect(status().isBadRequest());
            mvc.perform(get("/api/" + kind + "/page").session(session).header("X-Workspace-Id", workspace.getId())
                    .param("page", "1002").param("size", "100")).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/tasks/page").session(session).header("X-Workspace-Id", workspace.getId()).param("queue", "unknown"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/users/options").session(session).header("X-Workspace-Id", workspace.getId()).param("q", "x".repeat(201)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void contactReferencesOmitHiddenArchivedAndOtherOrganizationIdsAndMaskHiddenEmployers() throws Exception {
        Person hidden = person(sibling, company(sibling));
        Person shared = person(sibling, company(sibling));
        Person archived = person(workspace, company);
        Organization alienOrg = new Organization();
        alienOrg.setName("Reference organization " + unique());
        alienOrg.setSlug("reference-org-" + unique());
        organizationMapper.insert(alienOrg);
        Workspace alienWorkspace = workspace(alienOrg);
        Person alien = person(alienWorkspace, company(alienWorkspace));
        for (Person contact : List.of(shared, alien)) {
            jdbc.update("INSERT INTO person_share(person_id, workspace_id, granted_by) VALUES (?, ?, ?)",
                contact.getId(), workspace.getId(), actor.getId());
        }
        jdbc.update("UPDATE person SET archived_at = CURRENT_TIMESTAMP WHERE id = ?", archived.getId());
        sqlSession.clearCache();

        mvc.perform(get("/api/persons/references").session(login()).header("X-Workspace-Id", workspace.getId())
                .param("ids", List.of(person, hidden, shared, archived, alien).stream()
                    .map(contact -> String.valueOf(contact.getId())).toArray(String[]::new)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[?(@.id == " + person.getId() + ")].companyId").value(List.of(company.getId())))
            .andExpect(jsonPath("$[?(@.id == " + shared.getId() + ")].id").value(List.of(shared.getId())))
            .andExpect(jsonPath("$[?(@.id == " + shared.getId() + ")].companyId").isEmpty());
        mvc.perform(get("/api/persons/references").session(login()).header("X-Workspace-Id", sibling.getId())
                .param("ids", String.valueOf(person.getId())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void contactReferencesExposeOnlyIdentityAndVisibleEmployer() throws Exception {
        jdbc.update("UPDATE person SET email = 'private@example.test', phone = 'private phone', qualification_notes = 'Private qualification', lead_source = 'REFERRAL', lead_source_detail = 'Private source', referrer_person_id = ? WHERE id = ?",
            other.getId(), person.getId());
        sqlSession.clearCache();
        mvc.perform(get("/api/persons/references").session(login()).header("X-Workspace-Id", workspace.getId())
                .param("ids", String.valueOf(person.getId())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].length()").value(3))
            .andExpect(jsonPath("$[0].id").value(person.getId()))
            .andExpect(jsonPath("$[0].name").value(person.getName()))
            .andExpect(jsonPath("$[0].companyId").value(company.getId()))
            .andExpect(jsonPath("$[0].email").doesNotExist()).andExpect(jsonPath("$[0].phone").doesNotExist())
            .andExpect(jsonPath("$[0].qualificationNotes").doesNotExist()).andExpect(jsonPath("$[0].leadSourceDetail").doesNotExist())
            .andExpect(jsonPath("$[0].referrerPersonId").doesNotExist()).andExpect(jsonPath("$[0].company").doesNotExist());
    }

    @Test
    void contactReferenceIdsAreRequiredPositiveAndCappedBeforeDeduplication() throws Exception {
        var session = login();
        mvc.perform(get("/api/persons/references").session(session).header("X-Workspace-Id", workspace.getId()))
            .andExpect(status().isBadRequest());
        for (String invalid : List.of("", "0", "-1", "invalid")) {
            mvc.perform(get("/api/persons/references").session(session).header("X-Workspace-Id", workspace.getId()).param("ids", invalid))
                .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/persons/references").session(session).header("X-Workspace-Id", workspace.getId())
                .param("ids", java.util.Collections.nCopies(101, String.valueOf(person.getId())).toArray(String[]::new)))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/persons/references").session(session).header("X-Workspace-Id", workspace.getId())
                .param("ids", java.util.Collections.nCopies(100, String.valueOf(person.getId())).toArray(String[]::new)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void twentyThousandActivitiesReturnOnlyTheRequestedPage() throws Exception {
        jdbc.batchUpdate("INSERT INTO activity(workspace_id, created_by_id, person_id, type, subject, timestamp) VALUES (?, ?, ?, 'Call', 'Paging benchmark', '2026-01-01 12:00:00')",
            IntStream.range(0, 20_000).mapToObj(i -> new Object[] {workspace.getId(), actor.getId(), person.getId()}).toList());
        var session = login();
        var response = mvc.perform(get("/api/activities/page").session(session).header("X-Workspace-Id", workspace.getId()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(20_000))
            .andExpect(jsonPath("$.items.length()").value(25)).andReturn().getResponse();
        assertTrue(response.getContentAsByteArray().length <= 32_768,
            "The 25-row fixture response must fit within 32 KiB");
        task(workspace, person, "Historical task", true, "2000-01-01");
        var reference = mvc.perform(get("/api/persons/references").session(session).header("X-Workspace-Id", workspace.getId())
                .param("ids", String.valueOf(person.getId())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].companyId").value(company.getId()))
            .andExpect(jsonPath("$[0].activities").doesNotExist()).andExpect(jsonPath("$[0].activityIds").doesNotExist())
            .andExpect(jsonPath("$[0].tasks").doesNotExist()).andExpect(jsonPath("$[0].taskIds").doesNotExist())
            .andExpect(jsonPath("$[0].deals").doesNotExist()).andExpect(jsonPath("$[0].dealIds").doesNotExist())
            .andReturn().getResponse();
        assertTrue(reference.getContentAsByteArray().length <= 4096,
            "Contact hydration must remain below 4 KiB despite the 20,000 linked activities");
        mvc.perform(get("/api/activities/page").session(session).header("X-Workspace-Id", workspace.getId()).param("size", "500"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(100));
    }

    private void activity(Workspace owner, Person contact, String subject, String type) {
        jdbc.update("INSERT INTO activity(workspace_id, created_by_id, person_id, type, subject, timestamp) VALUES (?, ?, ?, ?, ?, '2026-01-01 12:00:00')",
            owner.getId(), actor.getId(), contact.getId(), type, subject);
    }

    private void task(Workspace owner, Person contact, String description, boolean completed, String due) {
        jdbc.update("INSERT INTO task(workspace_id, assigned_to_id, person_id, description, completed, status, due_date) VALUES (?, ?, ?, ?, ?, ?, ?)",
            owner.getId(), actor.getId(), contact.getId(), description, completed, completed ? "done" : "todo", due);
    }

    private Workspace workspace(Organization org) {
        Workspace result = new Workspace();
        result.setOrgId(org.getId());
        result.setName("List workspace " + unique());
        result.setSlug("list-workspace-" + unique());
        workspaceMapper.insert(result);
        return result;
    }

    private Company company(Workspace owner) {
        Company result = new Company();
        result.setWorkspaceId(owner.getId());
        result.setName("List company " + unique());
        companyMapper.insert(result);
        return result;
    }

    private Person person(Workspace owner, Company employer) {
        Person result = new Person();
        result.setWorkspaceId(owner.getId());
        result.setName("List contact " + unique());
        result.setCompany(employer);
        personMapper.insert(result);
        return result;
    }

    private MockHttpSession login() throws Exception {
        var result = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + actor.getUsername() + "\",\"password\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isOk()).andReturn();
        var session = result.getRequest().getSession(false);
        assertNotNull(session);
        if (!(session instanceof MockHttpSession mock)) throw new IllegalStateException("Expected mock session");
        return mock;
    }

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
