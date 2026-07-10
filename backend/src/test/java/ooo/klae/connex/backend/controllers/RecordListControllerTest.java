package ooo.klae.connex.backend.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.services.ActivityService;
import ooo.klae.connex.backend.services.BulkOperationService;
import ooo.klae.connex.backend.services.CompanyService;
import ooo.klae.connex.backend.services.ConnectionService;
import ooo.klae.connex.backend.services.DealRiskService;
import ooo.klae.connex.backend.services.DealService;
import ooo.klae.connex.backend.services.EmploymentService;
import ooo.klae.connex.backend.services.NoteService;
import ooo.klae.connex.backend.services.PersonService;
import ooo.klae.connex.backend.services.ScoringService;
import ooo.klae.connex.backend.services.TaskService;
import ooo.klae.connex.backend.services.WorkspaceService;

@ExtendWith(MockitoExtension.class)
class RecordListControllerTest {
    @Mock private PersonService personService;
    @Mock private EmploymentService employmentService;
    @Mock private ConnectionService connectionService;
    @Mock private BulkOperationService bulkOperationService;
    @Mock private CompanyService companyService;
    @Mock private DealService dealService;
    @Mock private DealRiskService dealRiskService;
    @Mock private WorkspaceService workspaceService;
    @Mock private NoteService noteService;
    @Mock private TaskService taskService;
    @Mock private ActivityService activityService;
    @Mock private ScoringService scoringService;

    @Test
    void personsWithoutFilterRequirePageEndpoint() {
        PersonController controller = new PersonController(
            personService, employmentService, connectionService, bulkOperationService);

        assertThrows(BadRequestException.class, () -> controller.getPersons(null, null, null));

        verify(personService, never()).getAllPersons();
    }

    @Test
    void personsPageClampsSize() {
        PersonController controller = new PersonController(
            personService, employmentService, connectionService, bulkOperationService);
        when(personService.getPersonsPage(null, null, null, null, null, false, 100, 0)).thenReturn(List.of());
        when(personService.countPersons(null, null, null, false)).thenReturn(0L);

        var response = controller.getPersonsPage(0, 500, null, null, null, null, null, false);

        assertEquals(0, response.total());
        verify(personService).getPersonsPage(null, null, null, null, null, false, 100, 0);
    }

    @Test
    void personsPageRejectsWarmthSort() {
        PersonController controller = new PersonController(
            personService, employmentService, connectionService, bulkOperationService);

        assertThrows(BadRequestException.class, () -> controller.getPersonsPage(
            1, 25, null, "warmth", "desc", null, null, false));

        verify(personService, never()).getPersonsPage(null, "warmth", "desc", null, null, false, 25, 0);
    }

    @Test
    void personIdsWithoutFilterRequireFilter() {
        PersonController controller = new PersonController(
            personService, employmentService, connectionService, bulkOperationService);

        assertThrows(BadRequestException.class, () -> controller.getPersonIds(null, null, null, false));

        verify(personService, never()).getMatchingPersonIds(null, null, null, false);
    }

    @Test
    void companiesWithoutFilterRequirePageEndpoint() {
        CompanyController controller = new CompanyController(companyService, bulkOperationService);

        assertThrows(BadRequestException.class, () -> controller.getAllCompanies(null));

        verify(companyService, never()).getAllCompanies();
    }

    @Test
    void companiesPageClampsSize() {
        CompanyController controller = new CompanyController(companyService, bulkOperationService);
        when(companyService.getCompaniesPage(100, 0)).thenReturn(List.of());
        when(companyService.countCompanies()).thenReturn(0L);

        var response = controller.getCompaniesPage(0, 500);

        assertEquals(0, response.total());
        verify(companyService).getCompaniesPage(100, 0);
    }

    @Test
    void dealsWithoutFilterRequirePageEndpoint() {
        DealController controller = new DealController(
            dealService, bulkOperationService, dealRiskService, workspaceService);

        assertThrows(BadRequestException.class, () -> controller.getDeals(null, null, null, null, null));

        verify(dealService, never()).getAllDeals();
    }

