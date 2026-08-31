package ooo.klae.connex.backend.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.dto.TaskWorkItem;
import ooo.klae.connex.backend.dto.TaskWorkPage;
import ooo.klae.connex.backend.dto.WorkItemAction;
import ooo.klae.connex.backend.dto.WorkItemSource;
import ooo.klae.connex.backend.dto.WorkItemUrgency;
import ooo.klae.connex.backend.services.TaskService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;

@ExtendWith(MockitoExtension.class)
class AssignedTaskWorkItemProviderTest {
    private static final Instant AS_OF = Instant.parse("2026-08-30T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 30);

    @Mock private TaskService taskService;
    @Mock private WorkspaceService workspaceService;

    @Test
    void mapsUrgencyReasonsEvidenceAndPermissionFilteredActions() {
        Task overdue = task(4, "Overdue", "2026-08-29", "2026-08-01 00:00:00");
        Task later = task(5, "Later", "2026-09-20", "2026-08-02 00:00:00");
        when(taskService.findOpenAssignedWork(AS_OF, TODAY, Set.of(), 25))
            .thenReturn(new TaskWorkPage(List.of(
                new TaskWorkItem(later, "b".repeat(64)),
                new TaskWorkItem(overdue, "a".repeat(64))), 2, 2, AS_OF));
        when(workspaceService.getCurrentPermissions()).thenReturn(Set.of(Permission.TASK_UPDATE));
        AssignedTaskWorkItemProvider provider = new AssignedTaskWorkItemProvider(
            taskService, workspaceService, Clock.fixed(AS_OF, ZoneOffset.UTC));

        var result = provider.load(query(Set.of(), 25));

        assertEquals(List.of("task:4", "task:5"), result.items().stream().map(item -> item.id()).toList());
        assertEquals(WorkItemUrgency.critical, result.items().getFirst().urgency());
        assertEquals(List.of(WorkItemAction.complete, WorkItemAction.open_context),
            result.items().getFirst().permittedActions());
        assertTrue(result.items().getFirst().etag().startsWith("\""));
    }

    @Test
    void delegatesCompletionWithTheExpectedHash() {
        AssignedTaskWorkItemProvider provider = new AssignedTaskWorkItemProvider(
            taskService, workspaceService, Clock.fixed(AS_OF, ZoneOffset.UTC));

        provider.execute(9, new WorkItemActionCommand(
            WorkItemAction.complete, "a".repeat(64), null, null, null, null));

        verify(taskService).complete(9, "a".repeat(64));
    }

    private static Task task(int id, String title, String dueDate, String timestamp) {
        Task task = new Task();
        task.setId(id);
        task.setDescription(title);
        task.setStatus("todo");
        task.setDueDate(dueDate);
        task.setCreatedAt(timestamp);
        task.setUpdatedAt(timestamp);
        return task;
    }

    private static WorkItemProviderQuery query(Set<WorkItemUrgency> urgencies, int limit) {
        return new WorkItemProviderQuery(7, 42, TODAY, AS_OF, urgencies, limit);
    }
}
