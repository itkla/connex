package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.RelationshipTemperatureDto;
import ooo.klae.connex.backend.services.ScoringService;

/**
 * Read-path org-ceiling proof (#316): the owned-or-shared visibility predicates
 * grant a shared-in record only when the owner and grantee workspaces share an
 * organization. Share rows are inserted directly (bypassing {@code ShareService}
 * and the write-path SQL ceiling) to model a legacy or out-of-band cross-org row
 * — the read path must refuse it on its own, symmetrically with the write path.
 */
class ShareReadIsolationMapperTest extends AbstractMapperTest {

    private static final DateTimeFormatter MYSQL_DATETIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired private ActivityMapper activityMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private PipelineMapper pipelineMapper;
    @Autowired private NoteMapper noteMapper;
    @Autowired private TaskMapper taskMapper;
    @Autowired private ScoringService scoringService;
    @Autowired private DataSource dataSource;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    @Test
    void crossOrgCompanyShare_grantsNoReadVisibility() {
        Workspace foreign = newWorkspaceInOrg(newOrganization().getId());
        Company company = newCompanyIn(foreign);
        insertShare("company_share", "company_id", company.getId(), workspace.getId());

        assertNull(companyMapper.getCompanyById(workspace.getId(), company.getId()),
            "a cross-org share row must not make the record readable");
        assertFalse(companyMapper.exists(workspace.getId(), company.getId()));
        assertTrue(companyMapper.getAllCompanies(workspace.getId()).stream()
            .noneMatch(c -> c.getId() == company.getId()));
        assertTrue(companyMapper.search(workspace.getId(), "%").stream()
            .noneMatch(c -> c.getId() == company.getId()));
    }

    @Test
    void sameOrgCompanyShare_grantsReadVisibility() {
        Workspace sibling = newWorkspaceInOrg(orgIdOf(workspace));
        Company company = newCompanyIn(sibling);
        insertShare("company_share", "company_id", company.getId(), workspace.getId());

        assertTrue(companyMapper.exists(workspace.getId(), company.getId()),
            "a same-org share row remains readable (positive control)");
        assertTrue(companyMapper.getAllCompanies(workspace.getId()).stream()
            .anyMatch(c -> c.getId() == company.getId()));
    }

    @Test
    void crossOrgPersonShare_grantsNoReadVisibility() {
        Workspace foreign = newWorkspaceInOrg(newOrganization().getId());
        Person person = newPersonIn(foreign);
        insertShare("person_share", "person_id", person.getId(), workspace.getId());

        assertNull(personMapper.getPersonById(workspace.getId(), person.getId()));
        assertFalse(personMapper.exists(workspace.getId(), person.getId()));
        assertTrue(personMapper.getProcessablePersonIds(workspace.getId(), java.util.List.of(person.getId())).isEmpty());
    }

    @Test
    void sameOrgPersonShareIsIncludedInBatchVisibility() {
        Workspace sibling = newWorkspaceInOrg(orgIdOf(workspace));
        Person person = newPersonIn(sibling);
        insertShare("person_share", "person_id", person.getId(), workspace.getId());

        assertTrue(personMapper.getProcessablePersonIds(
            workspace.getId(), java.util.List.of(person.getId())).contains(person.getId()));
    }

    @Test
    void assistantHistoryPagesExcludeCrossOrgShareRowsBeforeApplyingTheirLimit() {
        User creator = newUser();
        Workspace sibling = newWorkspaceInOrg(orgIdOf(workspace));
        Workspace foreign = newWorkspaceInOrg(newOrganization().getId());
        Person sameOrgPerson = newPersonIn(sibling);
        Person crossOrgPerson = newPersonIn(foreign);
        insertShare("person_share", "person_id", sameOrgPerson.getId(), workspace.getId());
        insertShare("person_share", "person_id", crossOrgPerson.getId(), workspace.getId());
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, newCompany());

        Activity visibleActivity = newActivity(sameOrgPerson, deal, creator);
        visibleActivity.setTimestamp("2026-08-10 09:00:00");
        Activity crossOrgDirectActivity = newActivity(crossOrgPerson, deal, creator);
        crossOrgDirectActivity.setTimestamp("2026-08-12 09:00:00");
        Activity crossOrgReferenceActivity = newActivity(sameOrgPerson, deal, creator);
        crossOrgReferenceActivity.setTimestamp("2026-08-11 09:00:00");
        activityMapper.insert(visibleActivity);
        activityMapper.insert(crossOrgDirectActivity);
        activityMapper.insert(crossOrgReferenceActivity);
        insertReference("activity", crossOrgReferenceActivity.getId(), crossOrgPerson);

