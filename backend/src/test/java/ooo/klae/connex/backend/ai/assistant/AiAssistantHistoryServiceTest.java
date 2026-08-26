package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
        List<Activity> personActivities = List.of(new Activity());
        List<Activity> dealActivities = List.of(new Activity());
        List<Activity> companyActivities = List.of(new Activity());
        List<Task> personTasks = List.of(new Task());
        List<Task> dealTasks = List.of(new Task());
        List<Task> companyTasks = List.of(new Task());
        List<Note> companyNotes = List.of(new Note());
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
        when(referenceService.hydrateActivities(7, personActivities)).thenReturn(personActivities);
        when(referenceService.hydrateActivities(7, dealActivities)).thenReturn(dealActivities);
        when(referenceService.hydrateActivities(7, companyActivities)).thenReturn(companyActivities);
        when(referenceService.hydrateTasks(7, personTasks)).thenReturn(personTasks);
        when(referenceService.hydrateTasks(7, dealTasks)).thenReturn(dealTasks);
        when(referenceService.hydrateTasks(7, companyTasks)).thenReturn(companyTasks);
        when(referenceService.hydrate(7, companyNotes)).thenReturn(companyNotes);

        assertEquals(personActivities, service.activitiesForPerson(17, null, null, 3));
        assertEquals(dealActivities, service.activitiesForDeal(8, null, null, 4));
        assertEquals(companyActivities, service.activitiesForCompany(5, null, null, 7));
        assertEquals(personTasks, service.tasksForPerson(17, 5));
        assertEquals(dealTasks, service.tasksForDeal(8, 6));
        assertEquals(companyTasks, service.tasksForCompany(5, 8));
        assertEquals(companyNotes, service.notesForCompany(5, 9));

        verify(activityMapper).getAiAssistantActivitiesByPersonId(7, 17, List.of(7, 9), null, null, 3);
        verify(activityMapper).getAiAssistantActivitiesByDealId(7, 8, List.of(7, 9), null, null, 4);
        verify(activityMapper).getAiAssistantActivitiesByCompanyId(7, 5, List.of(7, 9), null, null, 7);
        verify(taskMapper).getAiAssistantTasksByPersonId(7, 17, List.of(7, 9), 5);
        verify(taskMapper).getAiAssistantTasksByDealId(7, 8, List.of(7, 9), 6);
        verify(taskMapper).getAiAssistantTasksByCompanyId(7, 5, List.of(7, 9), 8);
        verify(noteMapper).getAiAssistantVisibleNotesByCompanyId(7, 5, 11, List.of(7, 9), 9);
        verify(workspaceScopeControlAccess, times(7)).getForWorkspace(7);
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
