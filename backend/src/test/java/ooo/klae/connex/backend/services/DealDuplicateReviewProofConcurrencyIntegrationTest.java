package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.mappers.DealDuplicateReviewProofMapper;

/** Verifies deal-review proof claims across independent service instances and transactions. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DealDuplicateReviewProofConcurrencyIntegrationTest {
    private static final int WORKSPACE_ID = 2_000_000_001;
    private static final int ACTOR_ID = 2_000_000_002;
    private static final String WORKFLOW = "a".repeat(64);
    private static final String RESULT = "b".repeat(64);

    @Autowired private DealDuplicateReviewProofMapper mapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    private DealDuplicateReviewProofService issuer;
    private DealDuplicateReviewProofService firstConsumer;
    private DealDuplicateReviewProofService secondConsumer;

    @BeforeEach
    void setUp() {
        cleanup();
        DuplicatePreflightProperties properties = new DuplicatePreflightProperties();
        issuer = service(properties);
        firstConsumer = service(properties);
        secondConsumer = service(properties);
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    void proofIssuedByOneReplicaHasExactlyOneConsumerAcrossTwoOtherReplicas()
            throws Exception {
        String proof = inTransaction(() -> issuer.issue(WORKFLOW, RESULT));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Claim> first = executor.submit(
                () -> consumeAfterStart(firstConsumer, proof, ready, start));
            Future<Claim> second = executor.submit(
                () -> consumeAfterStart(secondConsumer, proof, ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            Claim firstClaim = first.get(10, TimeUnit.SECONDS);
            Claim secondClaim = second.get(10, TimeUnit.SECONDS);
            List<Boolean> outcomes = List.of(
                firstClaim.consumed(),
                secondClaim.consumed()
            ).stream().sorted().toList();

            assertNotEquals(firstClaim.connectionId(), secondClaim.connectionId());
            assertEquals(List.of(false, true), outcomes);
        } finally {
            start.countDown();
        }
    }

    @Test
    void rolledBackClaimRemainsConsumableByAnotherReplica() {
        String proof = inTransaction(() -> issuer.issue(WORKFLOW, RESULT));

        Boolean claimedBeforeRollback = new TransactionTemplate(transactionManager).execute(
            status -> {
                boolean claimed = firstConsumer.consume(proof, WORKFLOW, RESULT);
                status.setRollbackOnly();
                return claimed;
            });

        assertTrue(Boolean.TRUE.equals(claimedBeforeRollback));
        assertTrue(inTransaction(
            () -> secondConsumer.consume(proof, WORKFLOW, RESULT)));
    }

    private DealDuplicateReviewProofService service(
            DuplicatePreflightProperties properties) {
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(WORKSPACE_ID);
        when(workspaceService.getCurrentUserId()).thenReturn(ACTOR_ID);
        return new DealDuplicateReviewProofService(mapper, workspaceService, properties);
    }

    private Claim consumeAfterStart(
            DealDuplicateReviewProofService consumer,
            String proof,
            CountDownLatch ready,
            CountDownLatch start) {
        return inTransaction(() -> {
            int connectionId = Objects.requireNonNull(
                jdbcTemplate.queryForObject("SELECT CONNECTION_ID()", Integer.class));
            ready.countDown();
            await(start);
            return new Claim(
                connectionId,
                consumer.consume(proof, WORKFLOW, RESULT));
        });
    }

    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return Objects.requireNonNull(
            new TransactionTemplate(transactionManager).execute(status -> work.get()));
    }

    private void cleanup() {
        new TransactionTemplate(transactionManager).executeWithoutResult(
            status -> mapper.deleteForActorAnywhere(ACTOR_ID));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent proof claims did not start");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent proof claim was interrupted", exception);
        }
    }

    private record Claim(int connectionId, boolean consumed) {
    }
}
