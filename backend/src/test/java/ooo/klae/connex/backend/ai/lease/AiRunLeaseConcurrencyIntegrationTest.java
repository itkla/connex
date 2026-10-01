package ooo.klae.connex.backend.ai.lease;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.exceptions.ConflictException;

/**
 * Instances racing to claim one lease.
 *
 * <p>The database probe keys on the contention point the claim actually has: the {@code FOR UPDATE} taken by
 * {@link AiRunLeaseService#acquireInCurrentTransaction}. The winner holds that row lock until it
 * commits, so the loser's own lock attempt blocks, then observes a live lease at a bumped epoch and
 * is refused.
 *
 * <p>What the row lock actually buys is stated precisely here rather than overclaimed. Two
 * claimants that read the same expired row concurrently would <em>not</em> both win even without
 * it: at READ COMMITTED the blocked takeover re-evaluates its
 * {@code owner IS NULL OR expires_at < CURRENT_TIMESTAMP(6)} predicate against the winner's
 * committed row and matches nothing. The lock buys the agreement between the epoch this method
 * returns and the epoch MySQL persists — {@code existing.getEpoch() + 1} is only the truth while no
 * one else can move the row between the read and the update. Without it, a claimant whose read went
 * stale returns a token one epoch behind the row, and every renewal with that token reports LOST
 * while the run keeps working. {@link
 * #aClaimantWhoseReadWentStaleStillReturnsTheEpochTheDatabasePersisted()} is the case that fails
 * when the lock is removed.
 */
class AiRunLeaseConcurrencyIntegrationTest extends AbstractAiRunLeaseIntegrationTest {

    @Test
    void exactlyOneClaimantWinsAnExpiredLeaseAndTheEpochAdvancesOnce() throws Exception {
        AiRunLeaseKey key = key(AiRunLeaseSubject.CHAT_TURN, 9001L);
        AiRunLease stale = acquire(key);
        expire(key);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        ExecutorService claimants = Executors.newFixedThreadPool(2);

        try {
            Future<AiRunLease> winner = claimants.submit(() -> inTenant(() ->
                    transactions.execute(status -> {
                        AiRunLease claimed = leaseService.acquireInCurrentTransaction(key, freshGuard());
                        locked.countDown();
                        awaitQuietly(commit);
                        return claimed;
                    })));
            assertTrue(locked.await(30, TimeUnit.SECONDS), "The winner never took the row lock");
            CompletableFuture<Long> contenderConnection = new CompletableFuture<>();
            Future<AiRunLease> loser = claimants.submit(() -> inTenant(() ->
                    transactions.execute(status -> {
                        contenderConnection.complete(
                                jdbcTemplate.queryForObject("SELECT CONNECTION_ID()", Long.class));
                        return leaseService.acquireInCurrentTransaction(key, freshGuard());
                    })));
            awaitLeaseRowLock(contenderConnection.get(30, TimeUnit.SECONDS), key, loser);
            commit.countDown();

            AiRunLease claimed = winner.get(60, TimeUnit.SECONDS);
            ExecutionException refusal =
                    assertThrows(ExecutionException.class, () -> loser.get(60, TimeUnit.SECONDS));

            assertEquals(stale.epoch() + 1L, claimed.epoch());
            assertInstanceOf(ConflictException.class, refusal.getCause());
        } finally {
            commit.countDown();
            claimants.shutdownNow();
            assertTrue(claimants.awaitTermination(10, TimeUnit.SECONDS),
                    "Lease claimants did not terminate after barrier release");
        }

        Map<String, Object> row = leaseRow(key);
        assertEquals(leaseIdentity.owner(), row.get("owner"));
        assertEquals(2L, ((Number) row.get("epoch")).longValue());
        assertEquals(1, leaseCount());
    }

    /**
     * A competing instance runs a whole claim-and-release cycle while a late claimant's read is
     * outstanding. Under the row lock the late claimant cannot read until that cycle commits, so
     * the epoch it returns is the epoch the database holds. Without the lock it reads the
     * pre-cycle epoch, its blocked update then applies to the post-cycle row, and it walks away
     * with a token one epoch behind — which every subsequent renewal reports as a lost lease.
     */
    @Test
    void aClaimantWhoseReadWentStaleStillReturnsTheEpochTheDatabasePersisted() throws Exception {
        AiRunLeaseKey key = key(AiRunLeaseSubject.CHAT_TURN, 9002L);
        acquire(key);
        expire(key);
        CountDownLatch cycled = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        ExecutorService claimants = Executors.newFixedThreadPool(2);

        try {
            Future<?> competitor = claimants.submit(() -> inTenant(() ->
                    transactions.execute(status -> {
                        leaseService.acquireInCurrentTransaction(key, freshGuard());
                        leaseService.releaseHeldInCurrentTransaction(key);
                        cycled.countDown();
                        awaitQuietly(commit);
                        return null;
                    })));
            assertTrue(cycled.await(30, TimeUnit.SECONDS), "The competitor never claimed the row");
            CompletableFuture<Long> contenderConnection = new CompletableFuture<>();
            Future<AiRunLease> late = claimants.submit(() -> inTenant(() ->
                    transactions.execute(status -> {
                        contenderConnection.complete(
                                jdbcTemplate.queryForObject("SELECT CONNECTION_ID()", Long.class));
                        return leaseService.acquireInCurrentTransaction(key, freshGuard());
                    })));
            awaitLeaseRowLock(contenderConnection.get(30, TimeUnit.SECONDS), key, late);
            commit.countDown();
            competitor.get(60, TimeUnit.SECONDS);

            AiRunLease claimed = late.get(60, TimeUnit.SECONDS);

            assertEquals(3L, claimed.epoch());
            assertEquals(3L, ((Number) leaseRow(key).get("epoch")).longValue());
            assertEquals(AiRunLeaseOutcome.HELD, leaseService.renew(claimed));
        } finally {
            commit.countDown();
            claimants.shutdownNow();
            assertTrue(claimants.awaitTermination(10, TimeUnit.SECONDS),
                    "Lease claimants did not terminate after barrier release");
        }
    }

