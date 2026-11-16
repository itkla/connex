package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.notifications.NotificationStateVersionService;
import ooo.klae.connex.backend.services.NotificationService;

/** Exercises privacy pruning and recipient invalidation in their committed maintenance transactions. */
class AiAgentPrivacyNotificationIntegrationTest extends AbstractAiAgentPrivacyIntegrationTest {
    @Autowired private NotificationService inbox;
    @Autowired private NotificationStateVersionService stateVersions;
    private final List<Integer> extraRecipients = new ArrayList<>();

    @AfterEach
    void removeNotificationFixtures() {
        jdbc.update("DELETE FROM notification WHERE workspace_id = ?", workspace.getId());
        extraRecipients.forEach(id -> jdbc.update("DELETE FROM app_user WHERE id = ?", id));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void pruningAlreadyReadBriefsInvalidatesTheirRecipientExactlyOnce(boolean organizationScope) {
        AiChatSession session = session();
        Notification first = readBrief(session);
        Notification second = readBrief(session);
        User other = newUser();
        extraRecipients.add(other.getId());
        chatMapper.insertParticipant(workspace.getId(), session.getId(), other.getId());
        readBrief(session, other);
        assertNotNull(notificationMapper.findById(currentUser.getId(), first.getId()));
        assertEquals(0, notificationMapper.getUnreadCounts(currentUser.getId()).getUnread());
        transaction().executeWithoutResult(status -> {
            assertTrue(privacyMapper.lockSessionNotifications(Integer.MAX_VALUE, session.getId(), 100).isEmpty());
            assertEquals(0, privacyMapper.deleteSessionNotifications(Integer.MAX_VALUE, session.getId(),
                List.of(first.getId(), second.getId())));
            assertEquals(0, privacyMapper.deleteSessionNotifications(workspace.getId(), Integer.MAX_VALUE,
                List.of(first.getId(), second.getId())));
        });
        long before = notificationMapper.getStateVersion(currentUser.getId());
        long otherBefore = notificationMapper.getStateVersion(other.getId());
        String operation = begin(organizationScope);

        finish(operation);

        assertEquals(0, notifications(session));
        assertEquals(before + 1, notificationMapper.getStateVersion(currentUser.getId()));
        assertEquals(otherBefore + 1, notificationMapper.getStateVersion(other.getId()));
        assertEquals(0, notificationMapper.getUnreadCounts(currentUser.getId()).getUnread());
        assertTrue(privacyService.batch(workspace.getId(), operation));
        assertEquals(before + 1, notificationMapper.getStateVersion(currentUser.getId()));
        assertEquals(otherBefore + 1, notificationMapper.getStateVersion(other.getId()));
    }

    @Test
    void pruningAnErasedRecipientsBriefsDoesNotRecreateTheirAccountReference() {
        AiChatSession session = session();
        readBrief(session);
        String operation = begin(false);
        jdbc.update("DELETE FROM workspace_member WHERE workspace_id = ? AND user_id = ?",
            workspace.getId(), currentUser.getId());
        jdbc.update("DELETE FROM app_user WHERE id = ?", currentUser.getId());

        finish(operation);

        assertEquals(0, notifications(session));
        assertEquals(0, notificationMapper.getStateVersion(currentUser.getId()));
    }

    @Test
    void pruningCommitsItsVersionWhenACallersQueuedChangeRollsBack() {
        AiChatSession session = session();
        readBrief(session);
        String operation = begin(false);
        advanceToTitle(operation);
        long before = notificationMapper.getStateVersion(currentUser.getId());

        transaction().executeWithoutResult(status -> {
            stateVersions.markChanged(currentUser.getId());
            assertFalse(privacyService.batch(workspace.getId(), operation));
            status.setRollbackOnly();
        });

        assertEquals(0, notifications(session));
        assertEquals(before + 1, notificationMapper.getStateVersion(currentUser.getId()));
        finish(operation);
        assertEquals(before + 1, notificationMapper.getStateVersion(currentUser.getId()));
    }

    @Test
    void failedVersionWriteRollsBackPruningAndTheSessionCursor() {
        AiChatSession session = session();
        readBrief(session);
        String operation = begin(false);
        advanceToTitle(operation);
        long before = notificationMapper.getStateVersion(currentUser.getId());
        try {
            jdbc.execute("""
                CREATE TRIGGER test_privacy_notification_state_failure
                BEFORE UPDATE ON notification_recipient_state FOR EACH ROW
                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Recipient version write refused'
                """);
            assertThrows(DataAccessException.class, () -> privacyService.batch(workspace.getId(), operation));
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS test_privacy_notification_state_failure");
        }
        assertEquals(1, notifications(session));
        assertEquals(before, notificationMapper.getStateVersion(currentUser.getId()));
        var work = privacyMapper.getWork(workspace.getId(), operation);
        assertNotNull(work);
        assertEquals("title", work.getSessionPhase());

        finish(operation);

        assertEquals(0, notifications(session));
        assertEquals(before + 1, notificationMapper.getStateVersion(currentUser.getId()));
    }

    private AiChatSession session() {
        AiChatSession session = new AiChatSession();
        session.setWorkspaceId(workspace.getId());
        session.setOrganizationPrivacyGeneration(1);
        session.setCreatedByUserId(currentUser.getId());
        session.setTitle("Brief privacy fixture");
        session.setVisibility("private");
        session.setStatus("active");
        chatMapper.insertSession(session);
        jdbc.update("""
            UPDATE ai_chat_session SET message_seq_high_water = 1,
                mission_taint_generation = 1, mission_taint_seq = 1
            WHERE workspace_id = ? AND id = ?
            """, workspace.getId(), session.getId());
        return session;
    }

    private Notification readBrief(AiChatSession session) {
        return readBrief(session, currentUser);
    }

    private Notification readBrief(AiChatSession session, User recipient) {
        authenticateAs(recipient, workspace.getId());
        Notification notice = new Notification();
        notice.setWorkspaceId(workspace.getId());
        notice.setRecipientId(recipient.getId());
        notice.setType("ai.brief.ready");
        notice.setCategory("assistant");
        notice.setSeverity("info");
        notice.setTemplateVersion(1);
        notice.setTitle("Brief ready");
        notice.setSourceType("ai_chat_session");
        notice.setSourceId(session.getId());
        notice.setDedupeKey("privacy-brief:" + UUID.randomUUID());
        notice.setTriggeredAt(notificationMapper.getDatabaseUtcTimestamp());
        notificationMapper.upsert(notice);
        assertNotNull(inbox.markRead(notice.getId()).getReadAt());
        authenticateAs(currentUser, workspace.getId());
        return notice;
    }

    private String begin(boolean organizationScope) {
        if (!organizationScope) {
            return invalidate();
        }
        UUID operation = UUID.randomUUID();
        transaction().executeWithoutResult(status -> privacyService.invalidateAssistantOrganization(
            workspace.getOrgId(), List.of(workspace.getId()), operation, "restriction", null, null));
        privacyService.reconcileAssistantOperations(workspace.getId());
        return operation.toString();
    }

    private long notifications(AiChatSession session) {
        return java.util.Objects.requireNonNull(jdbc.queryForObject("""
            SELECT COUNT(*) FROM notification WHERE workspace_id = ?
                AND source_type = 'ai_chat_session' AND source_id = ?
            """, Long.class, workspace.getId(), session.getId()));
    }

    private void advanceToTitle(String operation) {
        for (int count = 0; count < 50; count++) {
            var work = privacyMapper.getWork(workspace.getId(), operation);
            assertNotNull(work);
            if ("sessions".equals(work.getPhase()) && "title".equals(work.getSessionPhase())) {
                return;
            }
            assertFalse(privacyService.batch(workspace.getId(), operation));
        }
        throw new AssertionError("Privacy work did not reach notification pruning");
    }
}
