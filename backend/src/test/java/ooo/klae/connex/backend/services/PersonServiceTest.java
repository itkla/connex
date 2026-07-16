package ooo.klae.connex.backend.services;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.ShareMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;

class PersonServiceTest extends AbstractServiceTest {

    @Autowired PersonService personService;
    @Autowired ShareMapper shareMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RoleService roleService;
    @Autowired WorkspaceService workspaceService;

    @Test
    void updateProcessingRestrictionsPreservesTimestampClearsIndependentlyAndAuditsChanges() {
        Company company = newCompany();
        Person person = newPerson(company);

        Person restricted = personService.updateProcessingRestrictions(person.getId(), true, true);
        assertNotNull(restricted.getSuspendedAt());
        assertNotNull(restricted.getProvisionCeasedAt());
        Person normallyUpdated = personService.update(person.getId(), personDraft(company));
        assertNotNull(normallyUpdated.getSuspendedAt());
        assertNotNull(normallyUpdated.getProvisionCeasedAt());
        String changes = jdbcTemplate.queryForObject(
            "SELECT changes FROM audit_log WHERE workspace_id = ? AND entity_type = 'person' "
                + "AND entity_id = ? AND action = 'person.restrictions' ORDER BY id DESC LIMIT 1",
            String.class, workspace.getId(), person.getId());
        assertNotNull(changes);
        assertTrue(changes.contains("suspendedAt"));
        assertTrue(changes.contains("provisionCeasedAt"));

        var preserved = java.time.LocalDateTime.parse("2025-01-02T03:04:05");
        jdbcTemplate.update("UPDATE person SET suspended_at = ? WHERE id = ?", preserved, person.getId());
        Person idempotent = personService.updateProcessingRestrictions(person.getId(), true, true);
        assertEquals(preserved, idempotent.getSuspendedAt());

        Person partiallyCleared = personService.updateProcessingRestrictions(person.getId(), false, true);
        assertNull(partiallyCleared.getSuspendedAt());
        assertNotNull(partiallyCleared.getProvisionCeasedAt());
    }

    @Test
    void updateProcessingRestrictionsRequiresPersonUpdatePermission() {
        Person person = newPerson(newCompany());
        User restricted = newUser();
        var role = roleService.createRole(
            workspace.getId(), currentUser.getId(), "Restriction reader " + unique(), List.of("PERSON_CREATE"));
        workspaceService.assignCustomRole(
            workspace.getId(), currentUser.getId(), restricted.getId(), role.getId());
        authenticateAs(restricted, workspace.getId());

        assertThrows(ForbiddenException.class,
            () -> personService.updateProcessingRestrictions(person.getId(), true, false));
        assertNull(personMapper.getPersonById(workspace.getId(), person.getId()).getSuspendedAt());
    }

    @Test
    void updateEvaluationExclusions_updatesOwnedContactOnly() {
        Person person = newPerson(newCompany());

        Person riskUpdated = personService.updateEvaluationExclusions(person.getId(), true, null);
        assertTrue(riskUpdated.isRiskExcluded());
        assertFalse(riskUpdated.isIntroExcluded());

        Person introUpdated = personService.updateEvaluationExclusions(person.getId(), null, true);
        assertTrue(introUpdated.isRiskExcluded());
        assertTrue(introUpdated.isIntroExcluded());

        Person idempotent = personService.updateEvaluationExclusions(person.getId(), true, true);
        assertTrue(idempotent.isRiskExcluded());
        assertTrue(idempotent.isIntroExcluded());
    }

    @Test
    void updateEvaluationExclusions_rejectsSharedInContact() {
        Workspace other = newOtherWorkspace();
        Person foreign = personInWorkspace(other);
        shareMapper.sharePerson(foreign.getId(), other.getId(), workspace.getId(), currentUser.getId(), true);

        assertTrue(personMapper.exists(workspace.getId(), foreign.getId()));
        assertFalse(personMapper.existsOwned(workspace.getId(), foreign.getId()));
        assertThrows(ResourceNotFoundException.class,
            () -> personService.updateEvaluationExclusions(foreign.getId(), true, true));
        Person ownerView = personMapper.getPersonById(other.getId(), foreign.getId());
        assertFalse(ownerView.isRiskExcluded());
        assertFalse(ownerView.isIntroExcluded());

        assertThrows(ResourceNotFoundException.class,
            () -> personService.updateProcessingRestrictions(foreign.getId(), true, true));
        ownerView = personMapper.getPersonById(other.getId(), foreign.getId());
        assertNull(ownerView.getSuspendedAt());
        assertNull(ownerView.getProvisionCeasedAt());
    }

