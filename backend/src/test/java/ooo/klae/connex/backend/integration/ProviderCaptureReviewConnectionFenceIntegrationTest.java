package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.ProviderConnection;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.connectedaccounts.ProviderConnectionMutation;
import ooo.klae.connex.backend.dto.ProviderConnectionDto;
import ooo.klae.connex.backend.mappers.ProviderConnectionMapper;
import ooo.klae.connex.backend.mappers.UserMapper;

/**
 * Proves review admission's connection read prevents a lifecycle transition from committing
 * between generation validation and the tenant mutation.
 */
@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
class ProviderCaptureReviewConnectionFenceIntegrationTest {
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ProviderConnectionMapper connectionMapper;
    @Autowired private ProviderConnectionMutation connectionMutation;
    @Autowired private UserMapper userMapper;

    private User user;

    @BeforeEach
    void setUp() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            String suffix = UUID.randomUUID().toString().replace("-", "");
            user = new User();
            user.setUsername("capture_fence_" + suffix);
            user.setDisplayName("Capture Fence");
            user.setEmail("capture_fence_" + suffix + "@example.test");
            user.setTimezone("UTC");
            userMapper.insert(user);
            ProviderConnection connection = new ProviderConnection();
            connection.setUserId(user.getId());
            connection.setProvider("google");
            connection.setStatus("connected");
            connection.setProviderAccountEmail(user.getEmail());
            connection.setProviderAccountId("google:test:" + suffix);
            connection.setGrantedScopes("openid email");
            connection.setCredentialGeneration(1);
            connectionMapper.insert(connection);
        });
    }

    @AfterEach
    void tearDown() {
        if (user == null) {
            return;
        }
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            connectionMapper.delete(user.getId(), "google");
            userMapper.delete(user.getId());
        });
    }

    @Test
    void pauseCannotCommitInsideTheReviewAdmissionFence() throws Exception {
        CountDownLatch reviewValidated = new CountDownLatch(1);
        CountDownLatch releaseReviewMutation = new CountDownLatch(1);
        CountDownLatch pauseStarted = new CountDownLatch(1);
        AtomicBoolean reviewMutationCompleted = new AtomicBoolean();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> review = executor.submit(() ->
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    ProviderConnection locked =
                        connectionMapper.getByUserAndProviderForShare(
                            user.getId(), "google");
                    assertEquals("connected", locked.getStatus());
                    assertEquals(1, locked.getCredentialGeneration());
                    reviewValidated.countDown();
                    await(releaseReviewMutation);
                    reviewMutationCompleted.set(true);
                }));
            assertTrue(reviewValidated.await(10, TimeUnit.SECONDS));

            Future<ProviderConnectionDto> pause = executor.submit(() -> {
                pauseStarted.countDown();
                return connectionMutation.transition(
                    user.getId(), "google", "connected", "paused");
            });
            assertTrue(pauseStarted.await(10, TimeUnit.SECONDS));
            try {
                pause.get(Duration.ofMillis(500).toMillis(), TimeUnit.MILLISECONDS);
                fail("Pause committed while the review connection fence was held");
            } catch (TimeoutException expected) {
                assertFalse(reviewMutationCompleted.get());
            }
            assertEquals(
                "connected",
                connectionMapper.getByUserAndProvider(
                    user.getId(), "google").getStatus());

            releaseReviewMutation.countDown();
            review.get(20, TimeUnit.SECONDS);
            ProviderConnectionDto paused =
                pause.get(20, TimeUnit.SECONDS);

            assertTrue(reviewMutationCompleted.get());
            assertEquals("paused", paused.status());
            assertEquals(
                2,
                connectionMapper.getByUserAndProvider(
                    user.getId(), "google").getCredentialGeneration());
        } finally {
            releaseReviewMutation.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                    "Review connection fence did not resume");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                "Review connection fence was interrupted", exception);
        }
    }
}
