package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.notifications.NotificationDelivery;
import ooo.klae.connex.backend.support.TestEntityFixtures;

/** Proves recipient membership remains locked through the notification effect and commit. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WorkflowRecipientMembershipIntegrationTest {
    @Autowired private RuleActionExecutor actions;
    @Autowired private AutomationExecutor automation;
    @Autowired private WorkspaceMapper workspaces;
    @Autowired private UserMapper users;
    @Autowired private CompanyMapper companies;
    @Autowired private NotificationMapper notifications;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @MockitoSpyBean private NotificationDelivery delivery;
    private Workspace workspace;
    private User actor;
    private User recipient;
    private Company company;

    @BeforeEach
    void fixtures() {
        workspace = workspaces.getDefaultWorkspace();
        if (workspace == null) {
            workspace = new Workspace();
            workspace.setName("Test Workspace");
            workspace.setSlug("default");
            workspaces.insert(workspace);
            TestEntityFixtures.seedExistingWorkspacePrivacy(jdbc, workspace.getId());
        }
        actor = TestEntityFixtures.newUser(users, workspaces, workspace);
        recipient = TestEntityFixtures.newUser(users, workspaces, workspace);
        company = new Company();
        company.setWorkspaceId(workspace.getId());
        company.setName("Workflow membership source " + UUID.randomUUID());
        automation.runAs(workspace.getId(), actor, "member", () -> companies.insert(company));
    }

    @AfterEach
    void cleanup() {
        notifications.deleteAllForRecipient(workspace.getId(), recipient.getId());
        jdbc.update("DELETE FROM company WHERE workspace_id = ? AND id = ?", workspace.getId(), company.getId());
        workspaces.removeMember(workspace.getId(), recipient.getId());
        workspaces.removeMember(workspace.getId(), actor.getId());
        users.delete(recipient.getId());
        users.delete(actor.getId());
    }

    @Test
    void concurrentDepartureCannotCommitBetweenRecipientValidationAndNotificationWrite() throws Exception {
        CountDownLatch beforeWrite = new CountDownLatch(1);
        CountDownLatch allowWrite = new CountDownLatch(1);
        doAnswer(invocation -> {
            beforeWrite.countDown();
            assertTrue(allowWrite.await(15, TimeUnit.SECONDS));
            return invocation.callRealMethod();
        }).when(delivery).deliver(argThat((Notification notice) ->
            notice != null && notice.getRecipientId() == recipient.getId() && "rule".equals(notice.getType())));
        RuleAction action = new RuleAction();
        action.setType("notify");
        action.setTitle("Follow up");
        action.setTargetUserId(recipient.getId());
        RuleFireContext context = new RuleFireContext(workspace.getId(), 1, "company", company.getId(),
            actor.getId(), UUID.randomUUID().toString());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var sending = executor.submit(() -> automation.runAs(workspace.getId(), actor, "member",
                () -> actions.execute(action, context)));
            try {
                assertTrue(beforeWrite.await(15, TimeUnit.SECONDS));
                var removing = executor.submit(() -> {
                    TransactionTemplate transaction = new TransactionTemplate(transactions);
                    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
                    assertThrows(PessimisticLockingFailureException.class, () -> transaction.executeWithoutResult(status -> {
                        int previous = Objects.requireNonNull(jdbc.queryForObject(
                            "SELECT @@SESSION.innodb_lock_wait_timeout", Integer.class));
                        jdbc.execute("SET SESSION innodb_lock_wait_timeout = 1");
                        try {
                            workspaces.removeMember(workspace.getId(), recipient.getId());
                        } finally {
                            jdbc.execute("SET SESSION innodb_lock_wait_timeout = " + previous);
                        }
                    }));
                });
                removing.get(10, TimeUnit.SECONDS);
            } finally {
                allowWrite.countDown();
            }
            sending.get(15, TimeUnit.SECONDS);
        }
        assertNotNull(notifications.findByDedupe(workspace.getId(), recipient.getId(), context.notificationDedupeKey()));
        assertEquals(1, workspaces.removeMember(workspace.getId(), recipient.getId()));
    }
}
