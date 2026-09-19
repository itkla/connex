package ooo.klae.connex.backend.ai.lease;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * Two instances racing to claim one expired lease.
 *
 * <p>The latch keys on the contention point the claim actually has: the {@code FOR UPDATE} taken by
 * {@link AiRunLeaseService#acquireInCurrentTransaction}. The winner holds that row lock until it
 * commits, so the loser's own lock attempt blocks, then observes a live lease at a bumped epoch and
 * is refused. Removing the row lock would let both claimants read the same expired row and both
 * take it over.
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
                        AiRunLease claimed = leaseService.acquireInCurrentTransaction(key);
                        locked.countDown();
                        awaitQuietly(commit);
                        return claimed;
                    })));
            assertTrue(locked.await(30, TimeUnit.SECONDS), "The winner never took the row lock");
            Future<AiRunLease> loser = claimants.submit(() -> inTenant(() ->
                    transactions.execute(status -> leaseService.acquireInCurrentTransaction(key))));
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