    /**
     * Two first claimants of a subject that has never been leased. An absent primary key takes no
     * lock at READ COMMITTED, so both can reach the insert and the loser is refused by the primary
     * key itself — or by the deadlock its lock wait resolves into. Either way the caller must see
     * the conflict its contract describes rather than a raw data-access failure
     * {@code GlobalExceptionHandler} does not map.
     *
     * <p>The honest bound of this drill: the interleaving that reaches the insert-versus-insert
     * window is timing-dependent, so the run repeats over fresh keys rather than asserting that a
     * given attempt took that branch. {@code AiRunLeaseServiceTest} pins the translation itself
     * deterministically.
     */
    @Test
    void racingFirstClaimsOfAKeyWithNoRowYieldOneWinnerAndConflictsForTheRest() throws Exception {
        ExecutorService claimants = Executors.newFixedThreadPool(4);

        try {
            for (long subjectId = 9100L; subjectId < 9108L; subjectId++) {
                AiRunLeaseKey key = key(AiRunLeaseSubject.CHAT_TURN, subjectId);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<AiRunLease>> attempts = new ArrayList<>();
                for (int claimant = 0; claimant < 4; claimant++) {
                    attempts.add(claimants.submit(() -> inTenant(() -> {
                        awaitQuietly(start);
                        return transactions.execute(
                                status -> leaseService.acquireInCurrentTransaction(key, freshGuard()));
                    })));
                }
                start.countDown();

                int winners = 0;
                for (Future<AiRunLease> attempt : attempts) {
                    try {
                        assertEquals(1L, attempt.get(60, TimeUnit.SECONDS).epoch());
                        winners++;
                    } catch (ExecutionException refusal) {
                        assertInstanceOf(
                                ConflictException.class,
                                refusal.getCause(),
                                "A losing first claimant must be refused as a conflict");
                    }
                }

                assertEquals(1, winners, "Exactly one first claimant may hold subject " + subjectId);
                assertEquals(1L, ((Number) leaseRow(key).get("epoch")).longValue());
            }
        } finally {
            claimants.shutdownNow();
            assertTrue(claimants.awaitTermination(10, TimeUnit.SECONDS),
                    "Lease claimants did not terminate after barrier release");
        }
    }

    private void awaitLeaseRowLock(long connectionId, AiRunLeaseKey key, Future<?> contender) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        String primaryKey = key.workspaceId() + ", '" + key.subject().wireKey() + "', " + key.subjectId();
        while (System.nanoTime() < deadline) {
            assertFalse(contender.isDone(), "Claimant completed before contending on the lease read");
            Integer waiting = jdbcTemplate.queryForObject(
                    """
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
                      AND waiting_thread.PROCESSLIST_INFO LIKE '%FOR UPDATE%'
                      AND requested_lock.OBJECT_SCHEMA = DATABASE()
                      AND requested_lock.OBJECT_NAME = 'ai_run_lease'
                      AND requested_lock.INDEX_NAME = 'PRIMARY'
                      AND requested_lock.LOCK_TYPE = 'RECORD'
                      AND requested_lock.LOCK_MODE LIKE 'X%'
                      AND requested_lock.LOCK_STATUS = 'WAITING'
                      AND requested_lock.LOCK_DATA = ?
                      AND blocking_lock.OBJECT_SCHEMA = requested_lock.OBJECT_SCHEMA
                      AND blocking_lock.OBJECT_NAME = requested_lock.OBJECT_NAME
                      AND blocking_lock.INDEX_NAME = requested_lock.INDEX_NAME
                      AND blocking_lock.LOCK_TYPE = requested_lock.LOCK_TYPE
                      AND blocking_lock.LOCK_STATUS = 'GRANTED'
                      AND blocking_lock.LOCK_DATA = requested_lock.LOCK_DATA
                    """,
                    Integer.class, connectionId, primaryKey);
            if (waiting != null && waiting > 0) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        throw new AssertionError("Claimant never waited on the lease PRIMARY key " + primaryKey);
    }

    private <T> T inTenant(Supplier<T> work) {
        pinTenant();
        try {
            return work.get();
        } finally {
            tenantContext.clear();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Claim latch never opened");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while holding the lease row lock");
        }
    }
}
