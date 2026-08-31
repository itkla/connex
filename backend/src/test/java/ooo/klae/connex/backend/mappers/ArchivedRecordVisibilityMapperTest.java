package ooo.klae.connex.backend.mappers;

import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.PersonEmployment;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.CompanyEngagementCountsDto;
import ooo.klae.connex.backend.dto.FacetCount;
import ooo.klae.connex.backend.dto.JobMoveDto;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.dto.RelationshipScoreAggregateDto;
import ooo.klae.connex.backend.warmth.RelationshipWarmthModel;

/**
 * Regression coverage for the surfaces that kept showing archived contacts and companies after
 * archiving replaced the hard delete (#854). Each test pins one statement that used to leak: the
 * predicate is what the assertion is about, so removing it again turns these red.
 */
class ArchivedRecordVisibilityMapperTest extends AbstractMapperTest {

    private static final DateTimeFormatter MYSQL_DATETIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired private PersonEmploymentMapper employmentMapper;
    @Autowired private AttachmentMapper attachmentMapper;
    @Autowired private ActivityMapper activityMapper;
    @Autowired private TaskMapper taskMapper;
    @Autowired private NoteMapper noteMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void useIsolatedWorkspace() {
        workspace = newWorkspace("archive-vis");
    }

    /** P1-1: the recent-moves feed projects the contact's name and picture straight to the client. */
    @Test
    void recentMovesDropsArchivedContacts() {
        Company from = newCompany();
        Company to = newCompany();
        Person mover = newPerson(to);
        recordMove(mover, from, to);

        assertTrue(recentMoveIds().contains(mover.getId()));

        assertEquals(1, personMapper.archive(workspace.getId(), mover.getId()));

        assertFalse(recentMoveIds().contains(mover.getId()));

        assertEquals(1, personMapper.restore(workspace.getId(), mover.getId()));
        assertTrue(recentMoveIds().contains(mover.getId()));
    }

    @Test
    void relationshipScoringIgnoresUnlinkedAndArchivedEvidenceUntilRestore() {
        Company employer = newCompany();
        Person contact = newPerson(employer);
        User author = newUser();
        LocalDateTime reference = LocalDateTime.parse("2026-07-31T00:00:00");
        Activity unlinked = new Activity();
        unlinked.setWorkspaceId(workspace.getId());
        unlinked.setType("meeting");
        unlinked.setSubject("Unlinked activity");
        unlinked.setCreatedBy(author);
        unlinked.setTimestamp("2026-07-30 09:00:00");
        activityMapper.insert(unlinked);

        assertNull(personScore(contact.getId(), reference).lastTouchAt());
        assertNull(companyScore(employer.getId(), reference).lastTouchAt());

        newActivity(contact, null, author);

        assertNotNull(personScore(contact.getId(), reference).lastTouchAt());
        assertNotNull(companyScore(employer.getId(), reference).lastTouchAt());

        assertEquals(1, personMapper.archive(workspace.getId(), contact.getId()));
        assertFalse(personScores(reference).stream().anyMatch(score -> score.id() == contact.getId()));
        assertNull(companyScore(employer.getId(), reference).lastTouchAt());

        assertEquals(1, personMapper.restore(workspace.getId(), contact.getId()));
        assertNotNull(personScore(contact.getId(), reference).lastTouchAt());
        assertNotNull(companyScore(employer.getId(), reference).lastTouchAt());

        Company account = newCompany();
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, account);
        newActivity(null, deal, author);

        assertNotNull(companyScore(account.getId(), reference).lastTouchAt());

        assertEquals(1, companyMapper.archive(workspace.getId(), account.getId()));
        assertFalse(companyScores(reference).stream().anyMatch(score -> score.id() == account.getId()));

