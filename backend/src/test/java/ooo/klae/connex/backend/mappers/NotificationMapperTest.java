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
    void upsertUpdateCountDistinguishesChangedRowsFromStableRedelivery() {
        User recipient = newUser();
        notificationMapper.upsert(reminder(recipient, "warning", "2026-06-23 00:00:00"));

        int stableRows = notificationMapper.upsert(
            reminder(recipient, "warning", "2026-06-23 00:00:00"));
        int changedRows = notificationMapper.upsert(
            reminder(recipient, "critical", "2026-06-24 00:00:00"));

        assertTrue(stableRows <= 1);
        assertTrue(changedRows > 1);
    }

    @Test
    void upsertClearsAnActorThatNoLongerExists() {
        User recipient = newUser();
        User deletedActor = newUser();
        Notification notification = reminder(recipient, "warning", "2026-06-23 00:00:00");
        notification.setActorId(deletedActor.getId());
        userMapper.delete(deletedActor.getId());

        notificationMapper.upsert(notification);

        assertNull(onlyReminder(recipient).getActorId());
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
        task.setStatus("todo");
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
        User unaffectedRecipient = newUser();
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
        Notification newerResolved = reminder(
            unaffectedRecipient, "warning", "2026-03-01 00:00:00", 93);
        notificationMapper.upsert(newerResolved);
        notificationMapper.resolveReminder(
            workspace.getId(),
            unaffectedRecipient.getId(),
            onlyReminder(unaffectedRecipient).getId(),
            "2026-03-02 00:00:00"
        );

        assertEquals(
            List.of(recipient.getId()),
            notificationMapper.findPurgeRecipientIds(
                workspace.getId(), "2026-02-01 00:00:00")
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
    void actorCleanupProjectsExactRecipientsAndReportsUpdatedRows() {
        User actor = newUser();
        User firstRecipient = newUser();
        User secondRecipient = newUser();
        Notification first = reminder(firstRecipient, "warning", "2026-06-23 00:00:00", 101);
        first.setActorId(actor.getId());
        Notification second = reminder(secondRecipient, "warning", "2026-06-23 00:00:00", 102);
        second.setActorId(actor.getId());
        notificationMapper.upsert(first);
        notificationMapper.upsert(second);

        assertEquals(
            List.of(firstRecipient.getId(), secondRecipient.getId()).stream().sorted().toList(),
            notificationMapper.findRecipientIdsByActor(actor.getId())
        );
        assertEquals(2, notificationMapper.clearActorAnywhere(actor.getId()));
        assertTrue(notificationMapper.findRecipientIdsByActor(actor.getId()).isEmpty());
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
    void markAllReadLeavesPendingWorkspaceContentUnreadUntilMembershipActivates() {
        User recipient = newUser();
        Workspace pendingWorkspace = new Workspace();
        pendingWorkspace.setName("Pending WS");
        pendingWorkspace.setSlug("pending-" + unique());
        workspaceMapper.insert(pendingWorkspace);
        workspaceMapper.addPendingMember(pendingWorkspace.getId(), recipient.getId(), "member");
        Notification pending = reminder(recipient, "warning", "2026-06-24 00:00:00");
        pending.setWorkspaceId(pendingWorkspace.getId());
        pending.setDedupeKey("task.due:pending:" + unique());
        notificationMapper.upsert(pending);

        long cutoffId = notificationMapper.getInboxCutoffId(recipient.getId());
        assertEquals(0, notificationMapper.markAllRead(
            recipient.getId(), cutoffId, "2026-06-25 00:00:00"));

        workspaceMapper.activateMember(pendingWorkspace.getId(), recipient.getId());
        List<Notification> unread = notificationMapper.findPage(
            recipient.getId(), "unread", null, null, null, 50, 0);
        assertTrue(unread.stream().anyMatch(notification -> notification.getId() == pending.getId()));
    }

    @Test
    void markAllReadReturnsAndUpdatesOnlyRowsEligibleAtItsCutoff() {
        User recipient = newUser();
        Notification eligible = reminder(recipient, "warning", "2026-06-20 00:00:00", 101);
        Notification dismissed = reminder(recipient, "warning", "2026-06-21 00:00:00", 102);
        Notification resolved = reminder(recipient, "warning", "2026-06-22 00:00:00", 103);
        Notification snoozed = reminder(recipient, "warning", "2026-06-23 00:00:00", 104);
        Notification alreadyRead = reminder(recipient, "warning", "2026-06-24 00:00:00", 105);
        Notification boundarySnoozed = reminder(recipient, "warning", "2026-06-24 01:00:00", 107);
        notificationMapper.upsert(eligible);
        notificationMapper.upsert(dismissed);
        notificationMapper.upsert(resolved);
        notificationMapper.upsert(snoozed);
        notificationMapper.upsert(alreadyRead);
        notificationMapper.upsert(boundarySnoozed);
        notificationMapper.dismiss(recipient.getId(), dismissed.getId());
        notificationMapper.resolveReminder(
            workspace.getId(), recipient.getId(), resolved.getId(), "2026-06-25 00:00:00");
        notificationMapper.snooze(recipient.getId(), snoozed.getId(), "2999-01-01 00:00:00");
        notificationMapper.markRead(recipient.getId(), alreadyRead.getId());

        String readAt = notificationMapper.getDatabaseUtcTimestamp();
        notificationMapper.snooze(recipient.getId(), boundarySnoozed.getId(), readAt);
        long cutoffId = notificationMapper.getInboxCutoffId(recipient.getId());
        Notification afterCutoff = reminder(recipient, "warning", "2026-06-26 00:00:00", 106);
        notificationMapper.upsert(afterCutoff);
        assertEquals(2, notificationMapper.markAllRead(recipient.getId(), cutoffId, readAt));
        assertNotNull(notificationMapper.findById(recipient.getId(), eligible.getId()).getReadAt());
        assertNull(notificationMapper.findById(recipient.getId(), dismissed.getId()).getReadAt());
        assertNull(notificationMapper.findById(recipient.getId(), resolved.getId()).getReadAt());
        assertNull(notificationMapper.findById(recipient.getId(), snoozed.getId()).getReadAt());
        assertNotNull(notificationMapper.findById(recipient.getId(), alreadyRead.getId()).getReadAt());
        assertNotNull(notificationMapper.findById(recipient.getId(), boundarySnoozed.getId()).getReadAt());
        assertNull(notificationMapper.findById(recipient.getId(), afterCutoff.getId()).getReadAt());
        assertEquals(1, notificationMapper.getUnreadCounts(recipient.getId()).getUnread());
        assertEquals(
            notificationMapper.getStateVersion(recipient.getId()),
            notificationMapper.getUnreadCounts(recipient.getId()).getStateVersion());
    }

    @Test
    void stateVersionBumpIsExplicitAndRecipientScoped() {
        User recipient = newUser();
        User other = newUser();

        notificationMapper.bumpStateVersions(List.of(other.getId(), recipient.getId()));
        notificationMapper.bumpStateVersions(List.of(recipient.getId()));

        assertEquals(2, notificationMapper.getStateVersion(recipient.getId()));
        assertEquals(1, notificationMapper.getStateVersion(other.getId()));
    }

    @Test
    void relationshipNudgeCandidatesProjectDealStakeholdersAndSkipClosedDeals() {
        User owner = newUser();
        User collaborator = newUser();
        Company company = newCompany();
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, company);
        deal.setValue(50000.0);
        dealMapper.update(deal);
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

        RelationshipNudgeCandidate projected = notificationMapper
            .findRelationshipNudgeCandidates(workspace.getId())
            .stream()
            .filter(candidate -> candidate.getDealId() == deal.getId()
                && candidate.getPersonId() == stakeholder.getId())
            .findFirst()
            .orElseThrow();
        assertEquals(50000.0, projected.getDealValue());
        assertEquals("champion", projected.getPersonRole());
        assertEquals(Integer.valueOf(0), projected.getStagePosition());
        assertEquals(Integer.valueOf(0), projected.getPipelineMaxPosition());

        deal.setClosedAt("2026-06-30 00:00:00");
        deal.setWon(true);
        dealMapper.update(deal);

        assertFalse(
            notificationMapper.findRelationshipNudgeCandidates(workspace.getId())
                .stream()
                .anyMatch(candidate -> candidate.getDealId() == deal.getId())
        );
    }

    /**
     * Evaluation opt-outs (issue #358) gate the engine candidate queries: a risk-excluded person
     * stops being a relationship-nudge candidate, and a risk-excluded deal stops producing
     * deal-risk recipients.
     */
    @Test
    void evaluationOptOutsFilterNudgeCandidatesAndOpenDealRecipients() {
        User owner = newUser();
        Company company = newCompany();
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, company);
        dealMapper.updateOwner(workspace.getId(), deal.getId(), owner.getId());
        Person stakeholder = newPerson(company);
        dealMapper.addPerson(workspace.getId(), deal.getId(), stakeholder.getId(), "champion");

        assertTrue(notificationMapper.findRelationshipNudgeCandidates(workspace.getId()).stream()
            .anyMatch(candidate -> candidate.getPersonId() == stakeholder.getId()));
        assertTrue(notificationMapper.findOpenDealRecipients(workspace.getId()).stream()
            .anyMatch(recipient -> recipient.getDealId() == deal.getId()));

        personMapper.updateEvaluationExclusions(workspace.getId(), stakeholder.getId(), true, null);
        assertFalse(notificationMapper.findRelationshipNudgeCandidates(workspace.getId()).stream()
            .anyMatch(candidate -> candidate.getPersonId() == stakeholder.getId()));

        dealMapper.updateRiskExcluded(workspace.getId(), deal.getId(), true);
        assertFalse(notificationMapper.findOpenDealRecipients(workspace.getId()).stream()
            .anyMatch(recipient -> recipient.getDealId() == deal.getId()));
    }

    @Test
    void snoozeHidesNotificationUntilWindowPassesAndSurvivesReconcile() {
        User recipient = newUser();
        notificationMapper.upsert(reminder(recipient, "warning", "2026-06-23 00:00:00"));
        Notification stored = onlyReminder(recipient);

        notificationMapper.snooze(recipient.getId(), stored.getId(), "2999-01-01 00:00:00");
        assertTrue(activeInbox(recipient).isEmpty());

        notificationMapper.snooze(recipient.getId(), stored.getId(), "2000-01-01 00:00:00");
        assertEquals(1, activeInbox(recipient).size());

        notificationMapper.snooze(recipient.getId(), stored.getId(), "2999-01-01 00:00:00");
        notificationMapper.upsert(reminder(recipient, "warning", "2026-06-24 00:00:00"));
        assertTrue(activeInbox(recipient).isEmpty());

        notificationMapper.upsert(reminder(recipient, "critical", "2026-06-25 00:00:00"));
        assertEquals(1, activeInbox(recipient).size());

        notificationMapper.snooze(recipient.getId(), stored.getId(), "2999-01-01 00:00:00");
        notificationMapper.resolveReminder(
            workspace.getId(), recipient.getId(), stored.getId(), "2026-06-28 00:00:00");
        notificationMapper.upsert(reminder(recipient, "critical", "2026-06-29 00:00:00"));
        assertEquals(1, activeInbox(recipient).size());
    }

    private List<Notification> activeInbox(User recipient) {
        return notificationMapper.findPage(recipient.getId(), "active", null, null, null, 50, 0);
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
        return reminder(recipient, severity, triggeredAt, 91);
    }

    private Notification reminder(User recipient, String severity, String triggeredAt, int sourceId) {
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
        notification.setSourceId(sourceId);
        notification.setSourceLabel("Send proposal");
        notification.setActionUrl("/activity/tasks?taskId=" + sourceId);
        notification.setData("{\"taskId\":" + sourceId + "}");
        notification.setDedupeKey("task.due:" + sourceId);
        notification.setTriggeredAt(triggeredAt);
        return notification;
    }
}