    @Test
    void coreMutationsRejectSharedInContactBeforeSideEffects() {
        Workspace ownerWorkspace = newWorkspaceInSameOrg();
        Person shared = personInWorkspace(ownerWorkspace);
        shareMapper.sharePerson(
            shared.getId(), ownerWorkspace.getId(), workspace.getId(), currentUser.getId(), true);
        Tag tag = newTag();
        int employmentBefore = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM person_employment WHERE workspace_id = ? AND person_id = ?",
            Integer.class, workspace.getId(), shared.getId());
        int auditBefore = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE workspace_id = ? AND entity_type = 'person' AND entity_id = ?",
            Integer.class, workspace.getId(), shared.getId());

        assertThrows(ResourceNotFoundException.class,
            () -> personService.update(shared.getId(), personDraft(null)));
        assertThrows(ResourceNotFoundException.class,
            () -> personService.addTag(shared.getId(), tag.getId()));
        assertThrows(ResourceNotFoundException.class,
            () -> personService.removeTag(shared.getId(), tag.getId()));
        assertThrows(ResourceNotFoundException.class,
            () -> personService.replaceTags(shared.getId(), List.of(tag.getId())));
        assertThrows(ResourceNotFoundException.class,
            () -> personService.delete(shared.getId()));

        assertTrue(personMapper.existsOwned(ownerWorkspace.getId(), shared.getId()));
        assertEquals(employmentBefore, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM person_employment WHERE workspace_id = ? AND person_id = ?",
            Integer.class, workspace.getId(), shared.getId()));
        assertEquals(auditBefore, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE workspace_id = ? AND entity_type = 'person' AND entity_id = ?",
            Integer.class, workspace.getId(), shared.getId()));
    }

    @Test
    void getPersonById_returnsOnlyActiveWorkspaceChildrenForSharedContact() {
        Workspace ownerWorkspace = newWorkspaceInSameOrg();
        Company ownerCompany = companyInWorkspace(ownerWorkspace);
        Person shared = personInWorkspace(ownerWorkspace, ownerCompany);
        Activity ownerActivity = activityInWorkspace(ownerWorkspace, shared);
        Task ownerTask = taskInWorkspace(ownerWorkspace, shared);
        Note ownerNote = noteInWorkspace(ownerWorkspace, shared);
        Tag ownerTag = tagInWorkspace(ownerWorkspace);
        personMapper.addTag(ownerWorkspace.getId(), shared.getId(), ownerTag.getId());
        Activity activeActivity = activityInWorkspace(workspace, shared);
        Task activeTask = taskInWorkspace(workspace, shared);
        Note activeNote = noteInWorkspace(workspace, shared);
        shareMapper.sharePerson(shared.getId(), ownerWorkspace.getId(), workspace.getId(), currentUser.getId(), true);

        Person detail = personService.getPersonById(shared.getId());

        assertEquals(List.of(activeActivity.getId()), Arrays.stream(detail.getActivities()).map(Activity::getId).toList());
        assertEquals(List.of(activeTask.getId()), Arrays.stream(detail.getTasks()).map(Task::getId).toList());
        assertEquals(List.of(activeNote.getId()), Arrays.stream(detail.getNotes()).map(Note::getId).toList());
        assertTrue(Arrays.stream(detail.getActivities()).noneMatch(activity -> activity.getId() == ownerActivity.getId()));
        assertTrue(Arrays.stream(detail.getTasks()).noneMatch(task -> task.getId() == ownerTask.getId()));
        assertTrue(Arrays.stream(detail.getNotes()).noneMatch(note -> note.getId() == ownerNote.getId()));
        assertTrue(Arrays.stream(detail.getTags()).noneMatch(tag -> tag.getId() == ownerTag.getId()));
        assertNull(detail.getCompany(), "person share alone must not disclose owner-workspace company metadata");
    }

    @Test
    void create_rejectsUnsharedForeignCompany() {
        Workspace ownerWorkspace = newWorkspaceInSameOrg();
        Company ownerCompany = companyInWorkspace(ownerWorkspace);
        Person person = personDraft(ownerCompany);

        assertThrows(BadRequestException.class, () -> personService.create(person));
    }

    @Test
    void update_rejectsUnsharedForeignCompany() {
        Person existing = personService.create(personDraft(null));
        Workspace ownerWorkspace = newWorkspaceInSameOrg();
        Company ownerCompany = companyInWorkspace(ownerWorkspace);
        Person update = personDraft(ownerCompany);

        assertThrows(BadRequestException.class, () -> personService.update(existing.getId(), update));
    }

    @Test
    void distinctCompanies_doesNotRevealUnsharedForeignCompanyName() {
        Workspace ownerWorkspace = newWorkspaceInSameOrg();
        Company ownerCompany = companyInWorkspace(ownerWorkspace);
        personInWorkspace(workspace, ownerCompany);

        assertFalse(personService.distinctCompanies().contains(ownerCompany.getName()));
    }

    @Test
    void getDealsByPersonId_returnsOnlyDealsLinkedToPerson() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        Deal linked = newDeal(pipeline, stage, company);
        Deal unlinked = newDeal(pipeline, stage, company);
        Person person = newPerson(company);
        dealMapper.addPerson(workspace.getId(), linked.getId(), person.getId(), null);

        List<Deal> deals = personService.getDealsByPersonId(person.getId());

        assertTrue(deals.stream().anyMatch(x -> x.getId() == linked.getId()));
        assertTrue(deals.stream().noneMatch(x -> x.getId() == unlinked.getId()));
    }

    @Test
    void getDealsByPersonId_throwsWhenPersonMissing() {
        assertThrows(ResourceNotFoundException.class, () -> personService.getDealsByPersonId(-1));
    }

    @Test
    void getMatchingPersonIdsRejectsTooManyMatchesBeforeFetchingIds() {
        PersonMapper mapper = mock(PersonMapper.class);
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        PersonService service = new PersonService(
            mapper,
            mock(CompanyMapper.class),
            mock(TagMapper.class),
            mock(DealMapper.class),
            mock(ActivityMapper.class),
            mock(NoteMapper.class),
            mock(TaskMapper.class),
            mock(AuditService.class),
            workspaceService,
            mock(EmploymentService.class),
            mock(CustomFieldValueService.class),
            mock(ReferenceService.class),
            mock(RuleTriggerPublisher.class)
        );
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(mapper.countPersons(7, "Security", null, null, false)).thenReturn(1001L);

        assertThrows(BadRequestException.class,
            () -> service.getMatchingPersonIds("Security", null, null, false));

        verify(mapper, never()).getPersonIdsFiltered(7, "Security", null, null, false, 1000);
    }

    @Test
    void getActivitiesByPersonId_returnsOnlyMatchingActivities() {
        Person p1 = newPerson(newCompany());
        Person p2 = newPerson(newCompany());
        User user = newUser();
        Activity a1 = newActivity(user, p1, null);
        Activity a2 = newActivity(user, p2, null);

        List<Activity> activities = personService.getActivitiesByPersonId(p1.getId());

        assertTrue(activities.stream().anyMatch(x -> x.getId() == a1.getId()));
        assertTrue(activities.stream().noneMatch(x -> x.getId() == a2.getId()));
    }

    @Test
    void getActivitiesByPersonId_throwsWhenPersonMissing() {
        assertThrows(ResourceNotFoundException.class, () -> personService.getActivitiesByPersonId(-1));
    }

    @Test
    void getNotesByPersonId_returnsOnlyMatchingNotes() {
        Person p1 = newPerson(newCompany());
        Person p2 = newPerson(newCompany());
        User user = newUser();
        Note n1 = newNote(user, p1, null);
        Note n2 = newNote(user, p2, null);

        List<Note> notes = personService.getNotesByPersonId(p1.getId());

        assertTrue(notes.stream().anyMatch(x -> x.getId() == n1.getId()));
        assertTrue(notes.stream().noneMatch(x -> x.getId() == n2.getId()));
    }

    @Test
    void getNotesByPersonId_throwsWhenPersonMissing() {
        assertThrows(ResourceNotFoundException.class, () -> personService.getNotesByPersonId(-1));
    }

    @Test
    void getTasksByPersonId_returnsOnlyMatchingTasks() {
        Person p1 = newPerson(newCompany());
        Person p2 = newPerson(newCompany());
        User user = newUser();
        Task t1 = newTask(user, p1, null);
        Task t2 = newTask(user, p2, null);

        List<Task> tasks = personService.getTasksByPersonId(p1.getId());

        assertTrue(tasks.stream().anyMatch(x -> x.getId() == t1.getId()));
        assertTrue(tasks.stream().noneMatch(x -> x.getId() == t2.getId()));
    }

    @Test
    void getTasksByPersonId_throwsWhenPersonMissing() {
        assertThrows(ResourceNotFoundException.class, () -> personService.getTasksByPersonId(-1));
    }

    private Workspace newOtherWorkspace() {
        Workspace other = new Workspace();
        other.setName("Other Workspace");
        other.setSlug("other-" + unique());
        workspaceMapper.insert(other);
        return other;
    }

    private Workspace newWorkspaceInSameOrg() {
        Workspace other = new Workspace();
        other.setName("Other Workspace");
        other.setSlug("other-" + unique());
        other.setOrgId(workspaceMapper.getOrgId(workspace.getId()));
        workspaceMapper.insert(other);
        return other;
    }

    private Person personInWorkspace(Workspace target) {
        return personInWorkspace(target, null);
    }

    private Person personInWorkspace(Workspace target, Company company) {
        Person person = personDraft(company);
        person.setWorkspaceId(target.getId());
        personMapper.insert(person);
        return person;
    }

    private Person personDraft(Company company) {
        String s = unique();
        Person person = new Person();
        person.setName("Foreign " + s);
        person.setEmail(s + ".foreign@example.com");
        person.setTitle("Engineer");
        person.setCompany(company);
        return person;
    }

    private Company companyInWorkspace(Workspace target) {
        Company company = new Company();
        company.setName("Company " + unique());
        company.setWorkspaceId(target.getId());
        companyMapper.insert(company);
        return company;
    }

    private Activity activityInWorkspace(Workspace target, Person person) {
        Activity activity = new Activity();
        activity.setWorkspaceId(target.getId());
        activity.setType("call");
        activity.setSubject("Activity " + unique());
        activity.setNotes("Notes " + unique());
        activity.setPerson(person);
        activity.setCreatedBy(currentUser);
        activity.setTimestamp("2024-06-01 10:00:00");
        activityMapper.insert(activity);
        return activity;
    }

    private Task taskInWorkspace(Workspace target, Person person) {
        Task task = new Task();
        task.setWorkspaceId(target.getId());
        task.setDescription("Task " + unique());
        task.setCompleted(false);
        task.setStatus("todo");
        task.setDueDate("2024-12-31");
        task.setAssignedTo(currentUser);
        task.setPerson(person);
        taskMapper.insert(task);
        return task;
    }

    private Note noteInWorkspace(Workspace target, Person person) {
        Note note = new Note();
        note.setWorkspaceId(target.getId());
        note.setContent("Note " + unique());
        note.setAuthor(currentUser);
        note.setPerson(person);
        noteMapper.insert(note);
        return note;
    }

    private Tag tagInWorkspace(Workspace target) {
        Tag tag = new Tag();
        tag.setWorkspaceId(target.getId());
        tag.setName("tag_" + unique());
        tag.setColor("#abcdef");
        tagMapper.insert(tag);
        return tag;
    }
}