        Task visibleTask = newTask(sameOrgPerson, deal, creator, "2026-08-12");
        Task crossOrgDirectTask = newTask(crossOrgPerson, deal, creator, "2026-08-10");
        Task crossOrgReferenceTask = newTask(sameOrgPerson, deal, creator, "2026-08-11");
        taskMapper.insert(visibleTask);
        taskMapper.insert(crossOrgDirectTask);
        taskMapper.insert(crossOrgReferenceTask);
        insertReference("task", crossOrgReferenceTask.getId(), crossOrgPerson);
        jdbc().update("UPDATE task SET updated_at = ? WHERE id = ?", "2026-08-10 09:00:00", visibleTask.getId());
        jdbc().update("UPDATE task SET updated_at = ? WHERE id = ?", "2026-08-12 09:00:00", crossOrgDirectTask.getId());
        jdbc().update("UPDATE task SET updated_at = ? WHERE id = ?", "2026-08-11 09:00:00", crossOrgReferenceTask.getId());

        Note visibleNote = newNote(sameOrgPerson, deal, creator);
        Note crossOrgDirectNote = newNote(crossOrgPerson, deal, creator);
        Note crossOrgReferenceNote = newNote(sameOrgPerson, deal, creator);
        noteMapper.insert(visibleNote);
        noteMapper.insert(crossOrgDirectNote);
        noteMapper.insert(crossOrgReferenceNote);
        insertReference("note", crossOrgReferenceNote.getId(), crossOrgPerson);
        jdbc().update("UPDATE note SET updated_at = ? WHERE id = ?", "2026-08-10 09:00:00", visibleNote.getId());
        jdbc().update("UPDATE note SET updated_at = ? WHERE id = ?", "2026-08-12 09:00:00", crossOrgDirectNote.getId());
        jdbc().update("UPDATE note SET updated_at = ? WHERE id = ?", "2026-08-11 09:00:00", crossOrgReferenceNote.getId());

