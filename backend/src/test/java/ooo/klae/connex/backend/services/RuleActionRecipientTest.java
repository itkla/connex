package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;

import java.time.LocalDate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.DealDocument;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.DealDocumentMapper;
import ooo.klae.connex.backend.mappers.TagMapper;

/** Exercises the recipient and source boundary shared by both workflow runtimes. */
@ExtendWith(MockitoExtension.class)
class RuleActionRecipientTest {
    @Mock private TaskService taskService;
    @Mock private ActivityService activityService;
    @Mock private CompanyService companyService;
    @Mock private PersonService personService;
    @Mock private LeadResponseSlaService leadResponseSlaService;
    @Mock private DealService dealService;
    @Mock private NoteService noteService;
    @Mock private WorkflowNotificationDeliveryService notificationDelivery;
    @Mock private TagMapper tagMapper;
    @Mock private DealDocumentMapper documentMapper;
    @Mock private CampaignTriggeredSendService campaignTriggeredSendService;
    @Mock private WorkspaceService workspaceService;
    @Mock private UserCalendarService userCalendarService;
    @Mock private WorkflowTaskCreationService workflowTaskCreationService;
    @InjectMocks private RuleActionExecutor executor;

    @BeforeEach
    void defaults() {
        lenient().when(userCalendarService.today()).thenReturn(LocalDate.of(2026, 11, 6));
    }

    @ParameterizedTest
    @CsvSource({"Asia/Tokyo,2026-11-07", "UTC,2026-11-06"})
    void zeroUsesTheExecutionProfilesCalendarAcrossMidnight(String timezone, String expectedDate) {
        AuthService auth = mock(AuthService.class);
        var actor = new ooo.klae.connex.backend.beans.User();
        actor.setTimezone(timezone);
        when(auth.getCurrentUser()).thenReturn(actor);
        Clock clock = Clock.fixed(Instant.parse("2026-11-06T15:30:00Z"), ZoneOffset.UTC);
        RuleActionExecutor zonedExecutor = new RuleActionExecutor(taskService, activityService,
            companyService, personService, leadResponseSlaService, dealService, noteService,
            notificationDelivery, tagMapper, documentMapper, campaignTriggeredSendService,
            workspaceService, new UserCalendarService(auth, clock), workflowTaskCreationService);
        RuleAction action = action("create_task");
        action.setDueInDays(0);

        zonedExecutor.execute(action, contexts().getFirst());

        ArgumentCaptor<Task> tasks = ArgumentCaptor.forClass(Task.class);
        verify(workflowTaskCreationService).create(eq(7), tasks.capture());
        assertEquals(expectedDate, tasks.getValue().getDueDate());
    }

    @Test
    void explicitMemberReceivesSameDayTaskInBothRuntimes() {
        RuleAction action = action("create_task");
        action.setTargetUserId(23);
        action.setDueInDays(0);
        when(workspaceService.getRole(7, 23)).thenReturn("member");
        LocalDate today = LocalDate.of(2026, 11, 6);
        for (AutomationActionContext context : contexts()) {
            executor.execute(action, context);
        }
        ArgumentCaptor<Task> tasks = ArgumentCaptor.forClass(Task.class);
        verify(workflowTaskCreationService, times(2)).create(eq(7), tasks.capture());
        for (Task task : tasks.getAllValues()) {
            assertEquals(23, task.getAssignedTo().getId());
            assertEquals(today.toString(), task.getDueDate());
            assertEquals(19, task.getPerson().getId());
        }
    }

    @Test
    void absentRecipientAndDueDaysRetainRunAsMemberAndDefaultDate() {
        executor.execute(action("create_task"), contexts().getFirst());
        ArgumentCaptor<Task> tasks = ArgumentCaptor.forClass(Task.class);
        verify(workflowTaskCreationService).create(eq(7), tasks.capture());
        assertEquals(17, tasks.getValue().getAssignedTo().getId());
        assertEquals(LocalDate.of(2026, 11, 9).toString(), tasks.getValue().getDueDate());
    }

    @Test
    void notificationNamesAndLinksTheSourceAndUsesTheChosenMember() {
        RuleAction action = action("notify");
        action.setTargetUserId(23);
        action.setBody("Check the company");
        when(workspaceService.getRole(7, 23)).thenReturn("member");
        Person person = new Person();
        person.setName("Source contact");
        when(personService.getPersonById(19)).thenReturn(person);
        executor.execute(action, contexts().getFirst());
        ArgumentCaptor<Notification> notices = ArgumentCaptor.forClass(Notification.class);
        verify(notificationDelivery).deliver(notices.capture(), org.mockito.ArgumentMatchers.eq(17));
        Notification notice = notices.getValue();
        assertEquals(23, notice.getRecipientId());
        assertEquals(7, notice.getWorkspaceId());
        assertEquals("Source contact", notice.getSourceLabel());
        assertEquals("Source contact\nCheck the company", notice.getBody());
        assertEquals("/records/contacts/19", notice.getActionUrl());
    }

