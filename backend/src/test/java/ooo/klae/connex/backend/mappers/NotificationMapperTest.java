package ooo.klae.connex.backend.mappers;

import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
import ooo.klae.connex.backend.dto.FacetCount;
import ooo.klae.connex.backend.dto.NotificationCountsDto;

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
    void emailDeliveryClaimCanOnlyBeWonOnceWithinItsDedupeScope() {
        User recipient = newUser();
        User otherRecipient = newUser();
        Notification notification = reminder(recipient, "warning", "2026-06-23 00:00:00");
        Notification otherNotification = reminder(otherRecipient, "warning", "2026-06-23 00:00:00");
        notificationMapper.upsert(notification);
        notificationMapper.upsert(otherNotification);

        assertEquals(1, notificationMapper.claimEmailDelivery(
            workspace.getId(), recipient.getId(), notification.getDedupeKey()));
        assertEquals(0, notificationMapper.claimEmailDelivery(
            workspace.getId(), recipient.getId(), notification.getDedupeKey()));
        assertEquals(1, notificationMapper.claimEmailDelivery(
            workspace.getId(), otherRecipient.getId(), otherNotification.getDedupeKey()));
        assertEquals(0, notificationMapper.claimEmailDelivery(
            workspace.getId() + 1, recipient.getId(), notification.getDedupeKey()));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentEmailDeliveryClaimHasSingleWinner() throws Exception {
        User recipient = newUser();
        Notification notification = reminder(recipient, "warning", "2026-06-23 00:00:00");
        notificationMapper.upsert(notification);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = executor.submit(() -> claimAfterStart(notification, ready, start));
            Future<Integer> second = executor.submit(() -> claimAfterStart(notification, ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            List<Integer> outcomes = List.of(
                first.get(10, TimeUnit.SECONDS),
                second.get(10, TimeUnit.SECONDS)
            ).stream().sorted().toList();

            assertEquals(List.of(0, 1), outcomes);
        } finally {
            start.countDown();
            notificationMapper.deleteAllForRecipient(workspace.getId(), recipient.getId());
            workspaceMapper.removeMember(workspace.getId(), recipient.getId());
            userMapper.delete(recipient.getId());
        }
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

    private int claimAfterStart(
            Notification notification,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent email claims did not start");
        }
        return notificationMapper.claimEmailDelivery(
            notification.getWorkspaceId(), notification.getRecipientId(), notification.getDedupeKey());
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
    void facetsSpanAccessibleStatesAndExcludeForeignOrPendingWorkspaceBuckets() {
        User recipient = newUser();
        User otherRecipient = newUser();
        Workspace second = new Workspace();
        second.setName("Second WS");
        second.setSlug("facets-second-" + unique());
        workspaceMapper.insert(second);
        workspaceMapper.addMember(second.getId(), recipient.getId(), "member");
        Workspace pending = new Workspace();
        pending.setName("Pending WS");
        pending.setSlug("facets-pending-" + unique());
        workspaceMapper.insert(pending);
        workspaceMapper.addPendingMember(pending.getId(), recipient.getId(), "member");

        Notification dismissed = reminder(recipient, "warning", "2026-07-20 00:00:00", 301);
        notificationMapper.upsert(dismissed);
        notificationMapper.dismiss(recipient.getId(), dismissed.getId());

        Notification snoozed = reminder(recipient, "critical", "2026-07-20 01:00:00", 302);
        snoozed.setWorkspaceId(second.getId());
        snoozed.setType("deal.close");
        snoozed.setCategory("deal");
        notificationMapper.upsert(snoozed);
        notificationMapper.snooze(
            recipient.getId(), snoozed.getId(), "2999-01-01 00:00:00", "UTC");

        Notification invitation = reminder(recipient, "info", "2026-07-20 02:00:00", 303);
        invitation.setWorkspaceId(pending.getId());
        invitation.setType("workspace.join");
        invitation.setCategory("workspace");
        notificationMapper.upsert(invitation);

        Notification foreign = reminder(otherRecipient, "critical", "2026-07-20 03:00:00", 304);
        foreign.setCategory("foreign");
        notificationMapper.upsert(foreign);

        Notification inaccessible = reminder(recipient, "info", "2026-07-20 04:00:00", 305);
        inaccessible.setCategory("hidden");
        inaccessible.setSourceType("unknown");
        inaccessible.setSourceId(999999);
        notificationMapper.upsert(inaccessible);

        List<FacetCount> categories = notificationMapper.countsByCategory(recipient.getId());
        List<FacetCount> severities = notificationMapper.countsBySeverity(recipient.getId());
        List<FacetCount> workspaces = notificationMapper.countsByWorkspace(recipient.getId());

        assertEquals(1, facet(categories, "task").getCount());
        assertEquals(1, facet(categories, "deal").getCount());
        assertEquals(1, facet(categories, "workspace").getCount());
        assertNull(findFacet(categories, "foreign"));
        assertNull(findFacet(categories, "hidden"));
        assertEquals(1, facet(severities, "warning").getCount());
        assertEquals(1, facet(severities, "critical").getCount());
        assertEquals(1, facet(severities, "info").getCount());
        assertEquals(
            workspace.getName(),
            facet(workspaces, Integer.toString(workspace.getId())).getLabel()
        );
        assertEquals(second.getName(), facet(workspaces, Integer.toString(second.getId())).getLabel());
        assertNull(findFacet(workspaces, Integer.toString(pending.getId())));
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
        deal.setValue(new BigDecimal("50000.00"));
        dealMapper.updateValueAndSource(
            workspace.getId(), deal.getId(), deal.getValue(), "manual");
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

        personMapper.updateEvaluationExclusions(workspace.getId(), stakeholder.getId(), false, null);
        personMapper.updateProcessingRestrictions(workspace.getId(), stakeholder.getId(), true, false);
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

    @Test
    void statusFiltersCountsAndSnoozeMutationsShareOneSnapshot() {
        User recipient = newUser();
        User otherRecipient = newUser();
        Notification active = reminder(recipient, "warning", "2026-07-20 01:00:00", 201);
        Notification snoozed = reminder(recipient, "critical", "2026-07-20 00:00:00", 202);
        snoozed.setType("deal.close");
        snoozed.setCategory("deal");
        notificationMapper.upsert(active);
        notificationMapper.upsert(snoozed);
        assertEquals(1, notificationMapper.snooze(
            recipient.getId(), snoozed.getId(), "2026-07-20 03:00:00", "America/New_York"));

        String asOf = "2026-07-20 02:00:00";
        List<Notification> unread = notificationMapper.findPage(
            recipient.getId(), "unread", null, null, null, null, null, null, asOf, 25, 0);
        List<Notification> filteredSnoozed = notificationMapper.findPage(
            recipient.getId(), "snoozed", List.of("deal.close"), List.of("deal"),
            List.of("critical"), workspace.getId(), null, null, asOf, 25, 0);
        NotificationCountsDto counts = notificationMapper.getUnreadCounts(recipient.getId(), asOf);

        assertEquals(List.of(active.getId()), unread.stream().map(Notification::getId).toList());
        assertEquals(List.of(snoozed.getId()),
            filteredSnoozed.stream().map(Notification::getId).toList());
        assertEquals(1, counts.getUnread());
        assertEquals(1, counts.getSnoozed());
        assertEquals("2026-07-20 03:00:00",
            notificationMapper.getNextSnoozeExpiry(recipient.getId(), asOf));
        assertNull(notificationMapper.findById(otherRecipient.getId(), snoozed.getId()));
        assertEquals(0, notificationMapper.snooze(
            otherRecipient.getId(), snoozed.getId(), "2026-07-21 03:00:00", "UTC"));
        assertEquals(0, notificationMapper.unsnooze(otherRecipient.getId(), snoozed.getId()));

        assertEquals(1, notificationMapper.snooze(
            recipient.getId(), snoozed.getId(), asOf, "UTC"));
        assertTrue(notificationMapper.findPage(
            recipient.getId(), "active", null, null, null, null, null, null, asOf, 25, 0)
            .stream().anyMatch(item -> item.getId() == snoozed.getId()));

        assertEquals(1, notificationMapper.unsnooze(recipient.getId(), snoozed.getId()));
        Notification unsnoozed = notificationMapper.findById(recipient.getId(), snoozed.getId());
        assertNull(unsnoozed.getSnoozedUntil());
        assertNull(unsnoozed.getSnoozeTimezone());
    }

    @Test
    void dealCloseWorkIsWorkspaceRecipientSourceAndSnoozeScoped() {
        User recipient = newUser();
        User otherRecipient = newUser();
        Company company = newCompany();
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, company);
        Notification visible = dealClose(recipient, deal, "critical", 301);
        Notification other = dealClose(otherRecipient, deal, "critical", 302);
        Notification snoozed = dealClose(recipient, deal, "warning", 303);
        notificationMapper.upsert(visible);
        notificationMapper.upsert(other);
        notificationMapper.upsert(snoozed);
        notificationMapper.snooze(
            recipient.getId(), snoozed.getId(), "2026-08-31 00:00:00", "UTC");

        List<Notification> rows = notificationMapper.findActiveDealCloseWork(
            workspace.getId(), recipient.getId(), "2026-08-30 00:00:00",
            List.of("critical", "warning"), 10);

        assertEquals(List.of(visible.getId()), rows.stream().map(Notification::getId).toList());
        assertEquals(1, notificationMapper.countActiveDealCloseWork(
            workspace.getId(), recipient.getId(), "2026-08-30 00:00:00",
            List.of("critical", "warning")));
        assertNull(notificationMapper.findActiveDealCloseByIdForUpdate(
            workspace.getId(), otherRecipient.getId(), visible.getId(),
            "2026-08-30 00:00:00", List.of()));
    }

    @Test
    void approvalRequestResolutionIsDocumentRecipientAndWorkspaceScoped() {
        User first = newUser();
        User second = newUser();
        Notification firstRequest = approvalRequest(first, 701, 401);
        Notification secondRequest = approvalRequest(second, 701, 402);
        Notification otherDocument = approvalRequest(first, 702, 403);
        notificationMapper.upsert(firstRequest);
        notificationMapper.upsert(secondRequest);
        notificationMapper.upsert(otherDocument);

        assertEquals(List.of(first.getId(), second.getId()).stream().sorted().toList(),
            notificationMapper.findActiveApprovalRequestRecipientIds(workspace.getId(), 701));
        assertEquals(1, notificationMapper.resolveApprovalRequestsForRecipient(
            workspace.getId(), 701, first.getId(), "2026-08-30 00:00:00"));

        assertEquals(List.of(first.getId()),
            notificationMapper.findActiveApprovalRequestRecipientIds(workspace.getId(), 702));
        assertEquals(List.of(second.getId()),
            notificationMapper.findActiveApprovalRequestRecipientIds(workspace.getId(), 701));
        assertEquals(0, notificationMapper.resolveApprovalRequestsForRecipient(
            workspace.getId() + 1, 701, second.getId(), "2026-08-30 00:00:00"));
    }

    @Test
    void inactiveMembershipAndDeletedSourcesAreInaccessibleToReadsAndMutations() {
        User recipient = newUser();
        Task task = new Task();
        task.setWorkspaceId(workspace.getId());
        task.setDescription("Source task");
        task.setStatus("todo");
        task.setAssignedTo(recipient);
        taskMapper.insert(task);
        Notification notification = reminder(recipient, "warning", "2026-07-20 01:00:00", task.getId());
        notification.setSourceType("task");
        notification.setSourceId(task.getId());
        notificationMapper.upsert(notification);

        assertNotNull(notificationMapper.findById(recipient.getId(), notification.getId()));
        taskMapper.delete(workspace.getId(), task.getId());
        assertNull(notificationMapper.findById(recipient.getId(), notification.getId()));
        assertEquals(0, notificationMapper.markRead(recipient.getId(), notification.getId()));

        Notification unknownSource = reminder(
            recipient, "warning", "2026-07-20 01:30:00", 206);
        unknownSource.setType("unknown.event");
        unknownSource.setSourceType("unknown");
        unknownSource.setSourceId(999999);
        notificationMapper.upsert(unknownSource);
        assertNull(notificationMapper.findById(recipient.getId(), unknownSource.getId()));
        assertEquals(0, notificationMapper.markRead(recipient.getId(), unknownSource.getId()));
        assertEquals(0, notificationMapper.countPage(
            recipient.getId(), "all", List.of("unknown.event"), null, null, null,
            null, null, "2026-07-20 02:00:00"));

        Notification membershipNotification = reminder(
            recipient, "warning", "2026-07-20 02:00:00", 204);
        notificationMapper.upsert(membershipNotification);
        workspaceMapper.removeMember(workspace.getId(), recipient.getId());
        assertNull(notificationMapper.findById(recipient.getId(), membershipNotification.getId()));
        assertEquals(0, notificationMapper.snooze(
            recipient.getId(), membershipNotification.getId(), "2026-07-21 03:00:00", "UTC"));
        assertEquals(0, notificationMapper.unsnooze(
            recipient.getId(), membershipNotification.getId()));
    }

    @Test
    void deletedAndUnknownContextsAreInaccessibleToReadsCountsAndMutations() {
        User recipient = newUser();
        Company company = newCompany();
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, company);
        Notification deletedContext = reminder(
            recipient, "warning", "2026-07-20 01:00:00", 207);
        deletedContext.setContextType("deal");
        deletedContext.setContextId(deal.getId());
        notificationMapper.upsert(deletedContext);

        assertNotNull(notificationMapper.findById(recipient.getId(), deletedContext.getId()));
        dealMapper.delete(workspace.getId(), deal.getId());
        assertNull(notificationMapper.findById(recipient.getId(), deletedContext.getId()));
        assertEquals(0, notificationMapper.markRead(recipient.getId(), deletedContext.getId()));

        Notification unknownContext = reminder(
            recipient, "warning", "2026-07-20 01:30:00", 208);
        unknownContext.setContextType("unknown");
        unknownContext.setContextId(999999);
        notificationMapper.upsert(unknownContext);

        assertNull(notificationMapper.findById(recipient.getId(), unknownContext.getId()));
        assertEquals(0, notificationMapper.countPage(
            recipient.getId(), "all", List.of("task.due"), null, null, null,
            null, null, "2026-07-20 02:00:00"));
        assertEquals(0, notificationMapper.snooze(
            recipient.getId(), unknownContext.getId(), "2026-07-21 03:00:00", "UTC"));
    }

    @Test
    void workspaceJoinIsVisibleOnlyWhileMembershipIsPendingOrActive() {
        User recipient = newUser();
        Workspace invitedWorkspace = new Workspace();
        invitedWorkspace.setName("Invited WS");
        invitedWorkspace.setSlug("invited-" + unique());
        workspaceMapper.insert(invitedWorkspace);
        workspaceMapper.addPendingMember(invitedWorkspace.getId(), recipient.getId(), "member");
        Notification invitation = reminder(recipient, "info", "2026-07-20 01:00:00", 205);
        invitation.setWorkspaceId(invitedWorkspace.getId());
        invitation.setType("workspace.join");
        invitation.setCategory("workspace");
        notificationMapper.upsert(invitation);

        assertNotNull(notificationMapper.findById(recipient.getId(), invitation.getId()));
        workspaceMapper.removeMember(invitedWorkspace.getId(), recipient.getId());
        assertNull(notificationMapper.findById(recipient.getId(), invitation.getId()));
        assertEquals(0, notificationMapper.snooze(
            recipient.getId(), invitation.getId(), "2026-07-21 03:00:00", "UTC"));
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

    private static FacetCount facet(List<FacetCount> facets, String key) {
        return facets.stream()
            .filter(candidate -> key.equals(candidate.getKey()))
            .findFirst()
            .orElseThrow();
    }

    private static FacetCount findFacet(List<FacetCount> facets, String key) {
        return facets.stream()
            .filter(candidate -> key.equals(candidate.getKey()))
            .findFirst()
            .orElse(null);
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
        notification.setSourceLabel("Send proposal");
        notification.setActionUrl("/activity/tasks?task=" + sourceId);
        notification.setData("{\"taskId\":" + sourceId + "}");
        notification.setDedupeKey("task.due:" + sourceId);
        notification.setTriggeredAt(triggeredAt);
        return notification;
    }

    private Notification dealClose(User recipient, Deal deal, String severity, int dedupeId) {
        Notification notification = reminder(
            recipient, severity, "2026-08-29 00:00:00", dedupeId);
        notification.setType("deal.close");
        notification.setCategory("deal");
        notification.setTitle("Deal closing");
        notification.setSourceType("deal");
        notification.setSourceId(deal.getId());
        notification.setContextType("deal");
        notification.setContextId(deal.getId());
        notification.setData("{\"dealId\":" + deal.getId()
            + ",\"expectedCloseDate\":\"2026-08-31\"}");
        notification.setDedupeKey("deal.close:" + dedupeId);
        return notification;
    }

    private Notification approvalRequest(User recipient, int documentId, int dedupeId) {
        Notification notification = reminder(
            recipient, "info", "2026-08-29 00:00:00", dedupeId);
        notification.setType("document.approval_request");
        notification.setCategory("document");
        notification.setSourceType("deal_document");
        notification.setSourceId(documentId);
        notification.setDedupeKey("document.approval_request:" + dedupeId);
        return notification;
    }
}
