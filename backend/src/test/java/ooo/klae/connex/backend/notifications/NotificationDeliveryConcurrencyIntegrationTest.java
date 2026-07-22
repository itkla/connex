package ooo.klae.connex.backend.notifications;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.mappers.PreferenceMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.NotificationQuietHoursControlAccess;
import ooo.klae.connex.backend.services.NotificationQuietHoursEvaluator;

/** Verifies concurrent first-occurrence delivery against the real MySQL mapper and transaction boundary. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NotificationDeliveryConcurrencyIntegrationTest {

    @Autowired private NotificationDelivery delivery;
    @Autowired private UserMapper userMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;
    @MockitoSpyBean private NotificationMapper notificationMapper;
    @MockitoBean private PreferenceMapper preferenceMapper;
    @MockitoBean private EmailNotificationDispatcher emailDispatcher;
    @MockitoBean private NotificationPushPublisher pushPublisher;
    @MockitoBean private NotificationStateVersionService stateVersionService;
    @MockitoBean private NotificationQuietHoursControlAccess quietHoursControlAccess;

    private Workspace workspace;
    private User recipient;

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
        verify(notificationMapper, times(2)).claimEmailDelivery(
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
        assertTrue(persisted.getId() > 0);
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
