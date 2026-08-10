package ooo.klae.connex.backend.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

class AiRestrictionEpochTest {

    @Test
    void bumpAdvancesWorkspaceEpoch() {
        AiRestrictionEpoch epoch = new AiRestrictionEpoch();
        long before = epoch.current(7);

        epoch.bump(7);

        assertTrue(epoch.current(7) > before);
    }

    @Test
    void workspaceEpochsAreIndependent() {
        AiRestrictionEpoch epoch = new AiRestrictionEpoch();
        long otherWorkspace = epoch.current(8);

        epoch.bump(7);

        assertEquals(otherWorkspace, epoch.current(8));
    }

    @Test
    void workspaceStripesRemainBoundedAndCollisionInvalidatesStaleSnapshots() {
        AiRestrictionEpoch epoch = new AiRestrictionEpoch(3);
        epoch.bump(1);
        epoch.bump(2);
        epoch.bump(3);
        long evictedSnapshot = epoch.current(1);

        epoch.bump(4);

        assertEquals(3, epoch.usedWorkspaceStripeCount());
        assertNotEquals(evictedSnapshot, epoch.current(1));
    }

    @Test
    void concurrentBumpsAndReadsRemainMonotonicAndComplete() {
        AiRestrictionEpoch epoch = new AiRestrictionEpoch();
        int bumpWorkers = 4;
        int readWorkers = 4;
        int iterations = 500;
        try (ExecutorService executor = Executors.newFixedThreadPool(bumpWorkers + readWorkers)) {
            List<CompletableFuture<Void>> work = new ArrayList<>();
            for (int worker = 0; worker < bumpWorkers; worker++) {
                work.add(CompletableFuture.runAsync(() -> {
                    for (int iteration = 0; iteration < iterations; iteration++) {
                        epoch.bump(7);
                    }
                }, executor));
            }
            for (int worker = 0; worker < readWorkers; worker++) {
                work.add(CompletableFuture.runAsync(() -> {
                    long previous = 0;
                    for (int iteration = 0; iteration < iterations; iteration++) {
                        long current = epoch.current(7);
                        if (current < previous) {
                            throw new AssertionError("AI restriction epoch moved backwards");
                        }
                        previous = current;
                    }
                }, executor));
            }
            CompletableFuture.allOf(work.toArray(CompletableFuture[]::new)).join();
        }

        assertEquals((long) bumpWorkers * iterations, epoch.current(7));
        assertEquals(1, epoch.usedWorkspaceStripeCount());
    }

    @Test
    void transactionalBumpRetainsFenceUntilTransactionCompletion() throws Exception {
        AiRestrictionEpoch epoch = new AiRestrictionEpoch();
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            epoch.bump(7);
            CountDownLatch readStarted = new CountDownLatch(1);
            CompletableFuture<Long> blockedRead = CompletableFuture.supplyAsync(() -> {
                readStarted.countDown();
                return epoch.current(7);
            }, executor);

            assertTrue(readStarted.await(1, TimeUnit.SECONDS));
            assertFalse(blockedRead.isDone());
            TransactionSynchronizationUtils.triggerAfterCompletion(
                    TransactionSynchronization.STATUS_COMMITTED);
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);