    @Test
    void dealsPageClampsSize() {
        DealController controller = new DealController(
            dealService, bulkOperationService, dealRiskService, workspaceService);
        when(dealService.getDealsPage(
            null, null, null, null, null, null, null, null, 100, 0)).thenReturn(List.of());
        when(dealService.countDeals(null, null, null, null, null, null)).thenReturn(37L);

        var response = controller.getDealsPage(
            0, 500, null, null, null, null, null, null, null, null);

        assertEquals(37, response.total());
        verify(dealService).getDealsPage(
            null, null, null, null, null, null, null, null, 100, 0);
        verify(dealService).countDeals(null, null, null, null, null, null);
    }

    @Test
    void dealsPageRejectsInvalidStatusAndDirection() {
        DealController controller = new DealController(
            dealService, bulkOperationService, dealRiskService, workspaceService);

        assertThrows(BadRequestException.class, () -> controller.getDealsPage(
            1, 25, null, null, "sideways", null, null, null, null, null));
        assertThrows(BadRequestException.class, () -> controller.getDealsPage(
            1, 25, null, null, null, null, null, null, null, "stale"));

        verify(dealService, never()).getDealsPage(
            null, null, null, null, null, null, null, null, 25, 0);
    }

    @Test
    void notesWithoutFilterRequirePageEndpoint() {
        NoteController controller = new NoteController(noteService);

        assertThrows(BadRequestException.class, () -> controller.getNotes(null, null, null));

        verify(noteService, never()).getAllNotes();
    }

    @Test
    void notesPageClampsSize() {
        NoteController controller = new NoteController(noteService);
        when(noteService.getNotesPage(100, 0)).thenReturn(List.of());
        when(noteService.countNotes()).thenReturn(0L);

        var response = controller.getNotesPage(0, 500);

        assertEquals(0, response.total());
        verify(noteService).getNotesPage(100, 0);
    }

    @Test
    void tasksWithoutFilterRequirePageEndpoint() {
        TaskController controller = new TaskController(taskService);

        assertThrows(BadRequestException.class, () -> controller.getTasks(null, null, null));

        verify(taskService, never()).getAllTasks();
    }

    @Test
    void tasksPageClampsSize() {
        TaskController controller = new TaskController(taskService);
        when(taskService.getTasksPage(100, 0)).thenReturn(List.of());
        when(taskService.countTasks()).thenReturn(0L);

        var response = controller.getTasksPage(0, 500);

        assertEquals(0, response.total());
        verify(taskService).getTasksPage(100, 0);
    }

    @Test
    void activitiesWithoutFilterOrPaginationRequirePageEndpoint() {
        ActivityController controller = new ActivityController(activityService);

        assertThrows(BadRequestException.class, () -> controller.getActivities(null, null, null, null, null));

        verify(activityService, never()).getAllActivities();
    }

    @Test
    void activitiesPageClampsSize() {
        ActivityController controller = new ActivityController(activityService);
        when(activityService.getActivitiesPage(null, null, null, 100, 0)).thenReturn(List.of());
        when(activityService.countActivities(null, null, null)).thenReturn(0L);

        var response = controller.getActivitiesPage(0, 500, null, null, null);

        assertEquals(0, response.total());
        verify(activityService).getActivitiesPage(null, null, null, 100, 0);
    }

    @Test
    void scoringContactsRequireIds() {
        ScoringController controller = new ScoringController(scoringService, workspaceService);

        assertThrows(BadRequestException.class, () -> controller.contacts(null));
    }

    @Test
    void scoringCompaniesRejectTooManyIds() {
        ScoringController controller = new ScoringController(scoringService, workspaceService);
        List<Integer> ids = java.util.stream.IntStream.rangeClosed(1, 101).boxed().toList();

        assertThrows(BadRequestException.class, () -> controller.companies(ids));
    }

    @Test
    void scoringContactsDelegateBoundedIds() {
        ScoringController controller = new ScoringController(scoringService, workspaceService);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(scoringService.scoreContacts(7, new java.util.LinkedHashSet<>(List.of(3, 4)))).thenReturn(List.of());

        List<?> response = controller.contacts(List.of(3, 4));

        assertEquals(0, response.size());
        verify(scoringService).scoreContacts(7, new java.util.LinkedHashSet<>(List.of(3, 4)));
    }
}
