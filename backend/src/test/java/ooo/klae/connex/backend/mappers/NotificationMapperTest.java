package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.DealReminderCandidate;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.RelationshipNudgeCandidate;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.TaskReminderCandidate;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;

class NotificationMapperTest extends AbstractMapperTest {
    @Autowired private NotificationMapper notificationMapper;
    @Autowired private TaskMapper taskMapper;

    @Test
    void stableUpsertPreservesOrResetsLifecycleByPhase() {
        User recipient = newUser();
        Notification notification = reminder(recipient, "warning", "2026-06-23 00:00:00");
        notificationMapper.upsert(notification);

        Notification stored = onlyReminder(recipient);
        assertEquals(1, notificationMapper.markRead(recipient.getId(), stored.getId()));
        assertEquals(1, notificationMapper.dismiss(recipient.getId(), stored.getId()));

        notification.setTitle("Refreshed snapshot");
        notification.setTriggeredAt("2026-06-24 00:00:00");
        notificationMapper.upsert(notification);

        stored = onlyReminder(recipient);
        assertEquals("Refreshed snapshot", stored.getTitle());
        assertNotNull(stored.getReadAt());
        assertNotNull(stored.getDismissedAt());
        assertEquals("2026-06-23 00:00:00", stored.getTriggeredAt());

        notification.setSeverity("critical");
        notification.setTriggeredAt("2026-06-25 00:00:00");
        notificationMapper.upsert(notification);

        stored = onlyReminder(recipient);
        assertEquals("critical", stored.getSeverity());
        assertNull(stored.getReadAt());
        assertNull(stored.getDismissedAt());
        assertNull(stored.getResolvedAt());
        assertEquals("2026-06-25 00:00:00", stored.getTriggeredAt());

        assertEquals(
            1,
            notificationMapper.resolveReminder(
                workspace.getId(),
                recipient.getId(),
                stored.getId(),
                "2026-06-26 00:00:00"
            )
        );
        notification.setTriggeredAt("2026-06-27 00:00:00");
        notificationMapper.upsert(notification);

        stored = onlyReminder(recipient);
        assertNull(stored.getResolvedAt());
        assertEquals("2026-06-27 00:00:00", stored.getTriggeredAt());
    }

    @Test
    void lifecycleMutationCannotCrossRecipientScope() {
        User recipient = newUser();
        User otherRecipient = newUser();
        notificationMapper.upsert(reminder(recipient, "warning", "2026-06-23 00:00:00"));
        Notification stored = onlyReminder(recipient);

        assertEquals(
            0,
            notificationMapper.markRead(otherRecipient.getId(), stored.getId())
        );
        assertNull(
            notificationMapper.findById(otherRecipient.getId(), stored.getId())
        );
    }

    @Test
    void reminderCandidatesProjectTaskAssigneeAndDealTeam() {
        User owner = newUser();
        User collaborator = newUser();
        Company company = newCompany();
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, company);
        deal.setExpectedCloseDate("2026-06-30");
        dealMapper.update(deal);
        dealMapper.updateOwner(workspace.getId(), deal.getId(), owner.getId());
        dealMapper.insertCollaborators(
            workspace.getId(),
            deal.getId(),
            List.of(collaborator.getId())
        );

        Task task = new Task();
        task.setWorkspaceId(workspace.getId());
        task.setDescription("Prepare proposal");
        task.setDueDate("2026-06-24");
        task.setAssignedTo(owner);
        task.setDeal(deal);
        taskMapper.insert(task);

        TaskReminderCandidate taskCandidate = notificationMapper
            .findTaskReminderCandidates(workspace.getId())
            .stream()
            .filter(candidate -> candidate.getTaskId() == task.getId())
            .findFirst()
            .orElseThrow();
        assertEquals(owner.getId(), taskCandidate.getRecipientId());
        assertEquals(deal.getId(), taskCandidate.getDealId());

