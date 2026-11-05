package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.NotificationPreference;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.PreferenceMapper;

@Transactional(isolation = Isolation.READ_COMMITTED)
class TaskAssignmentNotificationServiceTest extends AbstractServiceTest {
    @Autowired TaskService taskService;
    @Autowired PreferenceMapper preferenceMapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void createNotifiesOnlyTheAssigneeWithTheTaskDeepLink() {
        User assignee = newUser();
        User bystander = newUser();

        Task created = taskService.create(draft(assignee));

        Notification notification = assigned(created, assignee);
        assertNotNull(notification);
        assertEquals("task.assigned", notification.getType());
        assertEquals("task", notification.getCategory());
        assertEquals("info", notification.getSeverity());
        assertEquals(workspace.getId(), notification.getWorkspaceId());
        assertEquals(currentUser.getId(), notification.getActorId());
        assertEquals(assignee.getId(), notification.getRecipientId());
        assertEquals("task", notification.getSourceType());
        assertEquals(created.getId(), notification.getSourceId());
        assertEquals(created.getDescription(), notification.getSourceLabel());
        assertEquals("/activity/tasks?task=" + created.getId(), notification.getActionUrl());
        assertEquals(notification.getId(), notificationMapper.findById(assignee.getId(), notification.getId()).getId());
        assertNull(assigned(created, currentUser));
        assertNull(assigned(created, bystander));
    }

    @Test
    void reassignmentResolvesTheOldNoticeAndReturningRevivesTheSameRowUnread() {
        User first = newUser();
        User second = newUser();
        Task task = taskService.create(draft(first));
        Notification original = assigned(task, first);
        notificationMapper.markRead(first.getId(), original.getId());
        notificationMapper.dismiss(first.getId(), original.getId());

        task.setAssignedTo(second);
        taskService.update(task.getId(), task);

        assertNotNull(assigned(task, first).getResolvedAt());
        assertNotNull(assigned(task, second));
        task.setAssignedTo(first);
        taskService.update(task.getId(), task);

        Notification returned = assigned(task, first);
        assertEquals(original.getId(), returned.getId());
        assertNull(returned.getResolvedAt());
        assertNull(returned.getReadAt());
        assertNull(returned.getDismissedAt());
        assertNotNull(assigned(task, second).getResolvedAt());
        assertEquals(2, assignmentCount(task));
    }

    @Test
    void repeatedUpdatesDoNotDuplicateOrReviveAnUnchangedAssignment() {
        User assignee = newUser();
        Task task = taskService.create(draft(assignee));
        Notification original = assigned(task, assignee);
        notificationMapper.markRead(assignee.getId(), original.getId());
        notificationMapper.dismiss(assignee.getId(), original.getId());

        for (int i = 0; i < 3; i++) {
            task.setDescription("Edited " + i);
            taskService.update(task.getId(), task);
        }

        assertEquals(1, assignmentCount(task));
        Notification unchanged = assigned(task, assignee);
        assertEquals(original.getId(), unchanged.getId());
        assertNotNull(unchanged.getReadAt());
        assertNotNull(unchanged.getDismissedAt());
    }

    @Test
    void createAndReassignToSelfNeverNotifyTheActor() {
        Task task = taskService.create(draft(currentUser));
        assertEquals(0, assignmentCount(task));
        User teammate = newUser();
        task.setAssignedTo(teammate);
        taskService.update(task.getId(), task);
        task.setAssignedTo(currentUser);
        taskService.update(task.getId(), task);

        assertNull(assigned(task, currentUser));
        assertNotNull(assigned(task, teammate).getResolvedAt());
        assertEquals(1, assignmentCount(task));
    }

    @Test
    void exactAndWildcardPreferencesApplyOnCreationAndReassignment() {
        User assignee = newUser();
        preference(assignee, "*", false);
        Task suppressed = taskService.create(draft(assignee));
        assertNull(assigned(suppressed, assignee));
        preference(assignee, "task.assigned", true);
        Task allowed = taskService.create(draft(assignee));
        assertNotNull(assigned(allowed, assignee));
        preference(assignee, "task.assigned", false);
        Task reassigned = taskService.create(draft(currentUser));
        reassigned.setAssignedTo(assignee);
        taskService.update(reassigned.getId(), reassigned);
        assertNull(assigned(reassigned, assignee));
    }

