package ooo.klae.connex.backend.integration;

import java.math.BigDecimal;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.RelationshipEvidenceRowDto;
import ooo.klae.connex.backend.dto.RelationshipEvidenceTotalsDto;
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.ShareMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.warmth.RelationshipWarmthModel;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Exercises the evidence endpoint through the real security chain, tenant resolver, service, and
 * mapper layers so source metadata cannot become a cross-workspace or private-note side channel.
 */
@SpringBootTest
@Transactional
class RelationshipEvidenceSecurityIntegrationTest {
    private static final String PASSWORD = "Evidence-Test-Pw1!";
    private static final DateTimeFormatter MYSQL_DATETIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private ActivityMapper activityMapper;
    @Autowired private CompanyMapper companyMapper;
    @Autowired private DealMapper dealMapper;
    @Autowired private NoteMapper noteMapper;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private PersonMapper personMapper;
    @Autowired private PipelineMapper pipelineMapper;
    @Autowired private ShareMapper shareMapper;
    @Autowired private TaskMapper taskMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private WorkspaceMapper workspaceMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        RequestContextHolder.resetRequestAttributes();
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(springSecurityFilterChain)
            .build();
    }

    @Test
    void evidenceEndpointsEnforceAuthenticationWorkspaceMembershipAndTenantIsolation() throws Exception {
        Workspace ownerWorkspace = newWorkspace();
        Workspace otherWorkspace = newWorkspace();
        User owner = newMember(ownerWorkspace);
        User outsider = newMember(otherWorkspace);
        Company company = newCompany(ownerWorkspace);
        Person person = newPerson(ownerWorkspace, company);
        activity(ownerWorkspace, owner, person, 1);

        mockMvc.perform(get(contactEvidencePath(person)))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get(companyEvidencePath(company)))
            .andExpect(status().isUnauthorized());

        MockHttpSession ownerSession = login(owner.getUsername());
        mockMvc.perform(get(contactEvidencePath(person))
                .header("X-Workspace-Id", ownerWorkspace.getId())
                .session(ownerSession))
            .andExpect(status().isOk());
        mockMvc.perform(get(companyEvidencePath(company))
                .header("X-Workspace-Id", ownerWorkspace.getId())
                .session(ownerSession))
            .andExpect(status().isOk());

        mockMvc.perform(get(contactEvidencePath(person))
                .header("X-Workspace-Id", otherWorkspace.getId())
                .session(ownerSession))
            .andExpect(status().isForbidden());
        mockMvc.perform(get(companyEvidencePath(company))
                .header("X-Workspace-Id", otherWorkspace.getId())
                .session(ownerSession))
            .andExpect(status().isForbidden());

        MockHttpSession outsiderSession = login(outsider.getUsername());
        mockMvc.perform(get(contactEvidencePath(person))
                .header("X-Workspace-Id", otherWorkspace.getId())
                .session(outsiderSession))
            .andExpect(status().isNotFound());
        mockMvc.perform(get(companyEvidencePath(company))
                .header("X-Workspace-Id", otherWorkspace.getId())
                .session(outsiderSession))
            .andExpect(status().isNotFound());
    }

    @Test
    void evidenceReturnsEligibleMetadataAndOnlyTheCallersPrivateNoteCount() throws Exception {
        Workspace workspace = newWorkspace();
        User caller = newMember(workspace);
        User colleague = newMember(workspace);
        Company company = newCompany(workspace);
        Person person = newPerson(workspace, company);
        Activity activity = activity(workspace, caller, person, 1);
        Note workspaceNote = note(workspace, caller, person, "workspace");
        Note callerPrivate = note(workspace, caller, person, "private");
        Note colleaguePrivateOne = note(workspace, colleague, person, "private");
        Note colleaguePrivateTwo = note(workspace, colleague, person, "private");
        Task task = task(workspace, caller, person);

        MockHttpSession callerSession = login(caller.getUsername());
        MvcResult contactEvidence = mockMvc.perform(get(contactEvidencePath(person))
                .header("X-Workspace-Id", workspace.getId())
                .session(callerSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.subjectType").value("person"))
            .andExpect(jsonPath("$.subjectId").value(person.getId()))
            .andExpect(jsonPath("$.asOf").exists())
            .andExpect(jsonPath("$.temperature.asOf").exists())
            .andExpect(jsonPath("$.temperature.modelVersion").value("warmth-v1"))
            .andExpect(jsonPath("$.attributionRule").value("direct_person_touches"))
            .andExpect(jsonPath("$.contributors", hasSize(3)))
            .andExpect(jsonPath("$.contributors[*].sourceType",
                hasItems("activity", "note", "task")))
            .andExpect(jsonPath("$.contributors[?(@.sourceType == 'activity')].sourceId",
                hasItem(activity.getId())))
            .andExpect(jsonPath("$.contributors[?(@.sourceType == 'note')].sourceId",
                hasItem(workspaceNote.getId())))
            .andExpect(jsonPath("$.contributors[?(@.sourceType == 'note')].sourceId",
                not(hasItem(callerPrivate.getId()))))
            .andExpect(jsonPath("$.contributors[?(@.sourceType == 'note')].sourceId",
                not(hasItem(colleaguePrivateOne.getId()))))
            .andExpect(jsonPath("$.contributors[?(@.sourceType == 'note')].sourceId",
                not(hasItem(colleaguePrivateTwo.getId()))))
            .andExpect(jsonPath("$.contributors[?(@.sourceType == 'task')].sourceId",
                hasItem(task.getId())))
            .andExpect(jsonPath("$.contributors[*].content").doesNotExist())
            .andExpect(jsonPath("$.totals.contributorCount").value(3))
            .andExpect(jsonPath("$.totals.sourceCounts.activities").value(1))
            .andExpect(jsonPath("$.totals.sourceCounts.notes").value(1))
            .andExpect(jsonPath("$.totals.sourceCounts.tasks").value(1))
            .andExpect(jsonPath("$.coverage.limitedEvidence").value(false))
            .andExpect(jsonPath("$.coverage.callerPrivateNotesExcluded").value(1))
            .andExpect(jsonPath("$.coverage.privateNoteCountScope").value("current_caller_only"))
            .andReturn();
        assertEvidenceMath(objectMapper.readTree(
            contactEvidence.getResponse().getContentAsString()));

        mockMvc.perform(get(companyEvidencePath(company))
                .header("X-Workspace-Id", workspace.getId())
                .session(callerSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.subjectType").value("company"))
            .andExpect(jsonPath("$.attributionRule")
                .value("present_day_person_company_or_deal_company"))
            .andExpect(jsonPath("$.contributors", hasSize(3)))
            .andExpect(jsonPath("$.contributors[*].sourceType",
                hasItems("activity", "note", "task")))
            .andExpect(jsonPath("$.contributors[?(@.sourceType == 'note')].sourceId",
                hasItem(workspaceNote.getId())))
            .andExpect(jsonPath("$.contributors[?(@.sourceType == 'note')].sourceId",
                not(hasItem(callerPrivate.getId()))))
            .andExpect(jsonPath("$.coverage.callerPrivateNotesExcluded").value(1));

        MockHttpSession colleagueSession = login(colleague.getUsername());
        mockMvc.perform(get(contactEvidencePath(person))
                .header("X-Workspace-Id", workspace.getId())
                .session(colleagueSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.contributors[?(@.sourceType == 'note')].sourceId",
                hasItem(workspaceNote.getId())))
            .andExpect(jsonPath("$.contributors[?(@.sourceType == 'note')].sourceId",
                not(hasItem(callerPrivate.getId()))))
            .andExpect(jsonPath("$.contributors[?(@.sourceType == 'note')].sourceId",
                not(hasItem(colleaguePrivateOne.getId()))))
            .andExpect(jsonPath("$.contributors[?(@.sourceType == 'note')].sourceId",
                not(hasItem(colleaguePrivateTwo.getId()))))
            .andExpect(jsonPath("$.coverage.callerPrivateNotesExcluded").value(2));
    }

    @Test
    void sharedRecordsDoNotExposeOwnerWorkspaceEvidence() throws Exception {
        Workspace ownerWorkspace = newWorkspace();
        Workspace granteeWorkspace = newWorkspace();
        User owner = newMember(ownerWorkspace);
        User grantee = newMember(granteeWorkspace);
        Company company = newCompany(ownerWorkspace);
        Person person = newPerson(ownerWorkspace, company);
        activity(ownerWorkspace, owner, person, 1);
        note(ownerWorkspace, owner, person, "workspace");
        note(ownerWorkspace, owner, person, "private");
        task(ownerWorkspace, owner, person);
        shareMapper.sharePerson(
            person.getId(), ownerWorkspace.getId(), granteeWorkspace.getId(), owner.getId(), false);
        shareMapper.shareCompany(
            company.getId(), ownerWorkspace.getId(), granteeWorkspace.getId(), owner.getId(), false);

        MockHttpSession granteeSession = login(grantee.getUsername());
        mockMvc.perform(get(contactEvidencePath(person))
                .header("X-Workspace-Id", granteeWorkspace.getId())
                .session(granteeSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.contributors", hasSize(0)))
            .andExpect(jsonPath("$.totals.contributorCount").value(0))
            .andExpect(jsonPath("$.coverage.limitedEvidence").value(true))
            .andExpect(jsonPath("$.coverage.callerPrivateNotesExcluded").value(0));

        mockMvc.perform(get(companyEvidencePath(company))
                .header("X-Workspace-Id", granteeWorkspace.getId())
                .session(granteeSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.contributors", hasSize(0)))
            .andExpect(jsonPath("$.totals.contributorCount").value(0))
            .andExpect(jsonPath("$.coverage.limitedEvidence").value(true))
            .andExpect(jsonPath("$.coverage.callerPrivateNotesExcluded").value(0));
    }

    @Test
    void evidenceContributorListIsBoundedWhileTotalsCoverEveryEligibleSource() throws Exception {
        Workspace workspace = newWorkspace();
        User user = newMember(workspace);
        Company company = newCompany(workspace);
        Person person = newPerson(workspace, company);
        for (int minutesAgo = 1; minutesAgo <= 22; minutesAgo++) {
            activity(workspace, user, person, minutesAgo);
        }

        MockHttpSession session = login(user.getUsername());
        mockMvc.perform(get(contactEvidencePath(person))
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.contributors", hasSize(20)))
            .andExpect(jsonPath("$.totals.contributorCount").value(22))
            .andExpect(jsonPath("$.totals.returnedCount").value(20))
            .andExpect(jsonPath("$.totals.omittedCount").value(2))
            .andExpect(jsonPath("$.totals.sourceCounts.activities").value(22));

        mockMvc.perform(get(companyEvidencePath(company))
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.contributors", hasSize(20)))
            .andExpect(jsonPath("$.totals.contributorCount").value(22))
            .andExpect(jsonPath("$.totals.returnedCount").value(20))
            .andExpect(jsonPath("$.totals.omittedCount").value(2))
            .andExpect(jsonPath("$.totals.sourceCounts.activities").value(22));
    }

    @Test
    void companyEvidenceIncludesDealOnlySourcesAndDeduplicatesDualAttribution() throws Exception {
        Workspace workspace = newWorkspace();
        User user = newMember(workspace);
        Company company = newCompany(workspace);
        Person person = newPerson(workspace, company);
        Pipeline pipeline = pipeline(workspace);
        Stage stage = stage(workspace, pipeline);
        Deal deal = deal(workspace, pipeline, stage, company);
        Activity dealOnly = activity(workspace, user, null, deal, 2);
        Activity dualAttributed = activity(workspace, user, person, deal, 1);
        Note excludedPrivate = note(workspace, user, null, deal, "private");

        MockHttpSession session = login(user.getUsername());
        MvcResult evidenceResult = mockMvc.perform(get(companyEvidencePath(company))
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.contributors", hasSize(2)))
            .andExpect(jsonPath("$.contributors[*].sourceId",
                hasItems(dealOnly.getId(), dualAttributed.getId())))
            .andExpect(jsonPath("$.totals.contributorCount").value(2))
            .andExpect(jsonPath("$.totals.sourceCounts.activities").value(2))
            .andExpect(jsonPath("$.totals.sourceCounts.notes").value(0))
            .andExpect(jsonPath("$.temperature.touchCount").value(2))
            .andExpect(jsonPath("$.coverage.callerPrivateNotesExcluded").value(1))
            .andExpect(jsonPath("$.contributors[?(@.sourceType == 'note')].sourceId",
                not(hasItem(excludedPrivate.getId()))))
            .andReturn();
        MvcResult scoreResult = mockMvc.perform(get("/api/scoring/companies")
                .param("ids", Integer.toString(company.getId()))
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andReturn();

        JsonNode evidenceTemperature = objectMapper.readTree(
            evidenceResult.getResponse().getContentAsString()).get("temperature");
        JsonNode ordinaryTemperature = objectMapper.readTree(
            scoreResult.getResponse().getContentAsString()).get(0);
        assertEquals(ordinaryTemperature.get("score"), evidenceTemperature.get("score"));
        assertEquals(ordinaryTemperature.get("band"), evidenceTemperature.get("band"));
        assertEquals(ordinaryTemperature.get("touchCount"), evidenceTemperature.get("touchCount"));
        assertEquals(ordinaryTemperature.get("modelVersion"), evidenceTemperature.get("modelVersion"));
    }

    @Test
    void evidenceMappersCapBeforeAggregationAndDeduplicateCompanyAttribution() {
        Workspace workspace = newWorkspace();
        User user = newMember(workspace);
        Company company = newCompany(workspace);
        Person person = newPerson(workspace, company);
        Pipeline pipeline = pipeline(workspace);
        Stage stage = stage(workspace, pipeline);
        Deal deal = deal(workspace, pipeline, stage, company);
        activity(workspace, user, person, null, 5);
        activity(workspace, user, null, deal, 4);
        Activity dualAttributed = activity(workspace, user, person, deal, 3);
        note(workspace, user, person, "workspace");
        task(workspace, user, person);
        LocalDateTime reference = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(1);

        RelationshipEvidenceTotalsDto exactTotals = companyMapper.getRelationshipEvidenceTotals(
            workspace.getId(),
            company.getId(),
            reference,
            RelationshipWarmthModel.current().sqlParameters(),
            10
        );
        RelationshipEvidenceTotalsDto cappedTotals = companyMapper.getRelationshipEvidenceTotals(
            workspace.getId(),
            company.getId(),
            reference,
            RelationshipWarmthModel.current().sqlParameters(),
            3
        );
        List<RelationshipEvidenceRowDto> contributors =
            companyMapper.getRelationshipEvidenceContributors(
                workspace.getId(),
                company.getId(),
                reference,
                RelationshipWarmthModel.current().sqlParameters(),
                10,
                10
            );

        assertEquals(5, exactTotals.contributorCount());
        assertEquals(3, exactTotals.activityCount());
        assertEquals(1, exactTotals.noteCount());
        assertEquals(1, exactTotals.taskCount());
        assertEquals(3, cappedTotals.contributorCount());
        assertEquals(5, contributors.size());
        assertEquals(1, contributors.stream()
            .filter(row -> "activity".equals(row.sourceType())
                && row.sourceId() == dualAttributed.getId())
            .count());
    }

    @Test
    void processingRestrictedContactsCannotExposeEvidence() throws Exception {
        Workspace workspace = newWorkspace();
        User user = newMember(workspace);
        Company company = newCompany(workspace);
        Person person = newPerson(workspace, company);
        activity(workspace, user, person, 1);
        personMapper.updateProcessingRestrictions(workspace.getId(), person.getId(), true, false);

        MockHttpSession session = login(user.getUsername());
        mockMvc.perform(get(contactEvidencePath(person))
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isNotFound());
        mockMvc.perform(get(companyEvidencePath(company))
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.contributors", hasSize(0)))
            .andExpect(jsonPath("$.totals.contributorCount").value(0));
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
        String suffix = suffix();
        Workspace workspace = new Workspace();
        workspace.setName("Evidence " + suffix);
        workspace.setSlug("evidence-" + suffix);
        workspaceMapper.insert(workspace);
        return workspace;
    }

    private User newMember(Workspace workspace) {
        String suffix = suffix();
        User user = new User();
        user.setUsername("evidence_" + suffix);
        user.setDisplayName("Evidence " + suffix);
        user.setEmail("evidence_" + suffix + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setTimezone("UTC");
        userMapper.insert(user);
        workspaceMapper.addMember(workspace.getId(), user.getId(), "member");
        return user;
    }

    private Company newCompany(Workspace workspace) {
        Company company = new Company();
        company.setName("Evidence Company " + suffix());
        company.setWorkspaceId(workspace.getId());
        companyMapper.insert(company);
        return company;
    }

    private Person newPerson(Workspace workspace, Company company) {
        String suffix = suffix();
        Person person = new Person();
        person.setName("Evidence Person " + suffix);
        person.setEmail("evidence.person." + suffix + "@example.com");
        person.setCompany(company);
        person.setWorkspaceId(workspace.getId());
        personMapper.insert(person);
        return person;
    }

    private Activity activity(Workspace workspace, User user, Person person, int minutesAgo) {
        return activity(workspace, user, person, null, minutesAgo);
    }

    private Activity activity(
            Workspace workspace, User user, Person person, Deal deal, int minutesAgo) {
        Activity activity = new Activity();
        activity.setWorkspaceId(workspace.getId());
        activity.setType("meeting");
        activity.setSubject("Eligible evidence");
        activity.setPerson(person);
        activity.setDeal(deal);
        activity.setCreatedBy(user);
        activity.setTimestamp(LocalDateTime.now(ZoneOffset.UTC)
            .minusMinutes(minutesAgo)
            .format(MYSQL_DATETIME));
        activityMapper.insert(activity);
        return activity;
    }

    private Note note(Workspace workspace, User user, Person person, String visibility) {
        return note(workspace, user, person, null, visibility);
    }

    private Note note(
            Workspace workspace, User user, Person person, Deal deal, String visibility) {
        Note note = new Note();
        note.setWorkspaceId(workspace.getId());
        note.setContent("Evidence content must never appear");
        note.setVisibility(visibility);
        note.setAuthor(user);
        note.setPerson(person);
        note.setDeal(deal);
        note.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC)
            .minusMinutes(1)
            .format(MYSQL_DATETIME));
        noteMapper.insert(note);
        return note;
    }

    private Task task(Workspace workspace, User user, Person person) {
        Task task = new Task();
        task.setWorkspaceId(workspace.getId());
        task.setDescription("Eligible evidence task");
        task.setStatus("todo");
        task.setAssignedTo(user);
        task.setPerson(person);
        task.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC)
            .minusMinutes(1)
            .format(MYSQL_DATETIME));
        taskMapper.insert(task);
        return task;
    }

    private Pipeline pipeline(Workspace workspace) {
        Pipeline pipeline = new Pipeline();
        pipeline.setName("Evidence Pipeline " + suffix());
        pipeline.setWorkspaceId(workspace.getId());
        pipelineMapper.insertPipeline(pipeline);
        return pipeline;
    }

    private Stage stage(Workspace workspace, Pipeline pipeline) {
        Stage stage = new Stage();
        stage.setName("Evidence Stage " + suffix());
        stage.setWorkspaceId(workspace.getId());
        stage.setPipeline(pipeline);
        stage.setPosition(0);
        pipelineMapper.insertStage(stage);
        return stage;
    }

    private Deal deal(
            Workspace workspace, Pipeline pipeline, Stage stage, Company company) {
        Deal deal = new Deal();
        deal.setName("Evidence Deal " + suffix());
        deal.setWorkspaceId(workspace.getId());
        deal.setValue(new BigDecimal("1000.00"));
        deal.setCurrency("JPY");
        deal.setPipelineId(pipeline.getId());
        deal.setStageId(stage.getId());
        deal.setCompanyId(company.getId());
        dealMapper.insert(deal);
        return deal;
    }

    private static String contactEvidencePath(Person person) {
        return "/api/scoring/contacts/" + person.getId() + "/evidence";
    }

    private static String companyEvidencePath(Company company) {
        return "/api/scoring/companies/" + company.getId() + "/evidence";
    }

    private static void assertEvidenceMath(JsonNode evidence) {
        Instant asOf = Instant.parse(evidence.get("asOf").asText());
        double returnedContribution = 0.0;
        for (JsonNode contributor : evidence.get("contributors")) {
            Instant occurredAt = Instant.parse(contributor.get("occurredAt").asText());
            double ageDays = Math.max(
                0.0,
                Duration.between(occurredAt, asOf).toMillis() / 86_400_000.0
            );
            double baseWeight = contributor.get("baseWeight").asDouble();
            double expectedContribution = baseWeight * Math.pow(2.0, -ageDays / 30.0);
            double actualContribution = contributor.get("decayedContribution").asDouble();
            assertEquals(expectedContribution, actualContribution, 0.000_000_001);
            returnedContribution += actualContribution;
        }
        JsonNode totals = evidence.get("totals");
        assertEquals(
            returnedContribution,
            totals.get("returnedDecayedContribution").asDouble(),
            0.000_000_001
        );
        assertEquals(
            totals.get("totalDecayedContribution").asDouble(),
            totals.get("returnedDecayedContribution").asDouble(),
            0.000_000_001
        );
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
