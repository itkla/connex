package ooo.klae.connex.backend.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

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
            CompletableFuture<Long> blockedRead;
            try {
                epoch.bump(7);
                AtomicReference<Thread> reader = new AtomicReference<>();
                blockedRead = CompletableFuture.supplyAsync(() -> {
                    reader.set(Thread.currentThread());
                    return epoch.current(7);
                }, executor);

                awaitFenceContention(epoch, reader, blockedRead);
                assertFalse(blockedRead.isDone());
            } finally {
                completeTransaction();
            }

            assertEquals(epoch.current(7), blockedRead.get(5, TimeUnit.SECONDS));
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
            CompletableFuture<Void> blockedBump;
            try {
                assertTrue(epoch.retainReadFenceUntilTransactionCompletionIfCurrent(
                        7, expectedEpoch));
                AtomicReference<Thread> bumper = new AtomicReference<>();
                blockedBump = CompletableFuture.runAsync(() -> {
                    bumper.set(Thread.currentThread());
                    epoch.bump(7);
                }, executor);

                awaitFenceContention(epoch, bumper, blockedBump);
                assertFalse(blockedBump.isDone());
            } finally {
                completeTransaction();
            }

            blockedBump.get(5, TimeUnit.SECONDS);
            assertNotEquals(expectedEpoch, epoch.current(7));
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
        AtomicReference<Thread> bumper = new AtomicReference<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<Boolean> action = CompletableFuture.supplyAsync(
                    () -> epoch.runIfCurrent(7, expectedEpoch, () -> {
                        actionStarted.countDown();
                        await(releaseAction);
                    }),
                    executor);
            CompletableFuture<Void> bump;
            try {
                assertTrue(actionStarted.await(5, TimeUnit.SECONDS));
                bump = CompletableFuture.runAsync(() -> {
                    bumper.set(Thread.currentThread());
                    epoch.bump(7);
                }, executor);
                awaitFenceContention(epoch, bumper, bump);
                assertFalse(bump.isDone());
            } finally {
                releaseAction.countDown();
            }

            assertTrue(action.get(5, TimeUnit.SECONDS));
            bump.get(5, TimeUnit.SECONDS);
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
        AtomicReference<Thread> bumper = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(
                2, Thread.ofPlatform().daemon(true).factory());
        try {
            CompletableFuture<Void> generation = CompletableFuture.runAsync(
                    () -> epoch.runWithExpectedEgressEpoch(7, expectedEpoch, () -> {
                        epoch.invokeAtEgress(7, () -> {
                            providerStarted.countDown();
                            await(releaseProvider);
                            return "generated";
                        });
                        lockContributor(contributorRow);
                        try {
                            assertTrue(epoch.current(7) > expectedEpoch);
                        } finally {
                            contributorRow.unlock();
                        }
                    }),
                    executor);
            CompletableFuture<Void> restriction;
            try {
                assertTrue(providerStarted.await(5, TimeUnit.SECONDS));
                restriction = CompletableFuture.runAsync(() -> {
                    lockContributor(contributorRow);
                    try {
                        restrictionLockedContributor.countDown();
                        bumper.set(Thread.currentThread());
                        epoch.bump(7);
                    } finally {
                        contributorRow.unlock();
                    }
                }, executor);
                assertTrue(restrictionLockedContributor.await(5, TimeUnit.SECONDS));
                awaitFenceContention(epoch, bumper, restriction);
            } finally {
                releaseProvider.countDown();
            }

            restriction.get(10, TimeUnit.SECONDS);
            generation.get(10, TimeUnit.SECONDS);
        } finally {
            releaseProvider.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS),
                    "Epoch workers did not terminate after provider release");
        }
    }

    private static void lockContributor(ReentrantLock contributorRow) {
        try {
            assertTrue(contributorRow.tryLock(5, TimeUnit.SECONDS),
                    "Contributor persistence must not retain the provider epoch fence");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Contributor lock acquisition was interrupted", exception);
        }
    }

    private static void awaitFenceContention(
            AiRestrictionEpoch epoch, AtomicReference<Thread> contender, CompletableFuture<?> result)
            throws ReflectiveOperationException {
        Field field = AiRestrictionEpoch.class.getDeclaredField("workspaceLocks");
        field.setAccessible(true);
        ReentrantReadWriteLock[] locks = ReentrantReadWriteLock[].class.cast(field.get(epoch));
        ReentrantReadWriteLock fence = locks[Math.floorMod(7, locks.length)];
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            assertFalse(result.isDone(), "Contender completed before waiting on the workspace fence");
            Thread thread = contender.get();
            if (thread != null && fence.hasQueuedThread(thread)) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        throw new AssertionError("Contender never queued on the workspace epoch fence");
    }

    private static void completeTransaction() {
        try {
            TransactionSynchronizationUtils.triggerAfterCompletion(
                    TransactionSynchronization.STATUS_COMMITTED);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
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
