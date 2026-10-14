package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import org.mybatis.spring.SqlSessionTemplate;
import static org.mockito.Mockito.inOrder;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.PersonLifecyclePass;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mappers.AssignmentSweepMapper;
import ooo.klae.connex.backend.mappers.AssignmentSweepMapper.Cursor;
import ooo.klae.connex.backend.mappers.AssignmentSweepMapper.Kind;
import ooo.klae.connex.backend.mappers.AssignmentSweepMapper.Reference;
import ooo.klae.connex.backend.mappers.PersonLifecyclePassMapper;
import ooo.klae.connex.backend.support.AssignmentSweepFixtures;
import ooo.klae.connex.backend.support.MySqlLockWaitProbe;

/** Real post-erasure window, cascade barrier, and parent-before-child lock races. */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UserAssignmentSweepConcurrencyIntegrationTest extends CampaignRealDbTestSupport {
    @Autowired private UserAccountCatalogOffboardingService sweeps;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private AssignmentSweepMapper mapper;
    @Autowired private PersonLifecyclePassMapper passes;
    @Autowired private SqlSessionTemplate assignmentSqlSession;

    @AfterEach
    void cleanAssignmentFixtures() {
        AssignmentSweepFixtures.removeWorkflow(jdbc, workspace.getId());
        jdbc.update("DELETE FROM task WHERE workspace_id = ?", workspace.getId());
        jdbc.update("DELETE FROM deal_collaborator WHERE workspace_id = ?", workspace.getId());
        jdbc.update("DELETE FROM deal WHERE workspace_id = ?", workspace.getId());
        jdbc.update("DELETE FROM stage WHERE workspace_id = ?", workspace.getId());
        jdbc.update("DELETE FROM pipeline WHERE workspace_id = ?", workspace.getId());
        jdbc.update("DELETE FROM person_lifecycle_pass WHERE workspace_id = ?", workspace.getId());
    }

    @Test
    void writesAfterInitialErasureCommitSurviveUntilTheCascadeAndThenAllEightKindsAreSwept() throws Exception {
        User target = newUser();
        int userId = target.getId();
        int ws = workspace.getId();
        var company = newCompany();
        var person = newPerson(company);
        var pipeline = newPipeline();
        var deal = newDeal(pipeline, newStage(pipeline, 0), company);
        jdbc.update("UPDATE company SET owner_id = ? WHERE id = ?", userId, company.getId());
        sweeps.eraseReferences(userId);
        assertNull(jdbc.queryForObject("SELECT owner_id FROM company WHERE id = ?", Integer.class, company.getId()));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection deletion = dataSource.getConnection(); Connection observer = dataSource.getConnection()) {
            long connection = connectionId(deletion);
            JdbcTemplate observation = new JdbcTemplate(new SingleConnectionDataSource(observer, true));
            Future<?>[] deleting = new Future<?>[1];
            transaction().executeWithoutResult(status -> {
                assertEquals(userId, workspaceMapper.lockActiveMembership(ws, userId));
                jdbc.update("UPDATE company SET owner_id = ? WHERE id = ?", userId, company.getId());
                jdbc.update("UPDATE person SET owner_id = ? WHERE id = ?", userId, person.getId());
                jdbc.update("UPDATE deal SET owner_id = ? WHERE id = ?", userId, deal.getId());
                jdbc.update("INSERT INTO deal_collaborator (workspace_id, deal_id, user_id) VALUES (?, ?, ?)", ws, deal.getId(), userId);
                jdbc.update("INSERT INTO task (workspace_id, description, assigned_to_id) VALUES (?, 'window', ?)", ws, userId);
                jdbc.update("INSERT INTO campaign (workspace_id, name, type, owner_user_id) VALUES (?, 'window', 'event', ?)", ws, userId);
                jdbc.update("INSERT INTO person_lifecycle_pass (workspace_id, person_id, entered_at, owner_id) VALUES (?, ?, CURRENT_TIMESTAMP, ?)",
                    ws, person.getId(), userId);
                AssignmentSweepFixtures.intervention(jdbc, ws, person.getId(), userId);
                deleting[0] = executor.submit(() -> {
                    try (var statement = deletion.prepareStatement("DELETE FROM app_user WHERE id = ?")) {
                        statement.setInt(1, userId);
                        assertEquals(1, statement.executeUpdate());
                    }
                    return null;
                });
                MySqlLockWaitProbe.awaitExclusiveRecordLock(observation, connection, "workspace_member", ws + ", " + userId);
                assertFalse(deleting[0].isDone());
            });
            deleting[0].get(30, TimeUnit.SECONDS);
            for (Kind kind : Kind.values()) {
                assertEquals(1, mapper.findReferencesAnywhere(kind, userId, Cursor.beginning(), mapper.findWatermark(kind), 200).size(), kind.name());
            }
            sweeps.sweepAssignmentsAfterDeletion(userId);
            assertNoReferences(userId);
            sweeps.sweepAssignmentsAfterDeletion(userId);
            assertNoReferences(userId);
        } finally {
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lifecycleInsertAndMilestoneSnapshotCommitBeforeTheSweepClearsPersonAndPass(boolean milestone) throws Exception {
        User target = newUser();
        var person = newPerson(newCompany());
        int ws = workspace.getId();
        jdbc.update("UPDATE person SET owner_id = ? WHERE id = ?", target.getId(), person.getId());
        userMapper.delete(target.getId());
        if (milestone) {
            jdbc.update("INSERT INTO person_lifecycle_pass (workspace_id, person_id, entered_at, owner_id) VALUES (?, ?, CURRENT_TIMESTAMP, NULL)",
                ws, person.getId());
        }
        CountDownLatch sweepAtPerson = new CountDownLatch(1);
        AtomicLong sweepConnection = new AtomicLong();
        doAnswer(call -> {
            sweepConnection.set(connectionId(DataSourceUtils.getConnection(dataSource)));
            sweepAtPerson.countDown();
            return assignmentSqlSession.getMapper(AssignmentSweepMapper.class)
                .lockParent(call.getArgument(0), call.getArgument(1));
        }).when(mapper).lockParent(eq(Kind.PERSON), any());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection observer = dataSource.getConnection()) {
            JdbcTemplate observation = new JdbcTemplate(new SingleConnectionDataSource(observer, true));
            Future<?>[] cleaning = new Future<?>[1];
            transaction().executeWithoutResult(status -> {
                assertTrue(personMapper.getOwnedPersonByIdForUpdate(ws, person.getId()) != null);
                if (milestone) {
                    assertEquals(1, passes.stampMilestone(ws, person.getId(), "QUALIFIED", java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)));
                } else {
                    PersonLifecyclePass pass = new PersonLifecyclePass();
                    pass.setWorkspaceId(ws);
                    pass.setPersonId(person.getId());
                    pass.setEnteredAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
                    passes.insert(pass);
                }
                assertEquals(target.getId(), jdbc.queryForObject("SELECT owner_id FROM person_lifecycle_pass WHERE workspace_id = ? AND person_id = ?",
                    Integer.class, ws, person.getId()));
                cleaning[0] = executor.submit(() -> {
                    sweeps.sweepAssignmentsAfterDeletion(target.getId());
                    return null;
                });
                try {
                    assertTrue(sweepAtPerson.await(30, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    throw new IllegalStateException(interrupted);
                }
                MySqlLockWaitProbe.awaitExclusiveRecordLock(observation, sweepConnection.get(), "person", String.valueOf(person.getId()));
            });
            cleaning[0].get(30, TimeUnit.SECONDS);
            assertNoReferences(target.getId());
            var order = inOrder(mapper);
            order.verify(mapper).lockParent(eq(Kind.PERSON), any());
            order.verify(mapper).clearOwner(eq(Kind.PERSON), any());
            order.verify(mapper).clearPersonPassOwners(any());
        } finally {
            org.mockito.Mockito.reset(mapper);
            executor.shutdownNow();
        }
    }

    @Test
    void collaboratorSweepWaitsOnReplacementParentBeforeTouchingSeats() throws Exception {
        User target = newUser();
        var company = newCompany();
        var pipeline = newPipeline();
        var deal = newDeal(pipeline, newStage(pipeline, 0), company);
        int ws = workspace.getId();
        jdbc.update("INSERT INTO deal_collaborator (workspace_id, deal_id, user_id) VALUES (?, ?, ?)", ws, deal.getId(), target.getId());
        userMapper.delete(target.getId());
        CountDownLatch requestedParent = new CountDownLatch(1);
        AtomicLong sweepConnection = new AtomicLong();
        doAnswer(call -> {
            sweepConnection.set(connectionId(DataSourceUtils.getConnection(dataSource)));
            requestedParent.countDown();
            return assignmentSqlSession.getMapper(AssignmentSweepMapper.class)
                .lockParent(call.getArgument(0), call.getArgument(1));
        }).when(mapper).lockParent(eq(Kind.COLLABORATOR), any());
        doAnswer(call -> {
            try (Connection probe = dataSource.getConnection(); var statement = probe.prepareStatement(
                    "SELECT id FROM deal FORCE INDEX (PRIMARY) WHERE id = ? FOR UPDATE NOWAIT")) {
                statement.setInt(1, deal.getId());
                SQLException busy = assertThrows(SQLException.class, statement::executeQuery);
                assertEquals(3572, busy.getErrorCode());
                assertEquals("HY000", busy.getSQLState());
            }
            return assignmentSqlSession.getMapper(AssignmentSweepMapper.class)
                .removeCollaborator(call.getArgument(0));
        }).when(mapper).removeCollaborator(any());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection observer = dataSource.getConnection()) {
            JdbcTemplate observation = new JdbcTemplate(new SingleConnectionDataSource(observer, true));
            Future<?>[] cleaning = new Future<?>[1];
            transaction().executeWithoutResult(status -> {
                assertTrue(dealMapper.getDealByPrimaryKeyForUpdate(ws, deal.getId()) != null);
                cleaning[0] = executor.submit(() -> {
                    sweeps.sweepAssignmentsAfterDeletion(target.getId());
                    return null;
                });
                try {
                    assertTrue(requestedParent.await(30, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    throw new IllegalStateException(interrupted);
                }
                MySqlLockWaitProbe.awaitExclusiveRecordLock(observation, sweepConnection.get(), "deal", String.valueOf(deal.getId()));
                dealMapper.getCollaboratorIdsForUpdate(ws, deal.getId());
            });
            cleaning[0].get(30, TimeUnit.SECONDS);
            assertNoReferences(target.getId());
        } finally {
            org.mockito.Mockito.reset(mapper);
            executor.shutdownNow();
        }
    }

    private void assertNoReferences(int userId) {
        for (Kind kind : Kind.values()) {
            Cursor watermark = mapper.findWatermark(kind);
            if (watermark != null) {
                assertTrue(mapper.findReferencesAnywhere(kind, userId, Cursor.beginning(), watermark, 200).isEmpty(), kind.name());
            }
        }
    }

    private TransactionTemplate transaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction;
    }

    private static long connectionId(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT CONNECTION_ID()")) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }
}
