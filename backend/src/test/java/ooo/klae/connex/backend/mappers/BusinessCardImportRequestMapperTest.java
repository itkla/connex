package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.time.LocalDateTime;
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
    void lookupReturnsIncompleteClaimOnlyWithinItsWorkspace() {
        int workspaceId = workspaceId();
        String requestId = requestId();
        assertEquals(1, mapper.claim(workspaceId, requestId, fingerprint((byte) 4)));

        BusinessCardImportRecord record = mapper.get(workspaceId, requestId);
        assertNotNull(record);
        assertNull(record.personId());
        assertNull(record.attachmentId());
        assertNull(mapper.get(workspaceId + 1, requestId));
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
    void cleanupDeletesOnlyExpiredCompletedClaimsWithinTheWorkspace() {
        int workspaceId = workspaceId();
        String expired = requestId();
        String current = requestId();
        String incomplete = requestId();
        byte[] fingerprint = fingerprint((byte) 3);
        assertEquals(1, mapper.claim(workspaceId, expired, fingerprint));
        assertEquals(1, mapper.claim(workspaceId, current, fingerprint));
        assertEquals(1, mapper.claim(workspaceId, incomplete, fingerprint));
        assertEquals(1, mapper.complete(workspaceId, expired, 31, 41, null));
        assertEquals(1, mapper.complete(workspaceId, current, 32, 42, null));
        LocalDateTime cutoff = LocalDateTime.of(2026, 7, 14, 0, 0);
        jdbcTemplate.update(
                "UPDATE business_card_import_request SET created_at = ? WHERE workspace_id = ? AND idempotency_key = ?",
                cutoff.minusDays(1), workspaceId, expired);
        jdbcTemplate.update(
                "UPDATE business_card_import_request SET created_at = ? WHERE workspace_id = ? AND idempotency_key = ?",
                cutoff.minusDays(1), workspaceId, incomplete);

        assertEquals(1, mapper.deleteExpired(workspaceId, cutoff, 10));

        assertNull(mapper.get(workspaceId, expired));
        assertNotNull(mapper.get(workspaceId, current));
        assertNotNull(mapper.get(workspaceId, incomplete));
    }

    @Test
    void cleanupEnumerationFindsInactiveWorkspacesWithExpiredCompletedClaimsOnly() {
        int expiredWorkspace = workspaceId();
        int incompleteWorkspace = expiredWorkspace + 1;
        String expired = requestId();
        String incomplete = requestId();
        LocalDateTime cutoff = LocalDateTime.of(2026, 7, 14, 0, 0);
        assertEquals(1, mapper.claim(expiredWorkspace, expired, fingerprint((byte) 5)));
        assertEquals(1, mapper.complete(expiredWorkspace, expired, 31, 41, null));
        assertEquals(1, mapper.claim(incompleteWorkspace, incomplete, fingerprint((byte) 6)));
        jdbcTemplate.update(
                "UPDATE business_card_import_request SET created_at = ? WHERE idempotency_key IN (?, ?)",
                cutoff.minusDays(1), expired, incomplete);

        List<Integer> workspaceIds = mapper.workspaceIdsWithExpired(cutoff, 100);

        assertTrue(workspaceIds.contains(expiredWorkspace));
        assertFalse(workspaceIds.contains(incompleteWorkspace));
    }

    @Test
    void concurrentRetryUsesTheFirstResultAfterAnEarlierConsistentRead() throws Exception {
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
                        assertEquals(0, jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM business_card_import_request WHERE workspace_id = ?",
                                Integer.class,
                                workspaceId));
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

    @Test
    void statusLookupWaitsForTheImportTransaction() throws Exception {
        int workspaceId = workspaceId();
        String requestId = requestId();
        byte[] fingerprint = fingerprint((byte) 6);
        CountDownLatch firstClaimed = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch statusReading = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    assertEquals(1, mapper.claim(workspaceId, requestId, fingerprint));
                    firstClaimed.countDown();
                    await(releaseFirst);
                    assertEquals(1, mapper.complete(workspaceId, requestId, 51, 61, null));
                });
                return 1;
            });
            assertTrue(firstClaimed.await(2, TimeUnit.SECONDS));
            Future<BusinessCardImportRecord> status = executor.submit(() ->
                    new TransactionTemplate(transactionManager).execute(transactionStatus -> {
                        statusReading.countDown();
                        return mapper.get(workspaceId, requestId);
                    }));
            assertTrue(statusReading.await(2, TimeUnit.SECONDS));

            try {
                assertThrows(TimeoutException.class, () -> status.get(250, TimeUnit.MILLISECONDS));
            } finally {
                releaseFirst.countDown();
            }

            assertEquals(1, result(first));
            BusinessCardImportRecord record = result(status);
            assertNotNull(record);
            assertEquals(51, record.personId());
            assertEquals(61, record.attachmentId());
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
