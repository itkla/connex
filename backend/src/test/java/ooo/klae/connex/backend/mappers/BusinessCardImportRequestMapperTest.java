package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.businesscard.BusinessCardImportRecord;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class BusinessCardImportRequestMapperTest {
    @Autowired private BusinessCardImportRequestMapper mapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    private final List<String> requestIds = new CopyOnWriteArrayList<>();

    @AfterEach
    void cleanUp() {
        for (String requestId : requestIds) {
            jdbcTemplate.update(
                    "DELETE FROM business_card_import_request WHERE idempotency_key = ?",
                    requestId);
        }
    }

    @Test
    void sequentialRetryReturnsTheRecordedResult() {
        int workspaceId = workspaceId();
        String requestId = requestId();
        byte[] fingerprint = fingerprint((byte) 7);

        assertEquals(1, mapper.claim(workspaceId, requestId, fingerprint));
        assertEquals(1, mapper.complete(workspaceId, requestId, 31, 41, 17));
        assertEquals(0, mapper.claim(workspaceId, requestId, fingerprint));

        BusinessCardImportRecord record = mapper.get(workspaceId, requestId);
        assertNotNull(record);
        assertArrayEquals(fingerprint, record.requestFingerprint());
        assertEquals(31, record.personId());
        assertEquals(41, record.attachmentId());
        assertEquals(17, record.companyId());
    }

    @Test
    void sameKeyCanBeClaimedIndependentlyAcrossWorkspaces() {
        int firstWorkspaceId = workspaceId();
        int secondWorkspaceId = firstWorkspaceId + 1;
        String requestId = requestId();
        byte[] firstFingerprint = fingerprint((byte) 1);
        byte[] secondFingerprint = fingerprint((byte) 2);

        assertEquals(1, mapper.claim(firstWorkspaceId, requestId, firstFingerprint));
        assertEquals(1, mapper.claim(secondWorkspaceId, requestId, secondFingerprint));

        assertArrayEquals(firstFingerprint,
                mapper.get(firstWorkspaceId, requestId).requestFingerprint());
        assertArrayEquals(secondFingerprint,
                mapper.get(secondWorkspaceId, requestId).requestFingerprint());
    }

    @Test
    void concurrentRetryWaitsForAndReusesTheFirstResult() throws Exception {
        int workspaceId = workspaceId();
        String requestId = requestId();
        byte[] fingerprint = fingerprint((byte) 9);
        CountDownLatch firstClaimed = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondAttempting = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    assertEquals(1, mapper.claim(workspaceId, requestId, fingerprint));
                    firstClaimed.countDown();
                    await(releaseFirst);
                    assertEquals(1, mapper.complete(workspaceId, requestId, 31, 41, null));
                });
                return 1;
            });
            assertTrue(firstClaimed.await(2, TimeUnit.SECONDS));
            Future<Attempt> second = executor.submit(() ->
                    new TransactionTemplate(transactionManager).execute(status -> {
                        secondAttempting.countDown();
                        int claimed = mapper.claim(workspaceId, requestId, fingerprint);
                        return new Attempt(claimed, mapper.get(workspaceId, requestId));
                    }));
            assertTrue(secondAttempting.await(2, TimeUnit.SECONDS));

            try {
                assertThrows(TimeoutException.class, () -> second.get(250, TimeUnit.MILLISECONDS));
            } finally {
                releaseFirst.countDown();
            }

            assertEquals(1, result(first));
            Attempt attempt = result(second);
            assertEquals(0, attempt.claimed());
            assertNotNull(attempt.record());
            assertArrayEquals(fingerprint, attempt.record().requestFingerprint());
            assertEquals(31, attempt.record().personId());
            assertEquals(41, attempt.record().attachmentId());
        }
    }

    private String requestId() {
        String requestId = UUID.randomUUID().toString();
        requestIds.add(requestId);
        return requestId;
    }

    private static int workspaceId() {
        return ThreadLocalRandom.current().nextInt(1_000_000, 2_000_000);
    }

    private static byte[] fingerprint(byte value) {
        byte[] fingerprint = new byte[32];
        Arrays.fill(fingerprint, value);
        return fingerprint;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for concurrent import test");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during concurrent import test", exception);
        }
    }

    private static <T> T result(Future<T> future)
            throws InterruptedException, ExecutionException, TimeoutException {
        return future.get(5, TimeUnit.SECONDS);
    }

    private record Attempt(int claimed, BusinessCardImportRecord record) {
    }
}
