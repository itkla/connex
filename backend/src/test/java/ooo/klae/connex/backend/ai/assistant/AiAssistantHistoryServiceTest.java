package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.ai.masking.MaskingEngine;
import ooo.klae.connex.backend.ai.mission.AiAgentScopePolicy;
import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.EntityReference;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.services.OrganizationWorkspaceScopeControlAccess;
import ooo.klae.connex.backend.services.OrganizationWorkspaceScopeControlOperations.WorkspaceScope;
import ooo.klae.connex.backend.services.ReferenceService;
import ooo.klae.connex.backend.services.WorkspaceService;

class AiAssistantHistoryServiceTest {
    private ActivityMapper activityMapper;
    private NoteMapper noteMapper;
    private TaskMapper taskMapper;
    private OrganizationWorkspaceScopeControlAccess workspaceScopeControlAccess;
    private WorkspaceService workspaceService;
    private ReferenceService referenceService;
    private AiAssistantHistoryService service;

    @BeforeEach
    void setUp() {
        activityMapper = mock(ActivityMapper.class);
        noteMapper = mock(NoteMapper.class);
        taskMapper = mock(TaskMapper.class);
        workspaceScopeControlAccess = mock(OrganizationWorkspaceScopeControlAccess.class);
        workspaceService = mock(WorkspaceService.class);
        referenceService = mock(ReferenceService.class);
        service = new AiAssistantHistoryService(
                activityMapper,
                noteMapper,
                taskMapper,
                workspaceScopeControlAccess,
                workspaceService,
                referenceService);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(workspaceService.getCurrentUserId()).thenReturn(11);
        when(workspaceScopeControlAccess.getForWorkspace(7))
                .thenReturn(new WorkspaceScope(70, List.of(7, 9), "[7,9]"));
    }