    @Test
    void assignmentAndMentionKeepIndependentPreferencesAndDedupeKeys() {
        User assignee = newUser();
        preference(assignee, "task.assigned", false);
        Task draft = draft(assignee);
        draft.setDescription("Please call [Teammate](user:" + assignee.getId() + ")");

        Task created = taskService.create(draft);
        taskService.update(created.getId(), created);

        assertNull(assigned(created, assignee));
        assertNotNull(notificationMapper.findByDedupe(workspace.getId(), assignee.getId(),
            "task.mention:" + created.getId() + ":" + assignee.getId()));
        assertEquals(1L, jdbc.queryForObject(
            "SELECT COUNT(*) FROM notification WHERE workspace_id = ? AND recipient_id = ? AND source_id = ?",
            Long.class, workspace.getId(), assignee.getId(), created.getId()));
    }

    @Test
    void linkedAssignmentsOpenTheExactTaskOnTheirRecord() {
        User assignee = newUser();
        var person = newPerson(newCompany());
        Task personDraft = draft(assignee);
        personDraft.setPerson(person);
        Task personTask = taskService.create(personDraft);
        assertEquals("/records/contacts/" + person.getId() + "?task=" + personTask.getId(),
            assigned(personTask, assignee).getActionUrl());
        var pipeline = newPipeline();
        var deal = newDeal(pipeline, newStage(pipeline, 0), newCompany());
        Task dealDraft = draft(assignee);
        dealDraft.setDeal(deal);
        Task dealTask = taskService.create(dealDraft);
        assertEquals("/records/deals/" + deal.getId() + "?task=" + dealTask.getId(),
            assigned(dealTask, assignee).getActionUrl());
    }

    @Test
    void pendingAndForeignMembersCannotReceiveAnAssignmentInThisWorkspace() {
        User pending = newPendingMember();
        assertThrows(ForbiddenException.class, () -> taskService.create(draft(pending)));
        User foreign = newUser();
        Workspace other = foreignWorkspace();
        workspaceMapper.addMember(other.getId(), foreign.getId(), "member");
        workspaceMapper.removeMember(workspace.getId(), foreign.getId());
        assertThrows(ForbiddenException.class, () -> taskService.create(draft(foreign)));

        Task existing = newTask(currentUser, null, null);
        existing.setAssignedTo(foreign);
        assertThrows(ForbiddenException.class, () -> taskService.update(existing.getId(), existing));
        assertEquals(currentUser.getId(), taskMapper.getTaskById(workspace.getId(), existing.getId()).getAssignedTo().getId());
        assertEquals(0L, jdbc.queryForObject(
            "SELECT COUNT(*) FROM notification WHERE workspace_id = ? AND recipient_id IN (?, ?)",
            Long.class, workspace.getId(), pending.getId(), foreign.getId()));
    }

    @Test
    void foreignTaskCannotBeReassignedOrHaveItsNoticeResolvedFromThisWorkspace() {
        User assignee = newUser();
        Workspace other = foreignWorkspace();
        workspaceMapper.addMember(other.getId(), assignee.getId(), "member");
        Task foreign = draft(assignee);
        foreign.setWorkspaceId(other.getId());
        foreign.setStatus("todo");
        taskMapper.insert(foreign);
        Notification foreignNotice = newNotification(other.getId(), assignee.getId());
        jdbc.update("UPDATE notification SET source_type = 'task', source_id = ? WHERE id = ?",
            foreign.getId(), foreignNotice.getId());

        assertThrows(ResourceNotFoundException.class,
            () -> taskService.update(foreign.getId(), draft(currentUser)));
        assertEquals(0, notificationMapper.resolveTaskAssignment(workspace.getId(), assignee.getId(),
            foreign.getId(), "2026-11-05 12:00:00"));
        assertNull(notificationMapper.findByDedupe(other.getId(), assignee.getId(), foreignNotice.getDedupeKey()).getResolvedAt());
        assertEquals(0, assignmentCount(foreign));
    }

    private Task draft(User assignee) {
        Task task = new Task();
        task.setDescription("Call the customer");
        task.setAssignedTo(assignee);
        return task;
    }

    private Notification assigned(Task task, User recipient) {
        return notificationMapper.findByDedupe(workspace.getId(), recipient.getId(),
            "task.assigned:" + task.getId() + ":" + recipient.getId());
    }

    private long assignmentCount(Task task) {
        Long count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM notification WHERE workspace_id = ? AND source_type = 'task' "
                + "AND source_id = ? AND type = 'task.assigned'", Long.class, workspace.getId(), task.getId());
        assertNotNull(count);
        return count;
    }

    private void preference(User user, String type, boolean enabled) {
        NotificationPreference preference = new NotificationPreference();
        preference.setUserId(user.getId());
        preference.setType(type);
        preference.setChannel("in_app");
        preference.setEnabled(enabled);
        preferenceMapper.upsert(preference);
    }

    private Workspace foreignWorkspace() {
        Workspace other = new Workspace();
        other.setName("Other workspace " + unique());
        other.setSlug("other_" + unique());
        workspaceMapper.insert(other);
        return other;
    }
}