        List<Integer> organizationWorkspaceIds = List.of(workspace.getId(), sibling.getId());
        assertEquals(
                List.of(visibleActivity.getId()),
                activityMapper.getAiAssistantActivitiesByDealId(
                        workspace.getId(), deal.getId(), organizationWorkspaceIds,
                        null, null, 1).stream()
                        .map(Activity::getId)
                        .toList());
        assertEquals(
                List.of(visibleTask.getId()),
                taskMapper.getAiAssistantTasksByDealId(
                        workspace.getId(), deal.getId(), organizationWorkspaceIds, 1).stream()
                        .map(Task::getId)
                        .toList());
        assertEquals(
                List.of(visibleActivity.getId()),
                activityMapper.getAiAssistantActivitiesByCompanyId(
                        workspace.getId(), deal.getCompanyId(), organizationWorkspaceIds,
                        null, null, 1).stream()
                        .map(Activity::getId)
                        .toList());
        assertEquals(
                List.of(visibleTask.getId()),
                taskMapper.getAiAssistantTasksByCompanyId(
                        workspace.getId(), deal.getCompanyId(), organizationWorkspaceIds, 1).stream()
                        .map(Task::getId)
                        .toList());
        assertEquals(
                List.of(visibleNote.getId()),
                noteMapper.getAiAssistantVisibleNotesByCompanyId(
                        workspace.getId(),
                        deal.getCompanyId(),
                        creator.getId(),
                        organizationWorkspaceIds,
                        1).stream()
                        .map(Note::getId)
                        .toList());
    }

    @Test
    void companyScoringAcceptsSameOrgPersonShareAndRejectsCrossOrgShare() {
        User creator = newUser();
        Company sameOrgCompany = newCompanyIn(workspace);
        Company crossOrgCompany = newCompanyIn(workspace);
        Company dealCompany = newCompanyIn(workspace);
        Workspace sibling = newWorkspaceInOrg(orgIdOf(workspace));
        Workspace foreign = newWorkspaceInOrg(newOrganization().getId());
        Person sameOrgPerson = newPersonIn(sibling, sameOrgCompany);
        Person crossOrgPerson = newPersonIn(foreign, crossOrgCompany);
        insertShare("person_share", "person_id", sameOrgPerson.getId(), workspace.getId());
        insertShare("person_share", "person_id", crossOrgPerson.getId(), workspace.getId());
        activityMapper.insert(newActivity(sameOrgPerson, null, creator));
        activityMapper.insert(newActivity(crossOrgPerson, null, creator));

        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, dealCompany);
        activityMapper.insert(newActivity(null, deal, creator));

        List<RelationshipTemperatureDto> scores = scoringService.scoreCompanies(
            workspace.getId(), Set.of(
                sameOrgCompany.getId(), crossOrgCompany.getId(), dealCompany.getId()));
        Map<Integer, RelationshipTemperatureDto> scoresByCompany = scores.stream()
            .collect(Collectors.toMap(RelationshipTemperatureDto::getId, score -> score));

        assertEquals(3, scores.size());
        assertEquals(1, scoresByCompany.get(sameOrgCompany.getId()).getTouchCount());
        assertEquals(0, scoresByCompany.get(crossOrgCompany.getId()).getTouchCount());
        assertEquals(1, scoresByCompany.get(dealCompany.getId()).getTouchCount());
    }

    @Test
    void crossOrgPipelineShare_grantsNoReadVisibility() {
        Workspace foreign = newWorkspaceInOrg(newOrganization().getId());
        Pipeline pipeline = newPipelineIn(foreign);
        Stage stage = newStageIn(foreign, pipeline);
        insertShare("pipeline_share", "pipeline_id", pipeline.getId(), workspace.getId());

        assertFalse(pipelineMapper.pipelineExists(workspace.getId(), pipeline.getId()));
        assertTrue(pipelineMapper.getAllPipelines(workspace.getId()).stream()
            .noneMatch(p -> p.getId() == pipeline.getId()));
        assertTrue(pipelineMapper.getAllStages(workspace.getId()).stream()
            .noneMatch(candidate -> candidate.getId() == stage.getId()));
    }

    @Test
    void sameOrgPipelineShareGrantsBatchStageVisibility() {
        Workspace sibling = newWorkspaceInOrg(orgIdOf(workspace));
        Pipeline pipeline = newPipelineIn(sibling);
        Stage stage = newStageIn(sibling, pipeline);
        insertShare("pipeline_share", "pipeline_id", pipeline.getId(), workspace.getId());

        assertTrue(pipelineMapper.getAllStages(workspace.getId()).stream()
            .anyMatch(candidate -> candidate.getId() == stage.getId()));
    }

    private void insertShare(String table, String fkColumn, int entityId, int granteeWorkspaceId) {
        jdbc().update("INSERT INTO " + table + " (" + fkColumn + ", workspace_id, granted_by, can_edit) "
            + "VALUES (?, ?, ?, ?)", entityId, granteeWorkspaceId, newUser().getId(), false);
    }

    private void insertReference(String sourceType, int sourceId, Person person) {
        jdbc().update(
                "INSERT INTO entity_reference "
                    + "(workspace_id, source_type, source_id, ref_type, ref_id, label) "
                    + "VALUES (?, ?, ?, 'person', ?, ?)",
                workspace.getId(), sourceType, sourceId, person.getId(), person.getName());
    }

    private Organization newOrganization() {
        String s = unique();
        Organization organization = new Organization();
        organization.setName("Org " + s);
        organization.setSlug("org-" + s);
        organizationMapper.insert(organization);
        return organization;
    }

    private Workspace newWorkspaceInOrg(int orgId) {
        String s = unique();
        Workspace ws = new Workspace();
        ws.setName("Workspace " + s);
        ws.setSlug("ws-" + s);
        ws.setOrgId(orgId);
        workspaceMapper.insert(ws);
        return ws;
    }

    private int orgIdOf(Workspace ws) {
        Integer orgId = workspaceMapper.getOrgId(ws.getId());
        assertTrue(orgId != null, "test workspace must belong to an organization");
        return orgId;
    }

    private Company newCompanyIn(Workspace ws) {
        Company company = new Company();
        company.setName("Company " + unique());
        company.setWorkspaceId(ws.getId());
        companyMapper.insert(company);
        return company;
    }

    private Person newPersonIn(Workspace ws) {
        return newPersonIn(ws, null);
    }

    private Person newPersonIn(Workspace ws, Company company) {
        Person person = new Person();
        person.setName("Person " + unique());
        person.setWorkspaceId(ws.getId());
        person.setCompany(company);
        personMapper.insert(person);
        return person;
    }

    private Activity newActivity(Person person, Deal deal, User creator) {
        Activity activity = new Activity();
        activity.setWorkspaceId(workspace.getId());
        activity.setType("meeting");
        activity.setSubject("Activity " + unique());
        activity.setPerson(person);
        activity.setDeal(deal);
        activity.setCreatedBy(creator);
        activity.setTimestamp(LocalDateTime.now(ZoneOffset.UTC).minusHours(1).format(MYSQL_DATETIME));
        return activity;
    }

    private Task newTask(Person person, Deal deal, User assignee, String dueDate) {
        Task task = new Task();
        task.setWorkspaceId(workspace.getId());
        task.setDescription("Task " + unique());
        task.setCompleted(false);
        task.setStatus("todo");
        task.setDueDate(dueDate);
        task.setAssignedTo(assignee);
        task.setPerson(person);
        task.setDeal(deal);
        return task;
    }

    private Note newNote(Person person, Deal deal, User author) {
        Note note = new Note();
        note.setWorkspaceId(workspace.getId());
        note.setContent("Note " + unique());
        note.setVisibility("workspace");
        note.setAuthor(author);
        note.setPerson(person);
        note.setDeal(deal);
        return note;
    }

    private Pipeline newPipelineIn(Workspace ws) {
        Pipeline pipeline = new Pipeline();
        pipeline.setName("Pipeline " + unique());
        pipeline.setWorkspaceId(ws.getId());
        pipelineMapper.insertPipeline(pipeline);
        return pipeline;
    }

    private Stage newStageIn(Workspace ws, Pipeline pipeline) {
        Stage stage = new Stage();
        stage.setName("Stage " + unique());
        stage.setWorkspaceId(ws.getId());
        stage.setPipeline(pipeline);
        pipelineMapper.insertStage(stage);
        return stage;
    }
}
