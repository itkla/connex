package ooo.klae.connex.backend.services;

import java.sql.Connection;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.BoardPositionUpdate;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.dto.TaskSummaryDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.notifications.NotificationChangePublisher;
import ooo.klae.connex.backend.notifications.NotificationDelivery;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.RequiredArgsConstructor;

/**
 * Business logic for {@code Task} operations.
 * Handles mapping between {@code TaskDto} and {@code Task} bean.
 * Delegates persistence to {@code TaskMapper}, resolves inline @/# references in
 * the task description via {@code ReferenceService}, and dispatches member-mention
 * notifications.
 */

@Service
@RequiredArgsConstructor
public class TaskService {
    private final TaskMapper taskMapper;
    private final DealMapper dealMapper;
    private final AuditService auditService;
    private final WorkspaceService workspaceService;
    private final AuthService authService;
    private final UserCalendarService userCalendarService;
    private final NotificationChangePublisher notificationChanges;
    private final ReferenceService referenceService;
    private final NotificationDelivery notificationDelivery;
    private final NotificationPreferenceService notificationPreferenceService;
    private final RuleTriggerPublisher ruleTriggers;
    private final ObjectMapper objectMapper;

    private static final String STATUS_TODO = "todo";
    private static final String STATUS_IN_PROGRESS = "in_progress";
    private static final String STATUS_DONE = "done";
    private static final Set<String> VALID_STATUSES = Set.of(STATUS_TODO, STATUS_IN_PROGRESS, STATUS_DONE);

    private static final Set<String> AUDIT_FIELDS =
        Set.of("description", "completed", "status", "dueDate");

    private static final String MENTION_TYPE = "task.mention";
    private static final String MENTION_CATEGORY = "task";
    private static final String MENTION_SEVERITY = "info";
    private static final String IN_APP = "in_app";
    private static final int SNIPPET_LENGTH = 140;
    private static final int POSITION_BATCH_SIZE = 500;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public List<Task> getAllTasks() {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        return referenceService.hydrateTasks(workspaceId, taskMapper.getAllTasks(workspaceId));
    }

