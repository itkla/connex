package ooo.klae.connex.backend.ai.lease;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.exceptions.ConflictException;

/**
 * Instances racing to claim one lease.
 *
 * <p>The latch keys on the contention point the claim actually has: the {@code FOR UPDATE} taken by
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
            Future<AiRunLease> loser = claimants.submit(() -> inTenant(() ->
                    transactions.execute(status ->
                            leaseService.acquireInCurrentTransaction(key, freshGuard()))));
            Thread.sleep(500L);
            commit.countDown();

            AiRunLease claimed = winner.get(60, TimeUnit.SECONDS);
            ExecutionException refusal =
                    assertThrows(ExecutionException.class, () -> loser.get(60, TimeUnit.SECONDS));

            assertEquals(stale.epoch() + 1L, claimed.epoch());
            assertInstanceOf(ConflictException.class, refusal.getCause());
        } finally {
            commit.countDown();
            claimants.shutdownNow();
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
            Future<AiRunLease> late = claimants.submit(() -> inTenant(() ->
                    transactions.execute(status ->
                            leaseService.acquireInCurrentTransaction(key, freshGuard()))));
            Thread.sleep(500L);
            commit.countDown();
            competitor.get(60, TimeUnit.SECONDS);

            AiRunLease claimed = late.get(60, TimeUnit.SECONDS);

            assertEquals(3L, claimed.epoch());
            assertEquals(3L, ((Number) leaseRow(key).get("epoch")).longValue());
            assertEquals(AiRunLeaseOutcome.HELD, leaseService.renew(claimed));
        } finally {
            commit.countDown();
            claimants.shutdownNow();
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
        }
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