            assertEquals(epoch.current(7), blockedRead.get(1, TimeUnit.SECONDS));
        } finally {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.clearSynchronization();
            }
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void transactionalFenceDoesNotBlockAnotherWorkspaceStripe() throws Exception {
        AiRestrictionEpoch epoch = new AiRestrictionEpoch();
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            epoch.bump(7);

            CompletableFuture<Long> otherWorkspace = CompletableFuture.supplyAsync(
                    () -> epoch.current(8), executor);

            assertEquals(0, otherWorkspace.get(1, TimeUnit.SECONDS));
            TransactionSynchronizationUtils.triggerAfterCompletion(
                    TransactionSynchronization.STATUS_COMMITTED);
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        } finally {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.clearSynchronization();
            }
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void transactionalReadFenceBlocksRestrictionBumpThroughCommit() throws Exception {
        AiRestrictionEpoch epoch = new AiRestrictionEpoch();
        long expectedEpoch = epoch.current(7);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            assertTrue(epoch.retainReadFenceUntilTransactionCompletionIfCurrent(
                    7, expectedEpoch));
            CountDownLatch bumpStarted = new CountDownLatch(1);
            CompletableFuture<Void> blockedBump = CompletableFuture.runAsync(() -> {
                bumpStarted.countDown();
                epoch.bump(7);
            }, executor);

            assertTrue(bumpStarted.await(1, TimeUnit.SECONDS));
            assertFalse(blockedBump.isDone());
            TransactionSynchronizationUtils.triggerAfterCompletion(
                    TransactionSynchronization.STATUS_COMMITTED);
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);

            blockedBump.get(1, TimeUnit.SECONDS);
            assertNotEquals(expectedEpoch, epoch.current(7));
        } finally {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.clearSynchronization();
            }
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void transactionalReadFenceRejectsAStaleEpoch() {
        AiRestrictionEpoch epoch = new AiRestrictionEpoch();
        long expectedEpoch = epoch.current(7);
        epoch.bump(7);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertFalse(epoch.retainReadFenceUntilTransactionCompletionIfCurrent(
                    7, expectedEpoch));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void currentEpochActionBlocksAConcurrentRestrictionBump() throws Exception {
        AiRestrictionEpoch epoch = new AiRestrictionEpoch();
        long expectedEpoch = epoch.current(7);
        CountDownLatch actionStarted = new CountDownLatch(1);
        CountDownLatch releaseAction = new CountDownLatch(1);
        CountDownLatch bumpStarted = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<Boolean> action = CompletableFuture.supplyAsync(
                    () -> epoch.runIfCurrent(7, expectedEpoch, () -> {
                        actionStarted.countDown();
                        await(releaseAction);
                    }),
                    executor);
            assertTrue(actionStarted.await(1, TimeUnit.SECONDS));
            CompletableFuture<Void> bump = CompletableFuture.runAsync(() -> {
                bumpStarted.countDown();
                epoch.bump(7);
            }, executor);
            assertTrue(bumpStarted.await(1, TimeUnit.SECONDS));
            assertFalse(bump.isDone());

            releaseAction.countDown();

            assertTrue(action.get(1, TimeUnit.SECONDS));
            bump.get(1, TimeUnit.SECONDS);
            assertFalse(epoch.runIfCurrent(7, expectedEpoch, () -> {
                throw new AssertionError("Stale epoch action must not execute");
            }));
        }
    }

    @Test
    void providerFenceReleasesBeforeContributorPersistenceToPreserveLockOrder() throws Exception {
        AiRestrictionEpoch epoch = new AiRestrictionEpoch();
        long expectedEpoch = epoch.current(7);
        ReentrantLock contributorRow = new ReentrantLock(true);
        CountDownLatch providerStarted = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        CountDownLatch restrictionLockedContributor = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<Void> generation = CompletableFuture.runAsync(
                    () -> epoch.runWithExpectedEgressEpoch(7, expectedEpoch, () -> {
                        epoch.invokeAtEgress(7, () -> {
                            providerStarted.countDown();
                            await(releaseProvider);
                            return "generated";
                        });
                        contributorRow.lock();
                        try {
                            assertTrue(epoch.current(7) > expectedEpoch);
                        } finally {
                            contributorRow.unlock();
                        }
                    }),
                    executor);
            assertTrue(providerStarted.await(1, TimeUnit.SECONDS));
            CompletableFuture<Void> restriction = CompletableFuture.runAsync(() -> {
                contributorRow.lock();
                try {
                    restrictionLockedContributor.countDown();
                    epoch.bump(7);
                } finally {
                    contributorRow.unlock();
                }
            }, executor);
            assertTrue(restrictionLockedContributor.await(1, TimeUnit.SECONDS));

            releaseProvider.countDown();

            restriction.get(1, TimeUnit.SECONDS);
            generation.get(1, TimeUnit.SECONDS);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
