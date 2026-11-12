package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.SnoozeRequest;
import ooo.klae.connex.backend.notifications.NotificationDelivery;
import ooo.klae.connex.backend.support.TestEntityFixtures;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry;

/** Exercises application-owned invalidation after real commits, without the pre-baseline hooks. */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NotificationFreshnessIntegrationTest extends AbstractServiceTest {
    @Autowired private NotificationService inbox;
    @Autowired private NotificationDelivery delivery;
    @Autowired private NotificationReconciliationService reconciliation;
    @Autowired private WorkspaceService workspaces;
    @Autowired private RoleService roles;
    @Autowired private TenantTeardownTenantTransaction teardown;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;
    private final List<Integer> accounts = new ArrayList<>();
    private final List<Integer> createdWorkspaces = new ArrayList<>();
    private final List<Integer> createdOrganizations = new ArrayList<>();
    private final List<Integer> createdRoles = new ArrayList<>();

    @Override
    protected User newUser() {
        User user = super.newUser();
        accounts.add(user.getId());
        return user;
    }

    @AfterEach
    void cleanCommittedFixtures() {
        for (int id : createdRoles) {
            jdbc.update("DELETE FROM workspace_role WHERE id = ?", id);
        }
        for (int id : createdWorkspaces) {
            TestEntityFixtures.deleteChatPrivacy(jdbc, id);
            jdbc.update("DELETE FROM workspace WHERE id = ?", id);
        }
        for (int id : createdOrganizations) {
            TestEntityFixtures.deleteOrganizationPrivacy(jdbc, id);
            jdbc.update("DELETE FROM organization WHERE id = ?", id);
        }
        for (int id : accounts) {
            jdbc.update("DELETE FROM notification WHERE recipient_id = ?", id);
            jdbc.update("DELETE FROM app_user WHERE id = ?", id);
        }
    }

    @Test
    void createUpdateReadUnreadDismissRestoreSnoozeUnsnoozeAndBulkReadEachBumpOnce() {
        Notification notice = reminder(currentUser);
        bumpsOnce(currentUser, () -> assertTrue(delivery.deliver(notice)));
        unchanged(currentUser, () -> assertFalse(delivery.deliver(notice)));
        notice.setTitle("Changed reminder");
        bumpsOnce(currentUser, () -> assertTrue(delivery.deliver(notice)));
        int id = notificationMapper.findByDedupe(workspace.getId(), currentUser.getId(), notice.getDedupeKey()).getId();

        bumpsOnce(currentUser, () -> inbox.markRead(id));
        unchanged(currentUser, () -> inbox.markRead(id));
        bumpsOnce(currentUser, () -> inbox.markUnread(id));
        unchanged(currentUser, () -> inbox.markUnread(id));
        bumpsOnce(currentUser, () -> inbox.dismiss(id));
        unchanged(currentUser, () -> inbox.dismiss(id));
        bumpsOnce(currentUser, () -> inbox.restore(id));
        unchanged(currentUser, () -> inbox.restore(id));
        SnoozeRequest request = new SnoozeRequest();
        request.setHours(1);
        bumpsOnce(currentUser, () -> inbox.snooze(id, request));
        bumpsOnce(currentUser, () -> inbox.unsnooze(id));
        unchanged(currentUser, () -> inbox.unsnooze(id));
        Notification second = reminder(currentUser);
        assertTrue(delivery.deliver(second));
        bumpsOnce(currentUser, () -> inbox.markAllRead());
        unchanged(currentUser, () -> inbox.markAllRead());
    }

    @Test
    void resolvingAndPurgingMultipleRowsBumpsEachAffectedRecipientOnce() {
        User other = newUser();
        for (User recipient : List.of(currentUser, other)) {
            for (int i = 0; i < 2; i++) {
                Notification notice = reminder(recipient);
                assertTrue(delivery.deliver(notice));
                jdbc.update("UPDATE notification SET source_type = 'task', source_id = 2147483647 WHERE id = ?", notice.getId());
            }
        }
        long otherBefore = version(other);
        bumpsOnce(currentUser, () -> inTransaction(() -> assertEquals(4,
            reconciliation.reconcileSourcePayloads(workspace.getId()))));
        assertEquals(otherBefore + 1, version(other));
        unchanged(currentUser, () -> inTransaction(() -> assertEquals(0,
            reconciliation.reconcileSourcePayloads(workspace.getId()))));
        jdbc.update("UPDATE notification SET resolved_at = '2000-01-01 00:00:00' WHERE recipient_id IN (?, ?)",
            currentUser.getId(), other.getId());

        otherBefore = version(other);
        bumpsOnce(currentUser, () -> assertEquals(4, reconciliation.purgeWorkspace(workspace.getId())));
        assertEquals(otherBefore + 1, version(other));
        unchanged(currentUser, () -> assertEquals(0, reconciliation.purgeWorkspace(workspace.getId())));
    }

    @Test
    void membershipCreationActivationRemovalAndDepartureBumpOnceIncludingNotificationCleanup() {
        User member = newUser();
        workspaceMapper.removeMember(workspace.getId(), member.getId());
        bumpsOnce(member, () -> workspaces.addPendingMember(workspace.getId(), currentUser, member, "member"));
        assertFalse(workspaceMapper.isMember(workspace.getId(), member.getId()));
        bumpsOnce(member, () -> workspaces.approveMembership(workspace.getId(), member.getId()));
        assertTrue(workspaceMapper.isMember(workspace.getId(), member.getId()));
        bumpsOnce(member, () -> workspaces.removeMember(workspace.getId(), currentUser.getId(), member.getId()));
        assertEquals(0, notificationMapper.countPage(member.getId(), null, null, null, null));
        bumpsOnce(member, () -> workspaces.ensureActiveMember(workspace.getId(), member.getId(), "member"));
        unchanged(member, () -> workspaces.ensureActiveMember(workspace.getId(), member.getId(), "member"));
        assertTrue(delivery.deliver(reminder(member)));
        bumpsOnce(member, () -> workspaces.leaveWorkspace(workspace.getId(), member.getId()));
        assertEquals(0, notificationMapper.countPage(member.getId(), null, null, null, null));
        bumpsOnce(member, () -> workspaces.addPendingMember(workspace.getId(), currentUser, member, "member"));
        bumpsOnce(member, () -> workspaces.declineMembership(workspace.getId(), member.getId()));
    }

    @Test
    void workspaceCreationBumpsItsOwnerOnce() {
        bumpsOnce(currentUser, () -> createWorkspace());
    }

    @Test
    void teardownBatchesBumpOnlyTheirRecipientsOnceAndPreserveOtherWorkspaces() {
        workspace = Objects.requireNonNull(workspaceMapper.getActiveById(createWorkspace()));
        authenticateAs(currentUser, workspace.getId());
        User other = newUser();
        assertTrue(delivery.deliver(reminder(currentUser)));
        assertTrue(delivery.deliver(reminder(currentUser)));
        assertTrue(delivery.deliver(reminder(other)));
        Notification outside = reminder(currentUser);
        outside.setWorkspaceId(createWorkspace());
        assertTrue(delivery.deliver(outside));
        assertEquals(0, notificationMapper.deleteTeardownBatch(workspace.getId(), List.of(outside.getId())));
        var declaration = TenantLifecycleRegistry.require("notification");
        long otherBefore = version(other);

        bumpsOnce(currentUser, () -> assertEquals(2, teardown.deleteBatch(workspace.getId(), declaration, 2)));
        assertEquals(otherBefore, version(other));
        long currentBefore = version(currentUser);
        bumpsOnce(other, () -> assertEquals(1, teardown.deleteBatch(workspace.getId(), declaration, 2)));
        assertEquals(currentBefore, version(currentUser));
        unchanged(other, () -> assertEquals(0, teardown.deleteBatch(workspace.getId(), declaration, 2)));
        assertNotNull(notificationMapper.findByDedupe(outside.getWorkspaceId(), currentUser.getId(), outside.getDedupeKey()));
    }

    @Test
    void teardownDeletionRollsBackWhenItsRecipientVersionCannotAdvance() {
        workspace = Objects.requireNonNull(workspaceMapper.getActiveById(createWorkspace()));
        authenticateAs(currentUser, workspace.getId());
        Notification notice = reminder(currentUser);
        assertTrue(delivery.deliver(notice));
        long before = version(currentUser);
        try {
            jdbc.execute("""
                CREATE TRIGGER test_notification_teardown_state_failure
                BEFORE UPDATE ON notification_recipient_state FOR EACH ROW
                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Recipient version write refused'
                """);

            assertThrows(DataAccessException.class, () -> teardown.deleteBatch(
                workspace.getId(), TenantLifecycleRegistry.require("notification"), 10));
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS test_notification_teardown_state_failure");
        }
        assertEquals(before, version(currentUser));
        assertNotNull(notificationMapper.findByDedupe(workspace.getId(), currentUser.getId(), notice.getDedupeKey()));
    }

    @Test
    void roleChangesWithoutOfferInvalidationLeaveTheVisibleInboxAndVersionUnchanged() {
        User member = newUser();
        Notification notice = reminder(member);
        assertTrue(delivery.deliver(notice));
        unchanged(member, () -> workspaces.changeMemberRole(workspace.getId(), currentUser.getId(), member.getId(), "admin", true));
        assertNotNull(notificationMapper.findById(member.getId(), notice.getId()));
        unchanged(member, () -> workspaces.changeMemberRole(workspace.getId(), currentUser.getId(), member.getId(), "member"));
        assertNotNull(notificationMapper.findById(member.getId(), notice.getId()));
        int roleId = roles.createRole(workspace.getId(), currentUser.getId(), "Freshness role " + unique(), List.of()).getId();
        createdRoles.add(roleId);
        unchanged(member, () -> workspaces.assignCustomRole(workspace.getId(), currentUser.getId(), member.getId(), roleId));
        assertNotNull(notificationMapper.findById(member.getId(), notice.getId()));
        unchanged(member, () -> workspaces.changeMemberRole(workspace.getId(), currentUser.getId(), member.getId(), "member"));
    }

    @Test
    void expiredPendingInvitationIsHiddenWithoutADurableStateVersionBump() {
        User member = newUser();
        workspaceMapper.removeMember(workspace.getId(), member.getId());
        workspaces.addPendingMember(workspace.getId(), currentUser, member, "member");
        Notification invitation = notificationMapper.findPage(member.getId(), "active", null, null, null, 100, 0).getFirst();
        assertEquals("workspace.join", invitation.getType());
        long before = version(member);

        jdbc.update("UPDATE workspace_member SET expires_at = '2000-01-01 00:00:00' WHERE workspace_id = ? AND user_id = ?",
            workspace.getId(), member.getId());

        assertNull(notificationMapper.findById(member.getId(), invitation.getId()));
        assertEquals(0, notificationMapper.getUnreadCounts(member.getId()).getUnread());
        assertEquals(before, version(member));
    }

    private Notification reminder(User recipient) {
        Notification notice = new Notification();
        notice.setWorkspaceId(workspace.getId());
        notice.setRecipientId(recipient.getId());
        notice.setType("task.due");
        notice.setCategory("task");
        notice.setSeverity("warning");
        notice.setTemplateVersion(1);
        notice.setTitle("Freshness reminder");
        notice.setDedupeKey("freshness:" + unique());
        notice.setTriggeredAt(notificationMapper.getDatabaseUtcTimestamp());
        return notice;
    }

    private int createWorkspace() {
        int id = workspaces.createWorkspace("Freshness workspace " + unique(), currentUser.getId()).getId();
        createdWorkspaces.add(id);
        Integer organizationId = workspaceMapper.getOrgId(id);
        assertNotNull(organizationId);
        if (!Objects.equals(organizationId, workspace.getOrgId())) {
            createdOrganizations.add(organizationId);
        }
        return id;
    }

    private void inTransaction(Runnable action) {
        var transaction = new TransactionTemplate(transactions);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.executeWithoutResult(status -> action.run());
    }

    private long version(User recipient) {
        return notificationMapper.getStateVersion(recipient.getId());
    }

    private void bumpsOnce(User recipient, Runnable action) {
        long before = version(recipient);
        action.run();
        assertEquals(before + 1, version(recipient));
    }

    private void unchanged(User recipient, Runnable action) {
        long before = version(recipient);
        action.run();
        assertEquals(before, version(recipient));
    }
}