    public List<Task> getTasksPage(int limit, int offset) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        return referenceService.hydrateTasks(workspaceId, taskMapper.getTasksPage(workspaceId, limit, offset));
    }

    public long countTasks() {
        return taskMapper.countTasks(workspaceService.getCurrentWorkspaceId());
    }

    public TaskSummaryDto getTaskSummary(MemberScope memberScope) {
        return taskMapper.taskSummary(
            workspaceService.getCurrentWorkspaceId(), userCalendarService.today(), memberScope);
    }

    public List<Task> getUpcomingOpenTasks(int limit) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        return referenceService.hydrateTasks(
            workspaceId, taskMapper.getUpcomingOpenTasks(workspaceId, limit));
    }

    public List<Task> getTasksByAssignedToId(int assignedToId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        return referenceService.hydrateTasks(workspaceId, taskMapper.getTasksByAssignedToId(workspaceId, assignedToId));
    }

    public List<Task> getTasksByPersonId(int personId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        return referenceService.hydrateTasks(workspaceId, taskMapper.getTasksByPersonId(workspaceId, personId));
    }

    public List<Task> getTasksByDealId(int dealId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        return referenceService.hydrateTasks(workspaceId, taskMapper.getTasksByDealId(workspaceId, dealId));
    }

    public Task getTaskById(int id) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Task task = taskMapper.getTaskById(workspaceId, id);
        if (task == null) throw new ResourceNotFoundException("Task not found with id: " + id);
        return hydrate(workspaceId, task);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.TASK_CREATE)
    public Task create(Task task) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        User actor = currentActorOrNull();
        task.setWorkspaceId(workspaceId);
        lockAssignee(task, workspaceId);
        validateLinkedRecords(task, workspaceId);
        lockTaskBoard(workspaceId);
        task.setStatus(task.isCompleted() ? STATUS_DONE : STATUS_TODO);
        task.setPosition(taskMapper.nextTaskPosition(workspaceId, task.getStatus()));
        task.setCreatedAt(null);
        taskMapper.insert(task);
        auditService.record("task.create", "task", task.getId(), task.getDescription(),
            "Created task " + task.getDescription(),
            auditService.diff(null, task, AUDIT_FIELDS));
        notificationChanges.publish(workspaceId, "task", task.getId());
        ruleTriggers.publish(workspaceId, "task", task.getId(), "task.created");
        List<Integer> mentioned =
            referenceService.syncReferences(workspaceId, ReferenceService.SOURCE_TASK, task.getId(), task.getDescription());
        if (actor != null) {
            notifyMentions(workspaceId, task, mentioned, actor);
        }
        return hydrate(workspaceId, task);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.TASK_UPDATE)
    public Task update(int id, Task task) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        lockAssignee(task, workspaceId);
        List<Task> lockedTasks = lockTaskBoardTasks(workspaceId, id);
        Task before = requireLockedTask(lockedTasks, id);
        if (before.isCompleted() != task.isCompleted()) {
            User currentUser = authService.getCurrentUser();
            if (before.getAssignedTo() == null || before.getAssignedTo().getId() != currentUser.getId()) {
                throw new ForbiddenException("Only the task assignee may change this task's completion");
            }
        }
        User actor = currentActorOrNull();
        task.setId(id);
        task.setWorkspaceId(workspaceId);
        validateLinkedRecords(task, workspaceId);
        String beforeStatus = before.getStatus() != null ? before.getStatus() : STATUS_TODO;
        String resolved = task.isCompleted() ? STATUS_DONE
            : (STATUS_DONE.equals(beforeStatus) ? STATUS_TODO : beforeStatus);
        task.setCompleted(STATUS_DONE.equals(resolved));
        task.setStatus(resolved);
        task.setPosition(resolved.equals(beforeStatus)
            ? before.getPosition()
            : taskMapper.nextTaskPosition(workspaceId, resolved));
        if (taskMapper.update(task) != 1) {
            throw new ResourceNotFoundException("Task not found with id: " + id);
        }
        if (!resolved.equals(beforeStatus)) {
            compactStatusColumn(workspaceId, beforeStatus, lockedTasks, id);
        }
        auditService.record("task.update", "task", id, task.getDescription(),
            "Updated task " + task.getDescription(),
            auditService.diff(before, task, AUDIT_FIELDS));
        notificationChanges.publish(workspaceId, "task", id);
        if (!before.isCompleted() && task.isCompleted()) {
            ruleTriggers.publish(workspaceId, "task", id, "task.completed");
        }
        List<Integer> mentioned =
            referenceService.syncReferences(workspaceId, ReferenceService.SOURCE_TASK, id, task.getDescription());
        if (actor != null) {
            notifyMentions(workspaceId, task, mentioned, actor);
        }
        return hydrate(workspaceId, task);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.TASK_DELETE)
    public void delete(int id) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        List<Task> lockedTasks = lockTaskBoardTasks(workspaceId, id);
        Task before = requireLockedTask(lockedTasks, id);
        if (taskMapper.delete(workspaceId, id) != 1) {
            throw new ResourceNotFoundException("Task not found with id: " + id);
        }
        compactStatusColumn(
            workspaceId,
            before.getStatus() != null ? before.getStatus() : STATUS_TODO,
            lockedTasks,
            id
        );
        referenceService.deleteReferences(workspaceId, ReferenceService.SOURCE_TASK, id);
        auditService.record("task.delete", "task", id, before.getDescription(),
            "Deleted task " + before.getDescription(),
            auditService.diff(before, null, AUDIT_FIELDS));
        notificationChanges.publish(workspaceId, "task", id);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.TASK_UPDATE)
    public Task complete(int id) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        User currentUser = authService.getCurrentUser();
        List<Task> lockedTasks = lockTaskBoardTasks(workspaceId, id);
        Task task = requireLockedTask(lockedTasks, id);
        if (task.getAssignedTo() == null || task.getAssignedTo().getId() != currentUser.getId()) {
            throw new ForbiddenException("Only the task assignee may complete this task");
        }
        if (task.isCompleted()) {
            return hydrate(workspaceId, task);
        }
        int donePosition = taskMapper.nextTaskPosition(workspaceId, STATUS_DONE);
        if (taskMapper.complete(workspaceId, id, currentUser.getId(), donePosition) == 0) {
            throw new ForbiddenException("Only the task assignee may complete this task");
        }
        compactStatusColumn(
            workspaceId,
            task.getStatus() != null ? task.getStatus() : STATUS_TODO,
            lockedTasks,
            id
        );
        Task completed = taskMapper.getTaskById(workspaceId, id);
        auditService.record("task.complete", "task", id, task.getDescription(),
            "Completed task " + task.getDescription(),
            auditService.singleChange("completed", task.isCompleted(), true));
        notificationChanges.publish(workspaceId, "task", id);
        ruleTriggers.publish(workspaceId, "task", id, "task.completed");
        return hydrate(workspaceId, completed);
    }

    /**
     * Moves a task to a target status column and ordinal position on the Kanban board, renumbering
     * the affected column(s) so positions stay contiguous and 0-based. Dragging a task into the
     * {@code done} column completes it, so it is gated by the same assignee-only rule as
     * {@link #complete(int)}; the {@code completed} flag is kept in lockstep with {@code status}.
     * @param id the task to move
     * @param status the target status: {@code todo}, {@code in_progress} or {@code done}
     * @param position the desired 0-based index within the target column (clamped to the column size)
     * @return the moved task
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.TASK_UPDATE)
    public Task move(int id, String status, int position) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        if (status == null || !VALID_STATUSES.contains(status)) {
            throw new BadRequestException("Invalid task status: " + status);
        }
        List<Task> lockedTasks = lockTaskBoardTasks(workspaceId, id);
        Task before = requireLockedTask(lockedTasks, id);
        String oldStatus = before.getStatus() != null ? before.getStatus() : STATUS_TODO;
        boolean toDone = STATUS_DONE.equals(status);
        boolean fromDone = STATUS_DONE.equals(oldStatus);
        if (toDone != fromDone) {
            User currentUser = authService.getCurrentUser();
            if (before.getAssignedTo() == null || before.getAssignedTo().getId() != currentUser.getId()) {
                throw new ForbiddenException("Only the task assignee may change this task's completion");
            }
        }
        boolean statusChanged = !status.equals(oldStatus);

        List<Integer> target = taskIdsInStatus(lockedTasks, status);
        target.removeIf(existing -> existing == id);
        int index = Math.max(0, Math.min(position, target.size()));
        target.add(index, id);

        if (statusChanged) {
            List<Integer> source = taskIdsInStatus(lockedTasks, oldStatus);
            source.removeIf(existing -> existing == id);
            setPositionBatches(
                workspaceId,
                oldStatus,
                BoardPositionBatches.fromOrderedIds(source, POSITION_BATCH_SIZE)
            );
        }

        if (taskMapper.moveTask(workspaceId, id, status, toDone, index) != 1) {
            throw new ResourceNotFoundException("Task not found with id: " + id);
        }
        setPositionBatches(
            workspaceId,
            status,
            BoardPositionBatches.fromOrderedIdsExcluding(target, id, POSITION_BATCH_SIZE)
        );

        Task moved = taskMapper.getTaskById(workspaceId, id);
        auditService.record("task.update", "task", id, before.getDescription(),
            statusChanged ? "Moved task " + before.getDescription() + " to " + status
                          : "Reordered task " + before.getDescription(),
            auditService.diff(before, moved, AUDIT_FIELDS));
        notificationChanges.publish(workspaceId, "task", id);
        if (toDone && !fromDone) {
            ruleTriggers.publish(workspaceId, "task", id, "task.completed");
        }
        return hydrate(workspaceId, moved);
    }

    private void setPositionBatches(
            int workspaceId, String status, List<List<BoardPositionUpdate>> batches) {
        for (List<BoardPositionUpdate> positions : batches) {
            taskMapper.setPositions(workspaceId, status, positions);
        }
    }

    private List<Task> lockTaskBoardTasks(int workspaceId, int requestedTaskId) {
        lockTaskBoard(workspaceId);
        Set<Integer> taskIds = new TreeSet<>(taskMapper.listWorkspaceTaskIds(workspaceId));
        taskIds.add(requestedTaskId);
        List<Task> lockedTasks = new ArrayList<>(taskIds.size());
        for (int taskId : taskIds) {
            Task locked = taskMapper.getTaskByIdForUpdate(workspaceId, taskId);
            if (locked != null) {
                lockedTasks.add(locked);
            }
        }
        return lockedTasks;
    }

    private void lockTaskBoard(int workspaceId) {
        Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && (isolation == null || isolation != Connection.TRANSACTION_READ_COMMITTED)) {
            throw new IllegalStateException("Task board mutations require READ_COMMITTED isolation");
        }
        taskMapper.lockTaskBoard(workspaceId);
    }

    private Task requireLockedTask(List<Task> lockedTasks, int taskId) {
        return lockedTasks.stream()
            .filter(task -> task.getId() == taskId)
            .findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("Task not found with id: " + taskId));
    }

    private void compactStatusColumn(
            int workspaceId, String status, List<Task> lockedTasks, int excludedTaskId) {
        List<Integer> remaining = taskIdsInStatus(lockedTasks, status);
        remaining.removeIf(existing -> existing == excludedTaskId);
        setPositionBatches(
            workspaceId,
            status,
            BoardPositionBatches.fromOrderedIds(remaining, POSITION_BATCH_SIZE)
        );
    }

    private List<Integer> taskIdsInStatus(List<Task> tasks, String status) {
        return new ArrayList<>(
            tasks.stream()
                .filter(task -> status.equals(task.getStatus() != null ? task.getStatus() : STATUS_TODO))
                .sorted(Comparator.comparingInt(Task::getPosition).thenComparingInt(Task::getId))
                .map(Task::getId)
                .toList()
        );
    }

    /**
     * Changes only a task's due date, leaving description, assignee, status, position and links
     * untouched. Unlike {@link #update(int, Task)} this cannot clobber other fields from a stale
     * client payload — it writes a single column after confirming the task belongs to the caller's
     * workspace.
     * @param id the task to reschedule
     * @param dueDate the target due date as a {@code YYYY-MM-DD} calendar day
     * @return the rescheduled task
     */
    @Transactional
    @RequirePermission(Permission.TASK_UPDATE)
    public Task reschedule(int id, String dueDate) {
        try {
            LocalDate.parse(dueDate);
        } catch (DateTimeParseException e) {
            throw new BadRequestException("Invalid task due date: " + dueDate);
        }
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Task before = taskMapper.getTaskByIdForUpdate(workspaceId, id);
        if (before == null) throw new ResourceNotFoundException("Task not found with id: " + id);
        if (taskMapper.updateDueDate(workspaceId, id, dueDate) != 1) {
            throw new ResourceNotFoundException("Task not found with id: " + id);
        }
        Task after = taskMapper.getTaskById(workspaceId, id);
        auditService.record("task.update", "task", id, after.getDescription(),
            "Rescheduled task " + after.getDescription(),
            auditService.singleChange("dueDate", before.getDueDate(), dueDate));
        notificationChanges.publish(workspaceId, "task", id);
        return hydrate(workspaceId, after);
    }

    private void lockAssignee(Task task, int workspaceId) {
        workspaceService.lockAndRequireMember(workspaceId, requireAssigneeId(task));
    }

    private int requireAssigneeId(Task task) {
        if (task.getAssignedTo() == null || task.getAssignedTo().getId() == 0) {
            throw new BadRequestException("Task assignee is required");
        }
        return task.getAssignedTo().getId();
    }

    private void validateLinkedRecords(Task task, int workspaceId) {
        if (task.getDeal() != null && !dealMapper.exists(workspaceId, task.getDeal().getId())) {
            throw new BadRequestException("Task deal must belong to the current workspace");
        }
    }

    private Task hydrate(int workspaceId, Task task) {
        return referenceService.hydrateTasks(workspaceId, List.of(task)).get(0);
    }

    private User currentActorOrNull() {
        try {
            return authService.getCurrentUser();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void notifyMentions(int workspaceId, Task task, List<Integer> recipientIds, User actor) {
        if (recipientIds.isEmpty()) {
            return;
        }
        String snippet = snippet(task.getDescription());
        String triggeredAt = LocalDateTime.now(ZoneOffset.UTC).format(TS);
        String taskAnchor = "?task=" + task.getId();
        String contextType = null;
        Integer contextId = null;
        String actionUrl = "/activity/tasks" + taskAnchor;
        if (task.getDeal() != null && task.getDeal().getId() > 0) {
            contextType = "deal";
            contextId = task.getDeal().getId();
            actionUrl = "/records/deals/" + contextId + taskAnchor;
        } else if (task.getPerson() != null && task.getPerson().getId() > 0) {
            contextType = "person";
            contextId = task.getPerson().getId();
            actionUrl = "/records/contacts/" + contextId + taskAnchor;
        }
        for (int recipientId : recipientIds) {
            if (recipientId == actor.getId()) {
                continue;
            }
            if (!notificationPreferenceService.isEnabled(recipientId, MENTION_TYPE, IN_APP)) {
                continue;
            }
            try {
                Notification notification = new Notification();
                notification.setWorkspaceId(workspaceId);
                notification.setRecipientId(recipientId);
                notification.setType(MENTION_TYPE);
                notification.setCategory(MENTION_CATEGORY);
                notification.setSeverity(MENTION_SEVERITY);
                notification.setTemplateVersion(1);
                notification.setTitle("New mention");
                notification.setBody(actor.getDisplayName() + " mentioned you in a task");
                notification.setActorId(actor.getId());
                notification.setActorLabel(actor.getDisplayName());
                notification.setSourceType("task");
                notification.setSourceId(task.getId());
                notification.setSourceLabel(snippet);
                notification.setContextType(contextType);
                notification.setContextId(contextId);
                notification.setActionUrl(actionUrl);
                notification.setDedupeKey(MENTION_TYPE + ":" + task.getId() + ":" + recipientId);
                notification.setTriggeredAt(triggeredAt);
                notification.setData(json(Map.of("taskId", task.getId())));
                notificationDelivery.deliver(notification);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static String snippet(String content) {
        String plain = ReferenceService.toPlainText(content).strip();
        return plain.length() > SNIPPET_LENGTH ? plain.substring(0, SNIPPET_LENGTH) : plain;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize notification data", exception);
        }
    }
}