    @Test
    void boundedCandidateReadsStayWorkspaceScopedAndHydrateTheirExactPages() {
        Activity personActivitiesRow = new Activity();
        personActivitiesRow.setId(1);
        List<Activity> personActivities = List.of(personActivitiesRow);
        Activity hydratedPersonActivitiesRow = new Activity();
        hydratedPersonActivitiesRow.setId(11);
        List<Activity> hydratedPersonActivities = List.of(hydratedPersonActivitiesRow);
        Activity dealActivitiesRow = new Activity();
        dealActivitiesRow.setId(2);
        List<Activity> dealActivities = List.of(dealActivitiesRow);
        Activity hydratedDealActivitiesRow = new Activity();
        hydratedDealActivitiesRow.setId(12);
        List<Activity> hydratedDealActivities = List.of(hydratedDealActivitiesRow);
        Activity companyActivitiesRow = new Activity();
        companyActivitiesRow.setId(3);
        List<Activity> companyActivities = List.of(companyActivitiesRow);
        Activity hydratedCompanyActivitiesRow = new Activity();
        hydratedCompanyActivitiesRow.setId(13);
        List<Activity> hydratedCompanyActivities = List.of(hydratedCompanyActivitiesRow);
        Task personTasksRow = new Task();
        personTasksRow.setId(4);
        List<Task> personTasks = List.of(personTasksRow);
        Task hydratedPersonTasksRow = new Task();
        hydratedPersonTasksRow.setId(14);
        List<Task> hydratedPersonTasks = List.of(hydratedPersonTasksRow);
        Task dealTasksRow = new Task();
        dealTasksRow.setId(5);
        List<Task> dealTasks = List.of(dealTasksRow);
        Task hydratedDealTasksRow = new Task();
        hydratedDealTasksRow.setId(15);
        List<Task> hydratedDealTasks = List.of(hydratedDealTasksRow);
        Task companyTasksRow = new Task();
        companyTasksRow.setId(6);
        List<Task> companyTasks = List.of(companyTasksRow);
        Task hydratedCompanyTasksRow = new Task();
        hydratedCompanyTasksRow.setId(16);
        List<Task> hydratedCompanyTasks = List.of(hydratedCompanyTasksRow);
        Note companyNotesRow = new Note();
        companyNotesRow.setId(7);
        List<Note> companyNotes = List.of(companyNotesRow);
        Note hydratedCompanyNotesRow = new Note();
        hydratedCompanyNotesRow.setId(17);
        List<Note> hydratedCompanyNotes = List.of(hydratedCompanyNotesRow);
        when(activityMapper.getAiAssistantActivitiesByPersonId(7, 17, List.of(7, 9), null, null, 3))
                .thenReturn(personActivities);
        when(activityMapper.getAiAssistantActivitiesByDealId(7, 8, List.of(7, 9), null, null, 4))
                .thenReturn(dealActivities);
        when(activityMapper.getAiAssistantActivitiesByCompanyId(7, 5, List.of(7, 9), null, null, 7))
                .thenReturn(companyActivities);
        when(taskMapper.getAiAssistantTasksByPersonId(7, 17, List.of(7, 9), 5))
                .thenReturn(personTasks);
        when(taskMapper.getAiAssistantTasksByDealId(7, 8, List.of(7, 9), 6))
                .thenReturn(dealTasks);
        when(taskMapper.getAiAssistantTasksByCompanyId(7, 5, List.of(7, 9), 8))
                .thenReturn(companyTasks);
        when(noteMapper.getAiAssistantVisibleNotesByCompanyId(7, 5, 11, List.of(7, 9), 9, false))
                .thenReturn(companyNotes);
        when(referenceService.hydrateActivities(eq(7), same(personActivities))).thenReturn(hydratedPersonActivities);
        when(referenceService.hydrateActivities(eq(7), same(dealActivities))).thenReturn(hydratedDealActivities);
        when(referenceService.hydrateActivities(eq(7), same(companyActivities))).thenReturn(hydratedCompanyActivities);
        when(referenceService.hydrateTasks(eq(7), same(personTasks))).thenReturn(hydratedPersonTasks);
        when(referenceService.hydrateTasks(eq(7), same(dealTasks))).thenReturn(hydratedDealTasks);
        when(referenceService.hydrateTasks(eq(7), same(companyTasks))).thenReturn(hydratedCompanyTasks);
        when(referenceService.hydrate(eq(7), same(companyNotes))).thenReturn(hydratedCompanyNotes);

        assertEquals(hydratedPersonActivities, service.activitiesForPerson(17, null, null, 3));
        assertEquals(hydratedDealActivities, service.activitiesForDeal(8, null, null, 4));
        assertEquals(hydratedCompanyActivities, service.activitiesForCompany(5, null, null, 7));
        assertEquals(hydratedPersonTasks, service.tasksForPerson(17, 5));
        assertEquals(hydratedDealTasks, service.tasksForDeal(8, 6));
        assertEquals(hydratedCompanyTasks, service.tasksForCompany(5, 8));
        assertEquals(hydratedCompanyNotes, service.notesForCompany(5, 9, false));

        verify(activityMapper).getAiAssistantActivitiesByPersonId(7, 17, List.of(7, 9), null, null, 3);
        verify(activityMapper).getAiAssistantActivitiesByDealId(7, 8, List.of(7, 9), null, null, 4);
        verify(activityMapper).getAiAssistantActivitiesByCompanyId(7, 5, List.of(7, 9), null, null, 7);
        verify(taskMapper).getAiAssistantTasksByPersonId(7, 17, List.of(7, 9), 5);
        verify(taskMapper).getAiAssistantTasksByDealId(7, 8, List.of(7, 9), 6);
        verify(taskMapper).getAiAssistantTasksByCompanyId(7, 5, List.of(7, 9), 8);
        verify(noteMapper).getAiAssistantVisibleNotesByCompanyId(7, 5, 11, List.of(7, 9), 9, false);
        verify(referenceService).hydrateActivities(eq(7), same(personActivities));
        verify(referenceService).hydrateActivities(eq(7), same(dealActivities));
        verify(referenceService).hydrateActivities(eq(7), same(companyActivities));
        verify(referenceService).hydrateTasks(eq(7), same(personTasks));
        verify(referenceService).hydrateTasks(eq(7), same(dealTasks));
        verify(referenceService).hydrateTasks(eq(7), same(companyTasks));
        verify(referenceService).hydrate(eq(7), same(companyNotes));
        verify(workspaceScopeControlAccess, times(7)).getForWorkspace(7);
    }

