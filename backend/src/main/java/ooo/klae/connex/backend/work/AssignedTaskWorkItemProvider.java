package ooo.klae.connex.backend.work;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.dto.TaskWorkItem;
import ooo.klae.connex.backend.dto.TaskWorkPage;
import ooo.klae.connex.backend.dto.WorkItemAction;
import ooo.klae.connex.backend.dto.WorkItemActionOutcome;
import ooo.klae.connex.backend.dto.WorkItemActionResponse;
import ooo.klae.connex.backend.dto.WorkItemContextDto;
import ooo.klae.connex.backend.dto.WorkItemDto;
import ooo.klae.connex.backend.dto.WorkItemEvidenceCode;
import ooo.klae.connex.backend.dto.WorkItemEvidenceDto;
import ooo.klae.connex.backend.dto.WorkItemReasonCode;
import ooo.klae.connex.backend.dto.WorkItemReasonDto;
import ooo.klae.connex.backend.dto.WorkItemSource;
import ooo.klae.connex.backend.dto.WorkItemUrgency;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.services.TaskService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;

/** Projects assigned open tasks and delegates completion to {@link TaskService}. */
@Component
@RequiredArgsConstructor
public class AssignedTaskWorkItemProvider implements WorkItemProvider {
    private final TaskService taskService;
    private final WorkspaceService workspaceService;
    private final Clock clock;

    @Override
    public WorkItemSource source() {
        return WorkItemSource.task;
    }

    @Override
    public WorkItemProviderResult load(WorkItemProviderQuery query) {
        TaskWorkPage page = taskService.findOpenAssignedWork(
            query.asOf(), query.actorToday(), query.urgencies(), query.candidateLimit());
        boolean mayComplete = workspaceService.getCurrentPermissions().contains(Permission.TASK_UPDATE);
        List<WorkItemDto> items = page.items().stream()
            .map(item -> toDto(item, query, mayComplete))
            .sorted(WorkItemOrdering.comparator())
            .toList();
        return new WorkItemProviderResult(
            items, page.matchingTotal(), page.overallTotal(), page.asOf());
    }

    @Override
    public WorkItemActionResponse execute(int sourceId, WorkItemActionCommand command) {
        if (command.action() != WorkItemAction.complete) {
            throw new BadRequestException("Unsupported task work action");
        }
        taskService.complete(sourceId, command.expectedStateHash());
        return new WorkItemActionResponse(
            source(), sourceId, WorkItemActionOutcome.applied, true, null, clock.instant());
    }

    private WorkItemDto toDto(
            TaskWorkItem item,
            WorkItemProviderQuery query,
            boolean mayComplete) {
        Task task = item.task();
        if (task.getDescription() == null || task.getDescription().isBlank()) {
            throw new InvalidWorkItemSourceRowsException();
        }
        LocalDate dueDate = parseDate(task.getDueDate());
        WorkItemUrgency urgency = urgency(dueDate, query.actorToday());
        WorkItemReasonCode reasonCode = reasonCode(dueDate, query.actorToday());
        Integer days = dueDate == null ? null : Math.toIntExact(
            Math.abs(ChronoUnit.DAYS.between(query.actorToday(), dueDate)));
        List<WorkItemAction> actions = new ArrayList<>();
        if (mayComplete) {
            actions.add(WorkItemAction.complete);
        }
        actions.add(WorkItemAction.open_context);
        WorkItemEvidenceCode evidenceCode = dueDate == null
            ? WorkItemEvidenceCode.task_open
            : WorkItemEvidenceCode.task_due;
        return new WorkItemDto(
            "task:" + task.getId(),
            source(),
            task.getId(),
            task.getDescription(),
            new WorkItemReasonDto(reasonCode, dueDate, days, null),
            dueDate,
            urgency,
            List.of(new WorkItemEvidenceDto(
                evidenceCode,
                source(),
                task.getId(),
                WorkItemProjectionSupport.instant(task.getCreatedAt()),
                dueDate,
                task.getDescription())),
            WorkItemProjectionSupport.instant(
                task.getUpdatedAt() == null ? task.getCreatedAt() : task.getUpdatedAt()),
            query.asOf(),
            item.currentVersion(),
            WorkItemProjectionSupport.etag(item.currentVersion()),
            new WorkItemContextDto(
                "task", task.getId(), task.getDescription(), "/activity/tasks?task=" + task.getId()),
            List.copyOf(actions));
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException exception) {
            throw new InvalidWorkItemSourceRowsException(exception);
        }
    }

    private static WorkItemUrgency urgency(LocalDate dueDate, LocalDate today) {
        if (dueDate == null || dueDate.isAfter(today.plusDays(7))) {
            return WorkItemUrgency.low;
        }
        if (dueDate.isBefore(today)) {
            return WorkItemUrgency.critical;
        }
        if (dueDate.isEqual(today)) {
            return WorkItemUrgency.high;
        }
        return WorkItemUrgency.normal;
    }

    private static WorkItemReasonCode reasonCode(LocalDate dueDate, LocalDate today) {
        if (dueDate == null || dueDate.isAfter(today.plusDays(7))) {
            return WorkItemReasonCode.task_open;
        }
        if (dueDate.isBefore(today)) {
            return WorkItemReasonCode.task_overdue;
        }
        if (dueDate.isEqual(today)) {
            return WorkItemReasonCode.task_due_today;
        }
        return WorkItemReasonCode.task_due_soon;
    }
}
