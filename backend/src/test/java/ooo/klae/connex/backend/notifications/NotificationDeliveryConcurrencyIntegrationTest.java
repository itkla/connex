package ooo.klae.connex.backend.notifications;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.mappers.PreferenceMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.NotificationQuietHoursControlAccess;
import ooo.klae.connex.backend.services.NotificationQuietHoursEvaluator;
import ooo.klae.connex.backend.support.LegacyNotificationTriggerFixtures;

/** Verifies notification delivery changes against the real MySQL mapper and transaction boundary. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NotificationDeliveryConcurrencyIntegrationTest {

    @Autowired private NotificationDelivery delivery;
    @Autowired private UserMapper userMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private NotificationMapper notificationMapper;
    @MockitoBean private PreferenceMapper preferenceMapper;
    @MockitoBean private EmailNotificationDispatcher emailDispatcher;
    @MockitoBean private NotificationPushPublisher pushPublisher;
    @MockitoBean private NotificationQuietHoursControlAccess quietHoursControlAccess;

    private Workspace workspace;
    private User recipient;

    @Test
    void legacyTriggersInvalidateUnchangedRedeliveryUntilTheForwardMigrationsRun() {
        Notification incoming = notification("task.due:legacy:" + UUID.randomUUID());
        assertTrue(delivery.deliver(incoming));
        long before = notificationMapper.getStateVersion(recipient.getId());
        Notification persisted = notificationMapper.findByDedupe(
            workspace.getId(), recipient.getId(), incoming.getDedupeKey());
        clearInvocations(pushPublisher);
        try {
            LegacyNotificationTriggerFixtures.install(jdbcTemplate);

            assertFalse(delivery.deliver(incoming));
            assertEquals(before + 1, notificationMapper.getStateVersion(recipient.getId()));
            verifyNoInteractions(pushPublisher);

            LegacyNotificationTriggerFixtures.apply(jdbcTemplate);

            assertFalse(delivery.deliver(incoming));
            assertFalse(delivery.deliver(incoming));
            assertEquals(before + 1, notificationMapper.getStateVersion(recipient.getId()));
            assertEquals(persisted.getUpdatedAt(), notificationMapper.findByDedupe(
                workspace.getId(), recipient.getId(), incoming.getDedupeKey()).getUpdatedAt());
            verifyNoInteractions(pushPublisher);

            incoming.setTitle("Changed reminder");
            assertTrue(delivery.deliver(incoming));
            assertEquals(before + 2, notificationMapper.getStateVersion(recipient.getId()));
            verify(pushPublisher).updated(eq(recipient.getId()), any(), eq(incoming.getDedupeKey()));
        } finally {
            LegacyNotificationTriggerFixtures.remove(jdbcTemplate);
        }
    }

    @BeforeEach
    void setUp() {
        workspace = workspaceMapper.getDefaultWorkspace();
        if (workspace == null) {
            workspace = new Workspace();
            workspace.setName("Test Workspace");
            workspace.setSlug("default");
            workspaceMapper.insert(workspace);
        }
        String unique = UUID.randomUUID().toString().substring(0, 8);
        recipient = new User();
        recipient.setUsername("notification_claim_" + unique);
        recipient.setDisplayName("Notification Claim " + unique);
        recipient.setEmail(unique + "@example.com");
        recipient.setPasswordHash("hash_" + unique);
        recipient.setTimezone("UTC");
        userMapper.insert(recipient);
        workspaceMapper.addMember(workspace.getId(), recipient.getId(), "member");
        when(emailDispatcher.channel()).thenReturn("email");
        when(emailDispatcher.dispatch(any())).thenReturn(1);
        when(preferenceMapper.isEnabledOptIn(recipient.getId(), "task.due", "email"))
            .thenReturn(true);
        when(quietHoursControlAccess.evaluateForUser(eq(recipient.getId()), any()))
            .thenReturn(new NotificationQuietHoursEvaluator.Evaluation(false, null));
    }

    @AfterEach
    void cleanUp() {
        notificationMapper.deleteAllForRecipient(workspace.getId(), recipient.getId());
        workspaceMapper.removeMember(workspace.getId(), recipient.getId());
        userMapper.delete(recipient.getId());
    }

    @Test
    void concurrentMissingPreReadsProduceOneCommittedEmail() throws Exception {
        String dedupeKey = "task.due:concurrent:" + UUID.randomUUID();
        CountDownLatch bothMissing = new CountDownLatch(2);
        NotificationMapper realNotificationMapper = sqlSessionTemplate.getMapper(NotificationMapper.class);
        doAnswer(invocation -> {
            Notification existing = realNotificationMapper.findByDedupe(
                workspace.getId(), recipient.getId(), dedupeKey);
            if (existing == null) {
                bothMissing.countDown();
                if (!bothMissing.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Concurrent notification pre-reads did not meet");
                }
            }
            return existing;
        }).when(notificationMapper).findByDedupe(workspace.getId(), recipient.getId(), dedupeKey);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> delivery.deliver(notification(dedupeKey)));
            Future<?> second = executor.submit(() -> delivery.deliver(notification(dedupeKey)));
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        } finally {
            bothMissing.countDown();
            bothMissing.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        Notification persisted = notificationMapper.findByDedupe(
            workspace.getId(), recipient.getId(), dedupeKey);
        assertNotNull(persisted);
        verify(notificationMapper, atLeastOnce()).claimEmailDelivery(
            workspace.getId(), recipient.getId(), dedupeKey);
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM notification WHERE workspace_id = ? AND recipient_id = ? AND dedupe_key = ?",
            Integer.class,
            workspace.getId(),
            recipient.getId(),
            dedupeKey));
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM notification WHERE workspace_id = ? AND recipient_id = ? AND dedupe_key = ? "
                + "AND email_delivery_claimed_at IS NOT NULL",
            Integer.class,
            workspace.getId(),
            recipient.getId(),
            dedupeKey));
        verify(emailDispatcher, times(1)).dispatch(any());
        assertEquals(1L, notificationMapper.getStateVersion(recipient.getId()));
        verify(pushPublisher).created(eq(recipient.getId()), any(), eq(dedupeKey));
        assertTrue(persisted.getId() > 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"task.due", "deal.close", "deal.risk"})
    void normalizedActorRedeliveryDoesNotBumpOrPush(String type) {
        Notification incoming = notification(type + ":normalized:" + UUID.randomUUID());
        incoming.setType(type);
        incoming.setActorId(0);
        assertNull(userMapper.getUserById(0));
        assertTrue(delivery.deliver(incoming));
        assertEquals(1L, notificationMapper.getStateVersion(recipient.getId()));
        Notification normalized = notificationMapper.findByDedupe(
            workspace.getId(), recipient.getId(), incoming.getDedupeKey());
        assertNotNull(normalized);
        assertNull(normalized.getActorId());
        assertTrue(notificationMapper.upsert(incoming) <= 1);
        jdbcTemplate.update("UPDATE notification SET updated_at='2001-01-01 00:00:00' WHERE id=?",
            incoming.getId());
        Map<String, Object> before = snapshot(incoming);
        clearInvocations(pushPublisher);

        int changedPasses = 0;
        for (int pass = 0; pass < 5; pass++) {
            if (delivery.deliver(incoming)) changedPasses++;
            assertEquals(before, snapshot(incoming));
        }

        assertEquals(0, changedPasses);
        assertEquals(1L, notificationMapper.getStateVersion(recipient.getId()));
        verifyNoInteractions(pushPublisher);

        incoming.setBody("Updated reminder evidence");
        assertTrue(delivery.deliver(incoming));
        assertEquals(2L, notificationMapper.getStateVersion(recipient.getId()));
        verify(pushPublisher).updated(eq(recipient.getId()), any(), eq(incoming.getDedupeKey()));
        Map<String, Object> changed = snapshot(incoming);
        clearInvocations(pushPublisher);
        for (int pass = 0; pass < 5; pass++) {
            assertFalse(delivery.deliver(incoming));
            assertEquals(changed, snapshot(incoming));
            assertEquals(2L, notificationMapper.getStateVersion(recipient.getId()));
        }
        verifyNoInteractions(pushPublisher);
    }

    @ParameterizedTest
    @ValueSource(strings = {"task.due", "deal.close", "deal.risk"})
    void resolvedReminderReopensOnceAndThenConverges(String type) {
        Notification incoming = notification(type + ":resolved:" + UUID.randomUUID());
        incoming.setType(type);
        assertTrue(delivery.deliver(incoming));
        jdbcTemplate.update("UPDATE notification SET resolved_at='2026-07-20 00:00:00', "
            + "read_at='2026-07-19 00:00:00', dismissed_at='2026-07-19 00:00:00', "
            + "snoozed_until='2026-07-22 00:00:00', snooze_timezone='UTC' WHERE id=?", incoming.getId());
        clearInvocations(pushPublisher);
        incoming.setTriggeredAt("2026-07-21 01:00:00");

        assertTrue(delivery.deliver(incoming));

        assertEquals(2L, notificationMapper.getStateVersion(recipient.getId()));
        Notification reopened = notificationMapper.findByDedupe(
            workspace.getId(), recipient.getId(), incoming.getDedupeKey());
        assertNotNull(reopened);
        assertNull(reopened.getResolvedAt());
        assertNull(reopened.getReadAt());
        assertNull(reopened.getDismissedAt());
        assertNull(reopened.getSnoozedUntil());
        assertNull(reopened.getSnoozeTimezone());
        assertEquals(incoming.getTriggeredAt(), reopened.getTriggeredAt());
        verify(pushPublisher).updated(eq(recipient.getId()), any(), eq(incoming.getDedupeKey()));
        Map<String, Object> before = snapshot(incoming);
        clearInvocations(pushPublisher);
        for (int pass = 0; pass < 5; pass++) {
            assertFalse(delivery.deliver(incoming));
            assertEquals(before, snapshot(incoming));
            assertEquals(2L, notificationMapper.getStateVersion(recipient.getId()));
        }
        verifyNoInteractions(pushPublisher);
    }

    @Test
    void purgedResolvedReminderIsRecreatedWithOneBumpAndPush() {
        Notification incoming = notification("task.due:purged:" + UUID.randomUUID());
        assertTrue(delivery.deliver(incoming));
        int previousId = incoming.getId();
        jdbcTemplate.update("UPDATE notification SET resolved_at='2001-01-01 00:00:00' WHERE id=?", previousId);
        NotificationMapper realMapper = sqlSessionTemplate.getMapper(NotificationMapper.class);
        doAnswer(invocation -> {
            Notification discovered = realMapper.findByDedupe(
                workspace.getId(), recipient.getId(), incoming.getDedupeKey());
            if (discovered != null && discovered.getId() == previousId) {
                TransactionTemplate purge = new TransactionTemplate(transactionManager);
                purge.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                purge.executeWithoutResult(status -> assertEquals(1, jdbcTemplate.update(
                    "DELETE FROM notification WHERE workspace_id=? AND recipient_id=? AND id=?",
                    workspace.getId(), recipient.getId(), previousId)));
            }
            return discovered;
        }).when(notificationMapper).findByDedupe(workspace.getId(), recipient.getId(), incoming.getDedupeKey());
        clearInvocations(pushPublisher);

        assertTrue(delivery.deliver(incoming));

        assertTrue(incoming.getId() > previousId);
        assertEquals(2L, notificationMapper.getStateVersion(recipient.getId()));
        assertEquals(incoming.getId(), snapshot(incoming).get("id"));
        verify(pushPublisher).updated(eq(recipient.getId()), any(), eq(incoming.getDedupeKey()));
        Map<String, Object> before = snapshot(incoming);
        clearInvocations(pushPublisher);
        for (int pass = 0; pass < 5; pass++) {
            assertFalse(delivery.deliver(incoming));
            assertEquals(before, snapshot(incoming));
            assertEquals(2L, notificationMapper.getStateVersion(recipient.getId()));
        }
        verifyNoInteractions(pushPublisher);
    }

    private Map<String, Object> snapshot(Notification notification) {
        return jdbcTemplate.queryForMap("SELECT * FROM notification WHERE workspace_id=? AND recipient_id=? AND dedupe_key=?",
            workspace.getId(), recipient.getId(), notification.getDedupeKey());
    }

    private Notification notification(String dedupeKey) {
        Notification notification = new Notification();
        notification.setWorkspaceId(workspace.getId());
        notification.setRecipientId(recipient.getId());
        notification.setType("task.due");
        notification.setCategory("tasks");
        notification.setSeverity("warning");
        notification.setTemplateVersion(1);
        notification.setTitle("Task due");
        notification.setBody("A task is due");
        notification.setDedupeKey(dedupeKey);
        notification.setTriggeredAt("2026-07-21 00:00:00");
        return notification;
    }
}