    @Test
    void activityBoundsReachEveryRecordSpecificMapperQuery() {
        LocalDateTime personStart = LocalDateTime.parse("2026-08-01T01:02:03");
        LocalDateTime personEnd = LocalDateTime.parse("2026-08-02T04:05:06");
        LocalDateTime dealStart = LocalDateTime.parse("2026-08-03T07:08:09");
        LocalDateTime dealEnd = LocalDateTime.parse("2026-08-04T10:11:12");
        LocalDateTime companyStart = LocalDateTime.parse("2026-08-05T13:14:15");
        LocalDateTime companyEnd = LocalDateTime.parse("2026-08-06T16:17:18");

        service.activitiesForPerson(17, personStart, personEnd, 3);
        service.activitiesForDeal(8, dealStart, dealEnd, 4);
        service.activitiesForCompany(5, companyStart, companyEnd, 7);

        verify(activityMapper).getAiAssistantActivitiesByPersonId(
                7, 17, List.of(7, 9), personStart, personEnd, 3);
        verify(activityMapper).getAiAssistantActivitiesByDealId(
                7, 8, List.of(7, 9), dealStart, dealEnd, 4);
        verify(activityMapper).getAiAssistantActivitiesByCompanyId(
                7, 5, List.of(7, 9), companyStart, companyEnd, 7);
        verify(workspaceScopeControlAccess, times(3)).getForWorkspace(7);
    }

