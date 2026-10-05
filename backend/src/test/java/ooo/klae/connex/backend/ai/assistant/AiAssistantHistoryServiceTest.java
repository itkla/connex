package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Note;
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
        when(noteMapper.getAiAssistantVisibleNotesByCompanyId(7, 5, 11, List.of(7, 9), 9))
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
        assertEquals(hydratedCompanyNotes, service.notesForCompany(5, 9));

        verify(activityMapper).getAiAssistantActivitiesByPersonId(7, 17, List.of(7, 9), null, null, 3);
        verify(activityMapper).getAiAssistantActivitiesByDealId(7, 8, List.of(7, 9), null, null, 4);
        verify(activityMapper).getAiAssistantActivitiesByCompanyId(7, 5, List.of(7, 9), null, null, 7);
        verify(taskMapper).getAiAssistantTasksByPersonId(7, 17, List.of(7, 9), 5);
        verify(taskMapper).getAiAssistantTasksByDealId(7, 8, List.of(7, 9), 6);
        verify(taskMapper).getAiAssistantTasksByCompanyId(7, 5, List.of(7, 9), 8);
        verify(noteMapper).getAiAssistantVisibleNotesByCompanyId(7, 5, 11, List.of(7, 9), 9);
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
}
