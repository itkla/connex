package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

class AiAssistantAccessFenceTest {

    @Test
    void authorizationMutationCannotCommitAcrossProviderEgress() throws Exception {
        AiAssistantAccessFence fence = new AiAssistantAccessFence();
        CountDownLatch egressEntered = new CountDownLatch(1);
        CountDownLatch releaseEgress = new CountDownLatch(1);
        CountDownLatch mutationEntered = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var egress = executor.submit(() -> fence.invokeAtEgress(7, 11, () -> {
                egressEntered.countDown();
                await(releaseEgress);
                return "sent";
            }));
            assertTrue(egressEntered.await(2, TimeUnit.SECONDS));
            var mutation = executor.submit(() -> {
                TransactionSynchronizationManager.setActualTransactionActive(true);
                TransactionSynchronizationManager.initSynchronization();
                try {
                    fence.retainMutationFenceUntilTransactionCompletion(7);
                    mutationEntered.countDown();
                    TransactionSynchronizationUtils.triggerAfterCompletion(
                            TransactionSynchronization.STATUS_COMMITTED);
                } finally {
                    TransactionSynchronizationManager.clearSynchronization();
                    TransactionSynchronizationManager.setActualTransactionActive(false);
                }
            });
            assertFalse(mutationEntered.await(100, TimeUnit.MILLISECONDS));
            releaseEgress.countDown();
            assertTrue(mutationEntered.await(2, TimeUnit.SECONDS));
            assertTrue("sent".equals(egress.get(2, TimeUnit.SECONDS)));
            mutation.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void accountDeletionExcludesEveryEgressForTheUser() throws Exception {
        AiAssistantAccessFence fence = new AiAssistantAccessFence();
        CountDownLatch deletionEntered = new CountDownLatch(1);
        CountDownLatch releaseDeletion = new CountDownLatch(1);
        CountDownLatch egressEntered = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var deletion = executor.submit(() -> fence.runWithUserMutationFence(11, () -> {
                deletionEntered.countDown();
                await(releaseDeletion);
            }));
            assertTrue(deletionEntered.await(2, TimeUnit.SECONDS));
            var egress = executor.submit(() -> fence.invokeAtEgress(19, 11, () -> {
                egressEntered.countDown();
                return "sent";
            }));
            assertFalse(egressEntered.await(100, TimeUnit.MILLISECONDS));
            releaseDeletion.countDown();
            assertTrue(egressEntered.await(2, TimeUnit.SECONDS));
            deletion.get(2, TimeUnit.SECONDS);
            assertTrue("sent".equals(egress.get(2, TimeUnit.SECONDS)));
        }
    }

    @Test
    void retainedPersistenceReadFenceBlocksAuthorizationMutationUntilCompletion() throws Exception {
        AiAssistantAccessFence fence = new AiAssistantAccessFence();
        CountDownLatch persistenceEntered = new CountDownLatch(1);
        CountDownLatch releasePersistence = new CountDownLatch(1);
        CountDownLatch mutationEntered = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var persistence = executor.submit(() -> {
                TransactionSynchronizationManager.setActualTransactionActive(true);
                TransactionSynchronizationManager.initSynchronization();
                try {
                    fence.retainReadFenceUntilTransactionCompletion(7);
                    persistenceEntered.countDown();
                    await(releasePersistence);
                    TransactionSynchronizationUtils.triggerAfterCompletion(
                            TransactionSynchronization.STATUS_COMMITTED);
                } finally {
                    TransactionSynchronizationManager.clearSynchronization();
                    TransactionSynchronizationManager.setActualTransactionActive(false);
                }
            });
            assertTrue(persistenceEntered.await(2, TimeUnit.SECONDS));
            var mutation = executor.submit(() -> {
                TransactionSynchronizationManager.setActualTransactionActive(true);
                TransactionSynchronizationManager.initSynchronization();
                try {
                    fence.retainMutationFenceUntilTransactionCompletion(7);
                    mutationEntered.countDown();
                    TransactionSynchronizationUtils.triggerAfterCompletion(
                            TransactionSynchronization.STATUS_COMMITTED);
                } finally {
                    TransactionSynchronizationManager.clearSynchronization();
                    TransactionSynchronizationManager.setActualTransactionActive(false);
                }
            });
            assertFalse(mutationEntered.await(100, TimeUnit.MILLISECONDS));
            releasePersistence.countDown();
            assertTrue(mutationEntered.await(2, TimeUnit.SECONDS));
            persistence.get(2, TimeUnit.SECONDS);
            mutation.get(2, TimeUnit.SECONDS);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Assistant fence test timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Assistant fence test was interrupted", exception);
        }
    }
}
