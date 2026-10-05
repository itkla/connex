package ooo.klae.connex.backend.support;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import org.springframework.jdbc.core.JdbcTemplate;

/** Observes a transaction waiting on an exact deal record in the current MySQL schema. */
public final class MySqlLockWaitTestSupport {
    private MySqlLockWaitTestSupport() {
    }

    /** Requires a connection-specific exclusive record wait backed by a granted lock on that row. */
    public static void awaitDealRowWait(
            JdbcTemplate jdbcTemplate, long connectionId, int workspaceId, int dealId) {
        if (connectionId <= 0) {
            throw new AssertionError("The waiting transaction did not expose its connection id");
        }
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadlineNanos) {
            Integer waiting = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM performance_schema.data_lock_waits lock_wait
                JOIN performance_schema.data_locks requested_lock
                  ON requested_lock.ENGINE = lock_wait.ENGINE
                 AND requested_lock.ENGINE_LOCK_ID = lock_wait.REQUESTING_ENGINE_LOCK_ID
                JOIN performance_schema.data_locks blocking_lock
                  ON blocking_lock.ENGINE = lock_wait.ENGINE
                 AND blocking_lock.ENGINE_LOCK_ID = lock_wait.BLOCKING_ENGINE_LOCK_ID
                JOIN performance_schema.threads waiting_thread
                  ON waiting_thread.THREAD_ID = lock_wait.REQUESTING_THREAD_ID
                WHERE waiting_thread.PROCESSLIST_ID = ?
                  AND requested_lock.OBJECT_SCHEMA = DATABASE()
                  AND requested_lock.OBJECT_NAME = 'deal'
                  AND ((requested_lock.INDEX_NAME = 'PRIMARY'
                        AND requested_lock.LOCK_DATA = CAST(? AS CHAR))
                    OR (requested_lock.INDEX_NAME = 'uq_deal_workspace_id'
                        AND requested_lock.LOCK_DATA = ?))
                  AND requested_lock.LOCK_TYPE = 'RECORD'
                  AND requested_lock.LOCK_MODE LIKE 'X%'
                  AND requested_lock.LOCK_STATUS = 'WAITING'
                  AND blocking_lock.OBJECT_SCHEMA = requested_lock.OBJECT_SCHEMA
                  AND blocking_lock.OBJECT_NAME = requested_lock.OBJECT_NAME
                  AND blocking_lock.INDEX_NAME = requested_lock.INDEX_NAME
                  AND blocking_lock.LOCK_TYPE = requested_lock.LOCK_TYPE
                  AND blocking_lock.LOCK_STATUS = 'GRANTED'
                  AND blocking_lock.LOCK_DATA = requested_lock.LOCK_DATA
                """, Integer.class, connectionId, dealId, workspaceId + ", " + dealId);
            if (waiting != null && waiting > 0) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        throw new AssertionError("Connection " + connectionId + " did not block on deal " + dealId
            + " in workspace " + workspaceId);
    }
}