        assertEquals(1, companyMapper.restore(workspace.getId(), account.getId()));
        assertNotNull(companyScore(account.getId(), reference).lastTouchAt());
    }

    /**
     * P1-2: the shared `companyJoin` backs every contact row, peek, and detail page. An archived
     * employer must read as no employer — the hard delete nulled `person.company_id`, and a chip
     * linking to a detail page that 404s would be worse than none.
     */
    @Test
    void archivedEmployerLeavesTheContactWithNoCompany() {
        Company employer = newCompany();
        Person employee = newPerson(employer);

        assertEquals(employer.getId(),
            personMapper.getPersonById(workspace.getId(), employee.getId()).getCompany().getId());

        assertEquals(1, companyMapper.archive(workspace.getId(), employer.getId()));

        Person hidden = personMapper.getPersonById(workspace.getId(), employee.getId());
        assertNotNull(hidden);
        assertNull(hidden.getCompany());
        assertTrue(personMapper.getAllPersons(workspace.getId()).stream()
            .filter(person -> person.getId() == employee.getId())
            .allMatch(person -> person.getCompany() == null));
        assertTrue(personMapper.getByIds(workspace.getId(), List.of(employee.getId())).stream()
            .allMatch(person -> person.getCompany() == null));
        assertTrue(personPageOf(employee).getCompany() == null);

        assertEquals(1, companyMapper.restore(workspace.getId(), employer.getId()));
        assertEquals(employer.getId(),
            personMapper.getPersonById(workspace.getId(), employee.getId()).getCompany().getId());
    }

    /** P1-2 follow-through: the contact search must not match on an archived employer's fields. */
    @Test
    void archivedEmployerStopsMatchingContactSearch() {
        Company employer = newCompany();
        Person employee = newPerson(employer);
        String query = "%" + employer.getName() + "%";

        assertTrue(personMapper.search(workspace.getId(), query).stream()
            .anyMatch(person -> person.getId() == employee.getId()));

        assertEquals(1, companyMapper.archive(workspace.getId(), employer.getId()));

        assertFalse(personMapper.search(workspace.getId(), query).stream()
            .anyMatch(person -> person.getId() == employee.getId()));
    }

    /** P1-3: the contacts browser company facet must stop offering archived companies. */
    @Test
    void companyFacetDropsArchivedCompaniesAndCountsTheirContactsAsUnassigned() {
        Company employer = newCompany();
        Person employee = newPerson(employer);

        assertTrue(personMapper.distinctCompanies(workspace.getId()).contains(employer.getName()));

        assertEquals(1, companyMapper.archive(workspace.getId(), employer.getId()));

        assertFalse(personMapper.distinctCompanies(workspace.getId()).contains(employer.getName()));
        assertFalse(personMapper.getPersonIdsFiltered(workspace.getId(), null,
            List.of(employer.getName()), null, false, allTeamScope(), null, false, null, false, null, false, false, null, 100)
            .contains(employee.getId()));
        assertTrue(personMapper.hasPersonWithoutCompany(workspace.getId()));
        assertTrue(personMapper.getPersonIdsFiltered(workspace.getId(), null, null, null,
            true, allTeamScope(), null, false, null, false, null, false, false, null, 100).contains(employee.getId()));
    }

    /** P1-4: the file browser labels every file with its owner's name. */
    @Test
    void fileBrowserStopsLabellingFilesWithArchivedOwners() {
        User reader = newUser();
        Company owner = newCompany();
        Person contact = newPerson(owner);
        Attachment companyFile = newAttachment("company", owner.getId());
        Attachment contactFile = newAttachment("person", contact.getId());

        assertEquals(owner.getName(), pagedLabel(companyFile, reader.getId()));
        assertEquals(contact.getName(), pagedLabel(contactFile, reader.getId()));
        assertEquals(owner.getName(),
            attachmentMapper.getByEntity(workspace.getId(), "company", owner.getId())
                .getFirst().getEntityLabel());

        assertEquals(1, companyMapper.archive(workspace.getId(), owner.getId()));
        assertEquals(1, personMapper.archive(workspace.getId(), contact.getId()));

        assertNull(pagedLabel(companyFile, reader.getId()));
        assertNull(pagedLabel(contactFile, reader.getId()));
        assertNull(attachmentMapper.getByEntity(workspace.getId(), "company", owner.getId())
            .getFirst().getEntityLabel());
        assertEquals(2, attachmentMapper.countOrphaned(workspace.getId(), reader.getId()));

        assertEquals(2, attachmentMapper.getPage(workspace.getId(), reader.getId(), null, null, null, null, null,
            null, 100, 0).size());
        assertNotNull(attachmentMapper.getMetadataById(workspace.getId(), companyFile.getId()));
    }

    /** P2-6: the deals company facet must keep summing to the deal total. */
    @Test
    void dealCompanyFacetCountsDealsOnArchivedCompaniesAsUnassigned() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company account = newCompany();
        Deal onAccount = newDeal(pipeline, stage, account);
        Deal unassigned = newDealWithoutCompany(pipeline, stage);

        Map<String, Long> before = facetCounts(dealMapper.countsByCompany(workspace.getId()));
        assertEquals(1L, before.get(String.valueOf(account.getId())));
        assertEquals(1L, before.get("__empty__"));

        assertEquals(1, companyMapper.archive(workspace.getId(), account.getId()));

        Map<String, Long> after = facetCounts(dealMapper.countsByCompany(workspace.getId()));
        assertNull(after.get(String.valueOf(account.getId())));
        assertEquals(2L, after.get("__empty__"));
        assertEquals(2L, after.values().stream().mapToLong(Long::longValue).sum());
        List<Integer> unassignedIds = dealMapper.getFilteredDealIds(workspace.getId(), null, null,
            null, null, null, null, null, true, null, null, allTeamScope(), 100);
        assertTrue(unassignedIds.contains(onAccount.getId()));
        assertTrue(unassignedIds.contains(unassigned.getId()));
    }

    /** P2-7: a company page must not show "0 people" beside engagement driven by archived people. */
    @Test
    void companyEngagementCountsIgnoreArchivedContacts() {
        Company account = newCompany();
        Person contact = newPerson(account);
        newWorkspaceNote(contact);

        CompanyEngagementCountsDto before =
            companyMapper.getCompanyEngagementCounts(workspace.getId(), account.getId());
        assertEquals(1L, before.personCount());
        assertEquals(1L, before.numNotes());

        assertEquals(1, personMapper.archive(workspace.getId(), contact.getId()));

        CompanyEngagementCountsDto after =
            companyMapper.getCompanyEngagementCounts(workspace.getId(), account.getId());
        assertEquals(0L, after.personCount());
        assertEquals(0L, after.numNotes());
    }

    /**
     * The company timeline must drop every row linked to an archived contact, including a row that
     * would otherwise stay attributed through a matching deal.
     */
    @Test
    void companyTimelineDropsDealAttributedRowsLinkedToArchivedContacts() {
        Company account = newCompany();
        Person contact = newPerson(account);
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, account);
        User user = newUser();
        Activity activity = newActivity(contact, deal, user);
        Task task = newTask(contact, deal, user);
        Note note = newTimelineNote(contact, deal, user);

        assertCompanyTimelineContains(account, user, activity, task, note);

        assertEquals(1, personMapper.archive(workspace.getId(), contact.getId()));

        assertTrue(activityMapper.getCompanyActivities(
            workspace.getId(), account.getId(), 100).isEmpty());
        assertTrue(taskMapper.getCompanyTasks(
            workspace.getId(), account.getId(), 100).isEmpty());
        assertTrue(noteMapper.getVisibleCompanyNotes(
            workspace.getId(), account.getId(), user.getId(), 100).isEmpty());

        assertEquals(1, personMapper.restore(workspace.getId(), contact.getId()));
        assertCompanyTimelineContains(account, user, activity, task, note);
    }

    /** Note search must stop finding a note by the name of a contact that has been archived. */
    @Test
    void noteSearchStopsMatchingArchivedContactNames() {
        Company account = newCompany();
        Person contact = newPerson(account);
        newWorkspaceNote(contact);
        String query = "%" + contact.getName() + "%";

        assertFalse(noteMapper.search(workspace.getId(), query).isEmpty());

        assertEquals(1, personMapper.archive(workspace.getId(), contact.getId()));

        assertTrue(noteMapper.search(workspace.getId(), query).isEmpty());
    }

    /**
     * P2-9: archive and restore are owned-only writes, so a share grantee must not enumerate the
     * owner workspace's archived records it could never bring back — while the active list keeps
     * showing the shared record.
     */
    @Test
    void shareGranteeNeverEnumeratesTheOwnersArchivedRecords() {
        Workspace grantee = newWorkspace("archive-grantee");
        Company sharedCompany = newCompany();
        Person sharedPerson = newPerson(sharedCompany);
        sharePerson(sharedPerson, grantee);
        shareCompany(sharedCompany, grantee);

        assertTrue(personPageIds(grantee, false).contains(sharedPerson.getId()));
        assertTrue(companyPageIds(grantee, false).contains(sharedCompany.getId()));

        assertEquals(1, personMapper.archive(workspace.getId(), sharedPerson.getId()));
        assertEquals(1, companyMapper.archive(workspace.getId(), sharedCompany.getId()));

        assertFalse(personPageIds(grantee, true).contains(sharedPerson.getId()));
        assertFalse(companyPageIds(grantee, true).contains(sharedCompany.getId()));
        assertFalse(personPageIds(grantee, false).contains(sharedPerson.getId()));
        assertFalse(companyPageIds(grantee, false).contains(sharedCompany.getId()));

        assertTrue(personPageIds(workspace, true).contains(sharedPerson.getId()));
        assertTrue(companyPageIds(workspace, true).contains(sharedCompany.getId()));
    }

    private List<Integer> recentMoveIds() {
        return employmentMapper.getRecentMoves(workspace.getId(),
                LocalDateTime.now().minusDays(30).format(MYSQL_DATETIME), 100)
            .stream().map(JobMoveDto::getPersonId).toList();
    }

    private void recordMove(Person person, Company from, Company to) {
        insertEmployment(person, from, LocalDateTime.now().minusDays(400),
            LocalDateTime.now().minusDays(3));
        insertEmployment(person, to, LocalDateTime.now().minusDays(2), null);
    }

    private void insertEmployment(Person person, Company company, LocalDateTime startedAt,
            LocalDateTime endedAt) {
        PersonEmployment employment = new PersonEmployment();
        employment.setWorkspaceId(workspace.getId());
        employment.setPersonId(person.getId());
        employment.setCompanyId(company.getId());
        employment.setCompanyName(company.getName());
        employment.setTitle("Engineer");
        employment.setStartedAt(startedAt.format(MYSQL_DATETIME));
        employment.setEndedAt(endedAt == null ? null : endedAt.format(MYSQL_DATETIME));
        employmentMapper.insert(employment);
    }

    private Person personPageOf(Person person) {
        return personMapper.getPersonsPage(workspace.getId(), null, "name", "asc", null, null,
                false, allTeamScope(), null, false, null, false, null, false, false, null, 500, 0)
            .stream().filter(row -> row.getId() == person.getId()).findFirst().orElseThrow();
    }

    private Attachment newAttachment(String entityType, int entityId) {
        Attachment attachment = new Attachment();
        attachment.setWorkspaceId(workspace.getId());
        attachment.setEntityType(entityType);
        attachment.setEntityId(entityId);
        attachment.setFileName("file-" + unique() + ".pdf");
        attachment.setUrl("/api/attachments/content/" + unique());
        attachment.setContentType("application/pdf");
        attachment.setSize(1024L);
        attachmentMapper.insert(attachment);
        return attachment;
    }

    private String pagedLabel(Attachment attachment, int currentUserId) {
        return attachmentMapper.getPage(workspace.getId(), currentUserId, null, null, null, null, null, null, 100, 0)
            .stream().filter(row -> row.getId() == attachment.getId()).findFirst().orElseThrow()
            .getEntityLabel();
    }

    private Deal newDealWithoutCompany(Pipeline pipeline, Stage stage) {
        Deal deal = new Deal();
        deal.setName("Deal " + unique());
        deal.setWorkspaceId(workspace.getId());
        deal.setValue(new BigDecimal("1000.00"));
        deal.setCurrency("JPY");
        deal.setPipelineId(pipeline.getId());
        deal.setStageId(stage.getId());
        dealMapper.insert(deal);
        return deal;
    }

    private Note newWorkspaceNote(Person person) {
        Note note = new Note();
        note.setWorkspaceId(workspace.getId());
        note.setContent("Note " + unique());
        note.setTitle("Title " + unique());
        note.setVisibility("workspace");
        note.setAuthor(newUser());
        note.setPerson(person);
        noteMapper.insert(note);
        return note;
    }

    private Activity newActivity(Person person, Deal deal, User user) {
        Activity activity = new Activity();
        activity.setWorkspaceId(workspace.getId());
        activity.setType("meeting");
        activity.setSubject("Activity " + unique());
        activity.setPerson(person);
        activity.setDeal(deal);
        activity.setCreatedBy(user);
        activity.setTimestamp("2026-07-30 10:00:00");
        activityMapper.insert(activity);
        return activity;
    }

    private Task newTask(Person person, Deal deal, User user) {
        Task task = new Task();
        task.setWorkspaceId(workspace.getId());
        task.setDescription("Task " + unique());
        task.setStatus("todo");
        task.setPosition(0);
        task.setDueDate("2026-08-01");
        task.setAssignedTo(user);
        task.setPerson(person);
        task.setDeal(deal);
        taskMapper.insert(task);
        return task;
    }

    private Note newTimelineNote(Person person, Deal deal, User user) {
        Note note = new Note();
        note.setWorkspaceId(workspace.getId());
        note.setContent("Timeline note " + unique());
        note.setTitle("Timeline title " + unique());
        note.setVisibility("workspace");
        note.setAuthor(user);
        note.setPerson(person);
        note.setDeal(deal);
        noteMapper.insert(note);
        return note;
    }

    private void assertCompanyTimelineContains(
            Company company, User user, Activity activity, Task task, Note note) {
        assertEquals(List.of(activity.getId()), activityMapper.getCompanyActivities(
            workspace.getId(), company.getId(), 100).stream().map(Activity::getId).toList());
        assertEquals(List.of(task.getId()), taskMapper.getCompanyTasks(
            workspace.getId(), company.getId(), 100).stream().map(Task::getId).toList());
        assertEquals(List.of(note.getId()), noteMapper.getVisibleCompanyNotes(
            workspace.getId(), company.getId(), user.getId(), 100)
            .stream().map(Note::getId).toList());
    }

    private void sharePerson(Person person, Workspace grantee) {
        jdbcTemplate.update(
            "INSERT INTO person_share (person_id, workspace_id) VALUES (?, ?)",
            person.getId(), grantee.getId());
    }

    private void shareCompany(Company company, Workspace grantee) {
        jdbcTemplate.update(
            "INSERT INTO company_share (company_id, workspace_id) VALUES (?, ?)",
            company.getId(), grantee.getId());
    }

    private List<Integer> personPageIds(Workspace viewer, boolean archived) {
        return personMapper.getPersonsPage(viewer.getId(), null, "name", "asc", null, null, false,
                allTeamScope(), null, false, null, false, null, false, archived, null, 500, 0)
            .stream().map(Person::getId).toList();
    }

    private List<Integer> companyPageIds(Workspace viewer, boolean archived) {
        return companyMapper.getCompaniesPage(viewer.getId(), null, "name", "asc", null, false, null,
                allTeamScope(), archived, null, 500, 0)
            .stream().map(Company::getId).toList();
    }

    private List<RelationshipScoreAggregateDto> personScores(LocalDateTime reference) {
        return personMapper.getRelationshipScoreAggregates(
            workspace.getId(), reference, RelationshipWarmthModel.current().sqlParameters());
    }

    private RelationshipScoreAggregateDto personScore(int personId, LocalDateTime reference) {
        return personScores(reference).stream()
            .filter(score -> score.id() == personId)
            .findFirst()
            .orElseThrow();
    }

    private List<RelationshipScoreAggregateDto> companyScores(LocalDateTime reference) {
        return companyMapper.getRelationshipScoreAggregates(
            workspace.getId(), reference, RelationshipWarmthModel.current().sqlParameters());
    }

    private RelationshipScoreAggregateDto companyScore(int companyId, LocalDateTime reference) {
        return companyScores(reference).stream()
            .filter(score -> score.id() == companyId)
            .findFirst()
            .orElseThrow();
    }

    private Map<String, Long> facetCounts(List<FacetCount> counts) {
        return counts.stream().collect(Collectors.toMap(FacetCount::getKey, FacetCount::getCount));
    }

    private static MemberScope allTeamScope() {
        return MemberScope.allTeam();
    }

    private Workspace newWorkspace(String prefix) {
        String suffix = unique();
        Workspace created = new Workspace();
        created.setName(prefix + "-" + suffix);
        created.setSlug(prefix + "-" + suffix);
        workspaceMapper.insert(created);
        return created;
    }
}
