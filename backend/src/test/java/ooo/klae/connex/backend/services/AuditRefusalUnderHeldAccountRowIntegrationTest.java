package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reproduces the stall behind #1986 against a real database and the real audit services.
 *
 * <p>The schedule and report-delete step-up gates re-check privilege while holding the actor's
 * {@code app_user} row {@code FOR SHARE}. An immediate refusal audit appends independently and takes
 * that row {@code FOR SHARE} again on another connection, which InnoDB queues behind an exclusive
 * request already waiting on the row. That writer waits on the gate, and the gate waits on the audit
 * in the application, so nothing moves until the writer's lock wait times out. The deferred refusal
 * is appended once the gate's transaction has completed. Each case commits rows, because
 * {@code audit_log} is append-only, and reads back only its own actor's refusals.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AuditRefusalUnderHeldAccountRowIntegrationTest extends AbstractServiceTest {
    private static final int WRITER_LOCK_WAIT_SECONDS = 5;
    private static final long WELL_INSIDE_WRITER_LOCK_WAIT_MS = 2_000;

    @Autowired private AuditService auditService;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSource dataSource;

    /**
     * The gate holds the account row shared while a writer is queued exclusively behind it, as a role
     * change or invite acceptance for the same account would be. The refusal must neither wait for
     * that writer's lock-wait timeout nor make the writer fail, and its row must land once.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aDeferredRefusalDoesNotQueueBehindAWriterWaitingOnTheHeldAccountRow(boolean scheduleDelete)
            throws Exception {
        int actorId = currentUser.getId();
        AtomicLong refusedAt = new AtomicLong();
        AtomicReference<Future<?>> writer = new AtomicReference<>();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                assertNotNull(userMapper.lockByIdForShare(actorId));
                long gateConnection = jdbcTemplate.queryForObject("SELECT CONNECTION_ID()", Long.class);
                writer.set(executor.submit(() -> {
                    updateAccountRow(actorId);
                    return null;
                }));
                awaitAccountRowWaitBehind(gateConnection, writer.get());
                refusedAt.set(System.nanoTime());
                if (scheduleDelete) {
                    auditService.deferScheduleDeleteStepUpRefusal();
                } else {
                    auditService.deferExportStepUpRefusal();
                }
                status.setRollbackOnly();
            });
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - refusedAt.get());

            assertTrue(elapsedMs < WELL_INSIDE_WRITER_LOCK_WAIT_MS,
                    "the refusal waited " + elapsedMs + " ms behind the writer queued on the account row");
            writer.get().get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertRefusalRecorded(actorId, scheduleDelete
                ? AuditService.SCHEDULE_DELETE_STEP_UP_SUMMARY
                : AuditService.EXPORT_STEP_UP_SUMMARY);
    }

    /**
     * The row is not written while the transaction that refused is still open, and is written once it
     * completes, whether it committed or rolled back.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aDeferredRefusalIsAppendedOnlyOnceItsTransactionCompletes(boolean rollBack) {
        int actorId = currentUser.getId();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            auditService.deferExportStepUpRefusal();
            assertEquals(0, refusals(actorId).size(), "the refusal was appended before completion");
            if (rollBack) {
                status.setRollbackOnly();
            }
        });

        assertRefusalRecorded(actorId, AuditService.EXPORT_STEP_UP_SUMMARY);
    }

    /** Without a transaction there is nothing to wait for, so the refusal is appended at once. */
    @Test
    void withoutATransactionTheDeferredRefusalIsAppendedImmediately() {
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive());

        auditService.deferScheduleDeleteStepUpRefusal();

        assertRefusalRecorded(currentUser.getId(), AuditService.SCHEDULE_DELETE_STEP_UP_SUMMARY);
    }

    /**
     * Deletes the committed user {@code AbstractServiceTest} made an owner of the shared default
     * workspace, so later classes in the same schema do not count it; its audit rows keep their
     * signed {@code integrity_actor_id}.
     */
    @AfterEach
    void deleteCommittedUser() {
        if (currentUser != null) {
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", currentUser.getId());
        }
    }

    private void assertRefusalRecorded(int actorId, String summary) {
        List<Map<String, Object>> rows = refusals(actorId);
        assertEquals(1, rows.size(), "exactly one refusal row must be recorded for the actor");
        Map<String, Object> row = rows.getFirst();
        assertEquals("user", row.get("entity_type"));
        assertEquals(actorId, ((Number) row.get("actor_id")).intValue());
        assertEquals("failure", row.get("outcome"));
        assertEquals(summary, row.get("summary"));
        assertTrue(String.valueOf(row.get("context")).contains(AuditService.EXPORT_STEP_UP_SERVICE_BOUNDARY_REASON));
        assertNull(row.get("workspace_id"));
        assertNull(row.get("org_id"));
        assertEquals("system", row.get("chain_scope_type"));
        assertEquals(0, ((Number) row.get("chain_scope_id")).intValue());
    }

    private List<Map<String, Object>> refusals(int actorId) {
        return jdbcTemplate.queryForList("""
                SELECT entity_type, actor_id, outcome, summary, context, workspace_id, org_id,
                       chain_scope_type, chain_scope_id
                FROM audit_log
                WHERE action = ? AND entity_type = 'user' AND entity_id = ?
                """, AuditService.EXPORT_STEP_UP_ACTION, actorId);
    }

    /**
     * Takes the account row exclusively on its own connection and commits at once, as an account
     * mutation would, with a short lock wait so a stalled gate shows up as this writer timing out.
     */
    private void updateAccountRow(int actorId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (Statement session = connection.createStatement()) {
                session.execute("SET SESSION innodb_lock_wait_timeout = " + WRITER_LOCK_WAIT_SECONDS);
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE app_user SET timezone = timezone WHERE id = ?")) {
                    update.setInt(1, actorId);
                    assertEquals(1, update.executeUpdate());
                }
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                try (Statement reset = connection.createStatement()) {
                    reset.execute("SET SESSION innodb_lock_wait_timeout = DEFAULT");
                }
                connection.setAutoCommit(autoCommit);
            }
        }
    }

    /**
     * Waits until the writer's request on {@code app_user} is queued behind the gate's connection, and
     * fails at once with the writer's outcome if it finishes first.
     */
    private void awaitAccountRowWaitBehind(long gateConnection, Future<?> waiter) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            if (waiter.isDone()) {
                try {
                    waiter.get();
                    throw new AssertionError("The writer completed without waiting behind the gate");
                } catch (ExecutionException failure) {
                    throw new AssertionError("The writer failed before waiting behind the gate",
                            failure.getCause());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Interrupted while waiting for the writer", interrupted);
                }
            }
            Integer waits = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM performance_schema.data_lock_waits waits
                    JOIN performance_schema.data_locks requested
                      ON requested.ENGINE_LOCK_ID = waits.REQUESTING_ENGINE_LOCK_ID
                    JOIN performance_schema.threads blocking
                      ON blocking.THREAD_ID = waits.BLOCKING_THREAD_ID
                    WHERE requested.OBJECT_SCHEMA = DATABASE() AND requested.OBJECT_NAME = 'app_user'
                      AND blocking.PROCESSLIST_ID = ?
                    """, Integer.class, gateConnection);
            if (waits != null && waits > 0) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for the writer", interrupted);
            }
        }
        throw new AssertionError("The writer never queued behind the gate's account row");
    }
}