    @Test
    void historyLimitsFailClosedOutsideTheToolContract() {
        assertThrows(IllegalArgumentException.class, () -> service.activitiesForPerson(17, null, null, 0));
        assertThrows(IllegalArgumentException.class, () -> service.tasksForDeal(8, 21));

        verifyNoInteractions(
                activityMapper,
                noteMapper,
                taskMapper,
                workspaceScopeControlAccess,
                workspaceService,
                referenceService);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void privacyModeReachesEveryNoteQueryBeforeItsLimit(boolean includePrivate) {
        service.notesForPerson(17, 10, includePrivate);
        service.notesForDeal(8, 10, includePrivate);
        service.notesForCompany(5, 10, includePrivate);
        verify(noteMapper).getAiAssistantVisibleNotesByPersonId(7, 17, 11, List.of(7, 9), 10, includePrivate);
        verify(noteMapper).getAiAssistantVisibleNotesByDealId(7, 8, 11, List.of(7, 9), 10, includePrivate);
        verify(noteMapper).getAiAssistantVisibleNotesByCompanyId(7, 5, 11, List.of(7, 9), 10, includePrivate);
    }

    @Test
    void missionParentRefusalPrecedesEveryHistoryQuery() {
        new AiAgentScopePolicy("person", 17).evaluate(() -> {
            assertThrows(AiAssistantLoopException.class, () -> service.activitiesForPerson(18, null, null, 3));
            assertThrows(AiAssistantLoopException.class, () -> service.activitiesForDeal(8, null, null, 3));
            assertThrows(AiAssistantLoopException.class, () -> service.activitiesForCompany(5, null, null, 3));
            assertThrows(AiAssistantLoopException.class, () -> service.tasksForPerson(18, 3));
            assertThrows(AiAssistantLoopException.class, () -> service.tasksForDeal(8, 3));
            assertThrows(AiAssistantLoopException.class, () -> service.tasksForCompany(5, 3));
            assertThrows(AiAssistantLoopException.class, () -> service.notesForPerson(18, 3, false));
            assertThrows(AiAssistantLoopException.class, () -> service.notesForDeal(8, 3, false));
            assertThrows(AiAssistantLoopException.class, () -> service.notesForCompany(5, 3, false));
            return null;
        });

        assertNull(AiAgentScopePolicy.current());
        verifyNoInteractions(activityMapper, noteMapper, taskMapper, workspaceScopeControlAccess,
                workspaceService, referenceService);
    }

    @Test
    void companyMissionDoesNotTraverseIndirectPersonOrDealHistory() {
        new AiAgentScopePolicy("company", 5).evaluate(() -> {
            assertEquals(List.of(), service.activitiesForCompany(5, null, null, 3));
            assertEquals(List.of(), service.tasksForCompany(5, 3));
            assertEquals(List.of(), service.notesForCompany(5, 3, true));
            return null;
        });

        verifyNoInteractions(activityMapper, noteMapper, taskMapper, workspaceScopeControlAccess,
                workspaceService, referenceService);
    }

    @Test
    void missionNotesKeepBoundedPrivacyQueryAndMaskingProvenanceWithoutHydratingLinkedRecords() {
        Person primary = new Person();
        primary.setId(17);
        Note raw = new Note();
        raw.setId(51);
        raw.setWorkspaceId(7);
        raw.setPerson(primary);
        raw.setVisibility("private");
        raw.setCreatedAt("2026-10-01T00:00:00Z");
        raw.setTitle("Follow up with [Private company](company:90)");
        raw.setContent("[Primary](person:17) [Outside](person:18) [Former Alice](user:99) said Former Alice");
        when(noteMapper.getAiAssistantVisibleNotesByPersonId(7, 17, 11, List.of(7, 9), 3, true))
                .thenReturn(List.of(raw));

        Note projected = new AiAgentScopePolicy("person", 17)
                .evaluate(() -> service.notesForPerson(17, 3, true)).getFirst();

        verify(noteMapper).getAiAssistantVisibleNotesByPersonId(7, 17, 11, List.of(7, 9), 3, true);
        verifyNoInteractions(referenceService);
        assertNotSame(raw, projected);
        assertEquals(17, projected.getPerson().getId());
        assertNull(projected.getDeal());
        assertEquals("private", projected.getVisibility());
        assertEquals(raw.getCreatedAt(), projected.getCreatedAt());
        assertTrue(projected.getContent().contains("[Primary](person:17)"));
        assertFalse(projected.getContent().contains("Outside"));
        assertFalse(projected.getContent().contains("user:99"));
        assertFalse(projected.getTitle().contains("Private company"));
        assertEquals(List.of("Former Alice"), projected.getReferences().stream().map(EntityReference::getLabel).toList());
        assertTrue(projected.getReferences().stream().allMatch(reference -> "user".equals(reference.getRefType())));
        MaskingContext context = new MaskingContext();
        AiAssistantMemberIdentifiers.seedReferences(projected.getReferences(), context);
        assertFalse(MaskingEngine.screenFreeTextBeforeTruncation(projected.getContent(), context).contains("Former Alice"));
        assertTrue(raw.getContent().contains("Outside"));
    }

    @Test
    void missionActivityAndTasksKeepDirectEvidenceWithoutRelatedParentExpansion() {
        LocalDateTime start = LocalDateTime.parse("2026-10-01T00:00:00");
        LocalDateTime end = start.plusDays(1);
        Deal primary = new Deal();
        primary.setId(8);
        Activity rawActivity = new Activity();
        rawActivity.setId(21);
        rawActivity.setWorkspaceId(7);
        rawActivity.setDeal(primary);
        rawActivity.setSubject("Discuss [Outside](person:18)");
        rawActivity.setNotes("[Former Bob][owner] said Former Bob\n\n[owner]: user:99");
        rawActivity.setType("call");
        rawActivity.setTimestamp("2026-10-01T02:00:00Z");
        Task rawTask = new Task();
        rawTask.setId(22);
        rawTask.setWorkspaceId(7);
        rawTask.setDeal(primary);
        rawTask.setDescription("[Owner Carol](user&colon;99) said Owner Carol about [Other deal](deal:9)");
        rawTask.setStatus("pending");
        User assigned = new User();
        assigned.setId(11);
        rawTask.setAssignedTo(assigned);
        when(activityMapper.getAiAssistantActivitiesByDealId(7, 8, List.of(7, 9), start, end, 3))
                .thenReturn(List.of(rawActivity));
        when(taskMapper.getAiAssistantTasksByDealId(7, 8, List.of(7, 9), 3)).thenReturn(List.of(rawTask));

        new AiAgentScopePolicy("deal", 8).evaluate(() -> {
            Activity activity = service.activitiesForDeal(8, start, end, 3).getFirst();
            Task task = service.tasksForDeal(8, 3).getFirst();
            assertNull(activity.getPerson());
            assertNull(task.getPerson());
            assertEquals(8, activity.getDeal().getId());
            assertEquals(8, task.getDeal().getId());
            assertFalse(activity.getSubject().contains("Outside"));
            assertFalse(activity.getNotes().contains("user:99"));
            assertFalse(task.getDescription().contains("Other deal"));
            assertFalse(task.getDescription().contains("user&colon;99"));
            assertEquals("call", activity.getType());
            assertEquals(rawActivity.getTimestamp(), activity.getTimestamp());
            assertEquals("pending", task.getStatus());
            assertEquals(11, task.getAssignedTo().getId());
            assertEquals(List.of("Former Bob"), activity.getReferences().stream().map(EntityReference::getLabel).toList());
            assertEquals(List.of("Owner Carol"), task.getReferences().stream().map(EntityReference::getLabel).toList());
            return null;
        });

        verify(activityMapper).getAiAssistantActivitiesByDealId(7, 8, List.of(7, 9), start, end, 3);
        verify(taskMapper).getAiAssistantTasksByDealId(7, 8, List.of(7, 9), 3);
        verifyNoInteractions(referenceService);
        assertTrue(rawTask.getDescription().contains("Other deal"));
    }

    @Test
    void missionSuppressesDualParentEvidenceWithoutLookingUpSecondaryLabels() {
        Person person = new Person();
        person.setId(17);
        Deal deal = new Deal();
        deal.setId(8);
        Activity activity = new Activity();
        activity.setPerson(person);
        activity.setDeal(deal);
        activity.setNotes("Secondary parent name must not enter model context");
        Task task = new Task();
        task.setPerson(person);
        task.setDeal(deal);
        task.setDescription("Secondary parent name must not enter model context");
        Note note = new Note();
        note.setPerson(person);
        note.setDeal(deal);
        note.setContent("Secondary parent name must not enter model context");
        when(activityMapper.getAiAssistantActivitiesByPersonId(7, 17, List.of(7, 9), null, null, 3))
                .thenReturn(List.of(activity));
        when(activityMapper.getAiAssistantActivitiesByDealId(7, 8, List.of(7, 9), null, null, 3))
                .thenReturn(List.of(activity));
        when(taskMapper.getAiAssistantTasksByPersonId(7, 17, List.of(7, 9), 3)).thenReturn(List.of(task));
        when(taskMapper.getAiAssistantTasksByDealId(7, 8, List.of(7, 9), 3)).thenReturn(List.of(task));
        when(noteMapper.getAiAssistantVisibleNotesByPersonId(7, 17, 11, List.of(7, 9), 3, false))
                .thenReturn(List.of(note));
        when(noteMapper.getAiAssistantVisibleNotesByDealId(7, 8, 11, List.of(7, 9), 3, false))
                .thenReturn(List.of(note));

        new AiAgentScopePolicy("person", 17).evaluate(() -> {
            assertEquals(List.of(), service.activitiesForPerson(17, null, null, 3));
            assertEquals(List.of(), service.tasksForPerson(17, 3));
            assertEquals(List.of(), service.notesForPerson(17, 3, false));
            return null;
        });
        new AiAgentScopePolicy("deal", 8).evaluate(() -> {
            assertEquals(List.of(), service.activitiesForDeal(8, null, null, 3));
            assertEquals(List.of(), service.tasksForDeal(8, 3));
            assertEquals(List.of(), service.notesForDeal(8, 3, false));
            return null;
        });

        verifyNoInteractions(referenceService);
        assertEquals(person, activity.getPerson());
        assertEquals(deal, task.getDeal());
        assertEquals("Secondary parent name must not enter model context", note.getContent());
    }

}