    @ParameterizedTest
    @ValueSource(strings = {"company", "deal", "task"})
    void notificationLinksEachOtherSupportedRecordType(String recordType) {
        String expectedUrl;
        switch (recordType) {
            case "company" -> {
                Company company = new Company();
                company.setName("Source record");
                when(companyService.getCompanyById(19)).thenReturn(company);
                expectedUrl = "/records/companies/19";
            }
            case "deal" -> {
                Deal deal = new Deal();
                deal.setName("Source record");
                when(dealService.getDealById(19)).thenReturn(deal);
                expectedUrl = "/records/deals/19";
            }
            case "task" -> {
                Task task = new Task();
                task.setDescription("Source record");
                when(taskService.getTaskById(19)).thenReturn(task);
                expectedUrl = "/activity/tasks?task=19";
            }
            default -> throw new IllegalArgumentException(recordType);
        }
        executor.execute(action("notify"), new WorkflowActionContext(7, 13, "action", recordType, 19, 17));
        ArgumentCaptor<Notification> notices = ArgumentCaptor.forClass(Notification.class);
        verify(notificationDelivery).deliver(notices.capture(), org.mockito.ArgumentMatchers.eq(17));
        assertEquals("Source record", notices.getValue().getSourceLabel());
        assertEquals(expectedUrl, notices.getValue().getActionUrl());
    }

    @Test
    void longTaskLabelsFitTheSnapshotWithoutSplittingUnicodeCharacters() {
        Task task = new Task();
        task.setDescription("📞".repeat(300));
        when(taskService.getTaskById(19)).thenReturn(task);
        executor.execute(action("notify"), new WorkflowActionContext(7, 13, "action", "task", 19, 17));
        ArgumentCaptor<Notification> notices = ArgumentCaptor.forClass(Notification.class);
        verify(notificationDelivery).deliver(notices.capture(), org.mockito.ArgumentMatchers.eq(17));
        assertEquals("📞".repeat(255), notices.getValue().getSourceLabel());
    }

    @Test
    void unavailableSourceCreatesNoNotification() {
        when(personService.getPersonById(19)).thenThrow(new ResourceNotFoundException("Contact not found"));
        assertThrows(ResourceNotFoundException.class, () -> executor.execute(action("notify"), contexts().getFirst()));
        verifyNoInteractions(notificationDelivery);
    }

    @ParameterizedTest
    @ValueSource(strings = {"create_task", "notify"})
    void removedOrForeignMemberFailsWithoutSendingToTheActor(String type) {
        RuleAction action = action(type);
        action.setTargetUserId(23);
        WorkflowExecutionException failure = assertThrows(WorkflowExecutionException.class,
            () -> executor.execute(action, contexts().getFirst()));
        assertEquals("action_target_member_unavailable", failure.code());
        verifyNoInteractions(taskService, workflowTaskCreationService, notificationDelivery);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 366})
    void invalidDueDaysFailWithoutCreatingATask(int days) {
        RuleAction action = action("create_task");
        action.setDueInDays(days);
        assertThrows(BadRequestException.class, () -> executor.execute(action, contexts().getFirst()));
        verifyNoInteractions(taskService, workflowTaskCreationService);
    }

    @Test
    void documentNotificationUsesAnAuthorizedDocumentSourceAndExistingRoute() {
        DealDocument document = new DealDocument();
        document.setId(19);
        document.setDealId(31);
        document.setTitle("Source document");
        when(documentMapper.getById(7, 19)).thenReturn(document);
        executor.execute(action("notify"), new RuleFireContext(7, 11, "document", 19, 17, "event"));
        ArgumentCaptor<Notification> notices = ArgumentCaptor.forClass(Notification.class);
        verify(notificationDelivery).deliver(notices.capture(), org.mockito.ArgumentMatchers.eq(17));
        assertEquals("deal_document", notices.getValue().getSourceType());
        assertEquals("Source document", notices.getValue().getSourceLabel());
        assertEquals("/records/deals/31/documents/19/print", notices.getValue().getActionUrl());
    }

    private static RuleAction action(String type) {
        RuleAction action = new RuleAction();
        action.setType(type);
        action.setTitle("Follow up");
        return action;
    }

    private static List<AutomationActionContext> contexts() {
        return List.of(new RuleFireContext(7, 11, "person", 19, 17, "event"),
            new WorkflowActionContext(7, 13, "action", "person", 19, 17));
    }
}
