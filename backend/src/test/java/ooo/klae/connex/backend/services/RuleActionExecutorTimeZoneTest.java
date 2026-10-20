package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.context.SecurityContextHolder;

import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.DealDocumentMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.notifications.NotificationDelivery;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

class RuleActionExecutorTimeZoneTest {

    private final TaskService taskService = mock(TaskService.class);
    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final TenantContext tenantContext = new TenantContext();
    private final RuleActionExecutor executor = new RuleActionExecutor(
        taskService, mock(ActivityService.class), mock(CompanyService.class), mock(PersonService.class),
        mock(LeadResponseSlaService.class), mock(DealService.class), mock(NoteService.class),
        mock(NotificationDelivery.class), mock(TagMapper.class), mock(DealDocumentMapper.class),
        mock(CampaignTriggeredSendService.class), workspaceService, tenantContext);

    @AfterEach
    void clearContext() {
        tenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @CsvSource({
        "2026-10-20T14:59:59Z, 2026-10-22",
        "2026-10-20T15:00:01Z, 2026-10-23"
    })
    void runAsInstallsTheWorkspaceAndActorForLocalTaskDates(String asOf, String dueDate) {
        WorkspaceMapper workspaceMapper = mock(WorkspaceMapper.class);
        when(workspaceMapper.getOrgId(7)).thenReturn(42);
        TenantWorkScope scope = new TenantWorkScope(
            tenantContext, mock(TenantCatalogResolver.class), workspaceMapper);
        AutomationExecutor automation = new AutomationExecutor(tenantContext, new AutomationScope(), scope);
        User actor = new User();
        actor.setId(17);
        when(workspaceService.getCurrentAnalyticsTimezone()).thenAnswer(invocation -> {
            assertEquals(7, tenantContext.getWorkspaceId());
            assertSame(actor, SecurityContextHolder.getContext().getAuthentication().getPrincipal());
            return "Asia/Tokyo";
        });

        withClock(asOf, () -> automation.runAs(7, actor, "member",
            () -> executor.execute(action(), context())));

        assertDueDate(dueDate);
        verify(workspaceService).getCurrentAnalyticsTimezone();
        assertFalse(tenantContext.isResolved());
    }

    @Test
    void noWorkspaceContextUsesUtcWithoutResolvingAnActor() {
        withClock("2026-10-20T15:00:01Z", () -> executor.execute(action(), context()));

        assertDueDate("2026-10-22");
        verifyNoInteractions(workspaceService);
    }

    @Test
    void workspaceResolutionFailuresPropagateWithoutCreatingATask() {
        tenantContext.set(7, 42, 17, "member", null);
        ForbiddenException failure = new ForbiddenException("Workspace is unavailable");
        when(workspaceService.getCurrentAnalyticsTimezone()).thenThrow(failure);

        assertSame(failure, assertThrows(ForbiddenException.class, () -> executor.execute(action(), context())));

        verifyNoInteractions(taskService);
    }

    private void assertDueDate(String expected) {
        ArgumentCaptor<Task> task = ArgumentCaptor.forClass(Task.class);
        verify(taskService).create(task.capture());
        assertEquals(expected, task.getValue().getDueDate());
    }

    private static RuleAction action() {
        RuleAction action = new RuleAction();
        action.setType("create_task");
        action.setTitle("Follow up");
        action.setDueInDays(2);
        return action;
    }

    private static RuleFireContext context() {
        return new RuleFireContext(7, 11, "person", 19, 17, "event");
    }

    private static void withClock(String asOf, Runnable work) {
        Clock fixed = Clock.fixed(Instant.parse(asOf), ZoneOffset.UTC);
        try (var clocks = mockStatic(Clock.class)) {
            clocks.when(() -> Clock.system(any(ZoneId.class)))
                .thenAnswer(invocation -> fixed.withZone(invocation.getArgument(0)));
            work.run();
        }
    }
}