        Set<Integer> dealRecipients = notificationMapper
            .findDealReminderCandidates(workspace.getId())
            .stream()
            .filter(candidate -> candidate.getDealId() == deal.getId())
            .map(DealReminderCandidate::getRecipientId)
            .collect(Collectors.toSet());
        assertEquals(Set.of(owner.getId(), collaborator.getId()), dealRecipients);
    }

    @Test
    void purgeRemovesResolvedHistoryButKeepsDismissedActiveRows() {
        User recipient = newUser();
        Notification dismissed = reminder(recipient, "warning", "2026-01-01 00:00:00");
        notificationMapper.upsert(dismissed);
        Notification storedDismissed = onlyReminder(recipient);
        notificationMapper.dismiss(recipient.getId(), storedDismissed.getId());

        Notification resolved = reminder(recipient, "warning", "2026-01-01 00:00:00");
        resolved.setSourceId(92);
        resolved.setDedupeKey("task.due:92");
        notificationMapper.upsert(resolved);
        Notification storedResolved = notificationMapper
            .findReminderNotifications(workspace.getId(), recipient.getId())
            .stream()
            .filter(notification -> "task.due:92".equals(notification.getDedupeKey()))
            .findFirst()
            .orElseThrow();
        notificationMapper.resolveReminder(
            workspace.getId(),
            recipient.getId(),
            storedResolved.getId(),
            "2026-01-02 00:00:00"
        );

        assertEquals(
            1,
            notificationMapper.purgeReminderHistory(
                workspace.getId(),
                recipient.getId(),
                "2026-02-01 00:00:00"
            )
        );
        List<Notification> remaining = notificationMapper.findReminderNotifications(
            workspace.getId(),
            recipient.getId()
        );
        assertEquals(1, remaining.size());
        assertNotNull(remaining.getFirst().getDismissedAt());
        assertNull(remaining.getFirst().getResolvedAt());
        assertFalse(remaining.stream().anyMatch(
            notification -> "task.due:92".equals(notification.getDedupeKey())
        ));
        assertTrue(remaining.stream().anyMatch(
            notification -> "task.due:91".equals(notification.getDedupeKey())
        ));
    }

    @Test
    void restoreClearsArchivedAndReadStateAndIgnoresActiveRows() {
        User recipient = newUser();
        notificationMapper.upsert(reminder(recipient, "warning", "2026-06-23 00:00:00"));
        Notification stored = onlyReminder(recipient);

        notificationMapper.markRead(recipient.getId(), stored.getId());
        notificationMapper.dismiss(recipient.getId(), stored.getId());

        assertEquals(1, notificationMapper.restore(recipient.getId(), stored.getId()));

        Notification restored = onlyReminder(recipient);
        assertNull(restored.getReadAt());
        assertNull(restored.getDismissedAt());
        assertNull(restored.getResolvedAt());

        assertEquals(0, notificationMapper.restore(recipient.getId(), stored.getId()));
    }

    @Test
    void inboxSpansEveryWorkspaceTheRecipientBelongsTo() {
        User recipient = newUser();
        Workspace second = new Workspace();
        second.setName("Second WS");
        second.setSlug("second-" + unique());
        workspaceMapper.insert(second);
        workspaceMapper.addMember(second.getId(), recipient.getId(), "member");

        notificationMapper.upsert(reminder(recipient, "warning", "2026-06-23 00:00:00"));
        Notification other = reminder(recipient, "warning", "2026-06-24 00:00:00");
        other.setWorkspaceId(second.getId());
        other.setSourceId(777);
        other.setDedupeKey("task.due:777");
        notificationMapper.upsert(other);

        List<Notification> page = notificationMapper.findPage(
            recipient.getId(), "active", null, null, null, 50, 0);

        Set<Integer> workspaceIds = page.stream()
            .map(Notification::getWorkspaceId)
            .collect(Collectors.toSet());
        assertTrue(workspaceIds.contains(workspace.getId()));
        assertTrue(workspaceIds.contains(second.getId()));
        assertTrue(page.stream().allMatch(n -> n.getWorkspaceName() != null));
    }

    @Test
    void relationshipNudgeCandidatesProjectDealStakeholdersAndSkipClosedDeals() {
        User owner = newUser();
        User collaborator = newUser();
        Company company = newCompany();
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, company);
        dealMapper.updateOwner(workspace.getId(), deal.getId(), owner.getId());
        dealMapper.insertCollaborators(
            workspace.getId(),
            deal.getId(),
            List.of(collaborator.getId())
        );

        Person stakeholder = newPerson(company);
        dealMapper.addPerson(workspace.getId(), deal.getId(), stakeholder.getId(), "champion");

        Set<Integer> recipients = notificationMapper
            .findRelationshipNudgeCandidates(workspace.getId())
            .stream()
            .filter(candidate -> candidate.getDealId() == deal.getId()
                && candidate.getPersonId() == stakeholder.getId())
            .map(RelationshipNudgeCandidate::getRecipientId)
            .collect(Collectors.toSet());
        assertEquals(Set.of(owner.getId(), collaborator.getId()), recipients);

        deal.setClosedAt("2026-06-30 00:00:00");
        deal.setWon(true);
        dealMapper.update(deal);

        assertFalse(
            notificationMapper.findRelationshipNudgeCandidates(workspace.getId())
                .stream()
                .anyMatch(candidate -> candidate.getDealId() == deal.getId())
        );
    }

    private Notification onlyReminder(User recipient) {
        List<Notification> notifications = notificationMapper.findReminderNotifications(
            workspace.getId(),
            recipient.getId()
        );
        assertEquals(1, notifications.size());
        return notifications.getFirst();
    }

    private Notification reminder(User recipient, String severity, String triggeredAt) {
        Notification notification = new Notification();
        notification.setWorkspaceId(workspace.getId());
        notification.setRecipientId(recipient.getId());
        notification.setType("task.due");
        notification.setCategory("task");
        notification.setSeverity(severity);
        notification.setTemplateVersion(1);
        notification.setTitle("Task due");
        notification.setBody("Task body");
        notification.setSourceType("task");
        notification.setSourceId(91);
        notification.setSourceLabel("Send proposal");
        notification.setActionUrl("/activity/tasks?taskId=91");
        notification.setData("{\"taskId\":91}");
        notification.setDedupeKey("task.due:91");
        notification.setTriggeredAt(triggeredAt);
        return notification;
    }
}