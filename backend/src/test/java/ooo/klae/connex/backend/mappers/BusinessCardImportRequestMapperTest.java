package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
    private static final int USER_ID = 9;

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

        assertEquals(1, claim(workspaceId, requestId, fingerprint));
        assertEquals(1, mapper.complete(workspaceId, requestId, 31, 41, 17));
        assertEquals(0, claim(workspaceId, requestId, fingerprint));

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
        assertEquals(1, claim(workspaceId, requestId, fingerprint((byte) 4)));

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

        assertEquals(1, claim(firstWorkspaceId, requestId, firstFingerprint));
        assertEquals(1, claim(secondWorkspaceId, requestId, secondFingerprint));

        assertArrayEquals(firstFingerprint,
                mapper.get(firstWorkspaceId, requestId).requestFingerprint());
        assertArrayEquals(secondFingerprint,
                mapper.get(secondWorkspaceId, requestId).requestFingerprint());
    }

    @Test
    void cleanupDeletesExpiredCompletedAndIncompleteClaimsWithinTheWorkspace() {
        int workspaceId = workspaceId();
        String expired = requestId();
        String current = requestId();
        String incomplete = requestId();
        String reservation = requestId();
        byte[] fingerprint = fingerprint((byte) 3);
        LocalDateTime cutoff = LocalDateTime.of(2026, 7, 14, 0, 0);
        assertEquals(1, claim(workspaceId, expired, fingerprint));
        assertEquals(1, claim(workspaceId, current, fingerprint));
        assertEquals(1, claim(workspaceId, incomplete, fingerprint));
        assertEquals(1, mapper.reserve(
                workspaceId, USER_ID, reservation, 1, cutoff.minusHours(1), cutoff));
        assertEquals(1, mapper.complete(workspaceId, expired, 31, 41, null));
        assertEquals(1, mapper.complete(workspaceId, current, 32, 42, null));
        jdbcTemplate.update(
                "UPDATE business_card_import_request SET expires_at = ? WHERE workspace_id = ? AND idempotency_key = ?",
                cutoff.minusDays(1), workspaceId, expired);
        jdbcTemplate.update(
                "UPDATE business_card_import_request SET expires_at = ? WHERE workspace_id = ? AND idempotency_key = ?",
                cutoff.minusDays(1), workspaceId, incomplete);

        assertEquals(3, mapper.deleteExpired(workspaceId, cutoff, 10));

        assertNull(mapper.get(workspaceId, expired));
        assertNotNull(mapper.get(workspaceId, current));
        assertNull(mapper.get(workspaceId, incomplete));
        assertNull(mapper.get(workspaceId, reservation));
    }

    @Test
    void cleanupEnumerationFindsExpiredCompletedAndIncompleteClaims() {
        int expiredWorkspace = workspaceId();
        int incompleteWorkspace = expiredWorkspace + 1;
        String expired = requestId();
        String incomplete = requestId();
        String reservation = requestId();
        LocalDateTime cutoff = LocalDateTime.of(2026, 7, 14, 0, 0);
        assertEquals(1, claim(expiredWorkspace, expired, fingerprint((byte) 5)));
        assertEquals(1, mapper.complete(expiredWorkspace, expired, 31, 41, null));
        assertEquals(1, claim(incompleteWorkspace, incomplete, fingerprint((byte) 6)));
        assertEquals(1, mapper.reserve(
                incompleteWorkspace, USER_ID, reservation, 1, cutoff.minusHours(1), cutoff));
        jdbcTemplate.update(
                "UPDATE business_card_import_request SET expires_at = ? WHERE idempotency_key IN (?, ?)",
                cutoff.minusDays(1), expired, incomplete);

        List<Integer> workspaceIds = mapper.workspaceIdsWithExpired(cutoff, 100);

        assertTrue(workspaceIds.contains(expiredWorkspace));
        assertTrue(workspaceIds.contains(incompleteWorkspace));
    }

    @Test
    void reservationPersistsOwnershipLeaseAndSlotUntilItBinds() {
        int workspaceId = workspaceId();
        String requestId = requestId();
        LocalDateTime now = LocalDateTime.of(2026, 7, 15, 0, 0);
        LocalDateTime submissionExpiresAt = now.plusMinutes(2);
        LocalDateTime expiresAt = now.plusDays(1);
        byte[] fingerprint = fingerprint((byte) 8);

        assertEquals(1, mapper.reserve(
                workspaceId, USER_ID, requestId, 2, submissionExpiresAt, expiresAt));

        BusinessCardImportRecord reservation = mapper.get(workspaceId, requestId);
        assertNotNull(reservation);
        assertEquals(USER_ID, reservation.createdByUserId());
        assertEquals(submissionExpiresAt, reservation.submissionExpiresAt());
        assertEquals(2, reservation.reservationSlot());
        assertEquals(1, mapper.bindReservation(
                workspaceId, USER_ID, requestId, fingerprint, expiresAt, now));

        BusinessCardImportRecord bound = mapper.get(workspaceId, requestId);
        assertNotNull(bound);
        assertArrayEquals(fingerprint, bound.requestFingerprint());
        assertNull(bound.submissionExpiresAt());
        assertNull(bound.reservationSlot());
    }

    @Test
    void reservationSlotsBoundOutstandingRowsAndAreReusableAfterBinding() {
        int workspaceId = workspaceId();
        String firstRequestId = requestId();
        String secondRequestId = requestId();
        String thirdRequestId = requestId();
        LocalDateTime now = LocalDateTime.of(2026, 7, 15, 0, 0);
        LocalDateTime submissionExpiresAt = now.plusMinutes(2);
        LocalDateTime expiresAt = now.plusDays(1);

        assertEquals(1, mapper.reserve(
                workspaceId, USER_ID, firstRequestId, 1, submissionExpiresAt, expiresAt));
        assertEquals(0, mapper.reserve(
                workspaceId, USER_ID, secondRequestId, 1, submissionExpiresAt, expiresAt));
        assertEquals(1, mapper.reserve(
                workspaceId, USER_ID, secondRequestId, 2, submissionExpiresAt, expiresAt));
        assertEquals(1, mapper.bindReservation(
                workspaceId,
                USER_ID,
                firstRequestId,
                fingerprint((byte) 1),
                expiresAt,
                now));
        assertEquals(1, mapper.reserve(
                workspaceId, USER_ID, thirdRequestId, 1, submissionExpiresAt, expiresAt));
    }

    @Test
    void expiredOrForeignReservationCannotBind() {
        int workspaceId = workspaceId();
        String expiredRequestId = requestId();
        String foreignRequestId = requestId();
        LocalDateTime now = LocalDateTime.of(2026, 7, 15, 0, 0);
        LocalDateTime expiresAt = now.plusDays(1);

        assertEquals(1, mapper.reserve(
                workspaceId, USER_ID, expiredRequestId, 1, now.minusSeconds(1), expiresAt));
        assertEquals(1, mapper.reserve(
                workspaceId, USER_ID, foreignRequestId, 2, now.plusMinutes(2), expiresAt));

        assertEquals(0, mapper.bindReservation(
                workspaceId,
                USER_ID,
                expiredRequestId,
                fingerprint((byte) 2),
                expiresAt,
                now));
        assertEquals(0, mapper.bindReservation(
                workspaceId,
                USER_ID + 1,
                foreignRequestId,
                fingerprint((byte) 3),
                expiresAt,
                now));
    }

    @Test
    void abandonedReservationCleanupIsOwnerScopedAndFreesItsSlot() {
        int workspaceId = workspaceId();
        String expiredRequestId = requestId();
        String otherUsersRequestId = requestId();
        String replacementRequestId = requestId();
        LocalDateTime now = LocalDateTime.of(2026, 7, 15, 0, 0);
        LocalDateTime expiresAt = now.plusDays(1);

        assertEquals(1, mapper.reserve(
                workspaceId, USER_ID, expiredRequestId, 1, now.minusSeconds(1), expiresAt));
        assertEquals(1, mapper.reserve(
                workspaceId, USER_ID + 1, otherUsersRequestId, 1, now.minusSeconds(1), expiresAt));

        assertEquals(1, mapper.deleteAbandonedReservations(workspaceId, USER_ID, now));
        assertNull(mapper.get(workspaceId, expiredRequestId));
        assertNotNull(mapper.get(workspaceId, otherUsersRequestId));
        assertEquals(1, mapper.reserve(
                workspaceId, USER_ID, replacementRequestId, 1, now.plusMinutes(2), expiresAt));
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
                    assertEquals(1, claim(workspaceId, requestId, fingerprint));
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
                        int claimed = claim(workspaceId, requestId, fingerprint);
                        return new Attempt(claimed, mapper.getForUpdate(workspaceId, requestId));
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
    void statusLookupReadsTheCommittedReservationWithoutWaitingForTheImport() throws Exception {
        int workspaceId = workspaceId();
        String requestId = requestId();
        byte[] fingerprint = fingerprint((byte) 6);
        LocalDateTime now = LocalDateTime.of(2026, 7, 15, 0, 0);
        LocalDateTime expiresAt = now.plusDays(1);
        assertEquals(1, mapper.reserve(
                workspaceId, USER_ID, requestId, 1, now.plusMinutes(2), expiresAt));
        CountDownLatch firstBound = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    assertNotNull(mapper.getForUpdate(workspaceId, requestId));
                    assertEquals(1, mapper.bindReservation(
                            workspaceId,
                            USER_ID,
                            requestId,
                            fingerprint,
                            expiresAt.plusHours(1),
                            now));
                    firstBound.countDown();
                    await(releaseFirst);
                    assertEquals(1, mapper.complete(workspaceId, requestId, 51, 61, null));
                });
                return 1;
            });
            assertTrue(firstBound.await(2, TimeUnit.SECONDS));
            Future<BusinessCardImportRecord> status = executor.submit(() ->
                    new TransactionTemplate(transactionManager).execute(
                            transactionStatus -> mapper.get(workspaceId, requestId)));

            try {
                BusinessCardImportRecord record = status.get(1, TimeUnit.SECONDS);
                assertNotNull(record);
                assertNull(record.requestFingerprint());
                assertNull(record.personId());
                assertNull(record.attachmentId());
            } finally {
                releaseFirst.countDown();
            }

            assertEquals(1, result(first));
            BusinessCardImportRecord completed = mapper.get(workspaceId, requestId);
            assertNotNull(completed);
            assertEquals(51, completed.personId());
            assertEquals(61, completed.attachmentId());
        }
    }

    @Test
    void statusLockWaitsForAnImportTransactionAndReadsItsCompletedResult() throws Exception {
        int workspaceId = workspaceId();
        String requestId = requestId();
        byte[] fingerprint = fingerprint((byte) 6);
        LocalDateTime now = LocalDateTime.of(2026, 7, 15, 0, 0);
        LocalDateTime expiresAt = now.plusDays(1);
        assertEquals(1, mapper.reserve(
                workspaceId, USER_ID, requestId, 1, now.plusMinutes(2), expiresAt));
        CountDownLatch firstBound = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    assertNotNull(mapper.getForUpdate(workspaceId, requestId));
                    assertEquals(1, mapper.bindReservation(
                            workspaceId,
                            USER_ID,
                            requestId,
                            fingerprint,
                            expiresAt,
                            now));
                    firstBound.countDown();
                    await(releaseFirst);
                    assertEquals(1, mapper.complete(workspaceId, requestId, 51, 61, null));
                });
                return 1;
            });
            assertTrue(firstBound.await(2, TimeUnit.SECONDS));
            Future<BusinessCardImportRecord> status = executor.submit(() ->
                    new TransactionTemplate(transactionManager).execute(
                            transactionStatus -> mapper.getForUpdate(workspaceId, requestId)));

            try {
                assertThrows(TimeoutException.class,
                        () -> status.get(250, TimeUnit.MILLISECONDS));
            } finally {
                releaseFirst.countDown();
            }

            assertEquals(1, result(first));
            BusinessCardImportRecord completed = result(status);
            assertNotNull(completed);
            assertArrayEquals(fingerprint, completed.requestFingerprint());
            assertEquals(51, completed.personId());
            assertEquals(61, completed.attachmentId());
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

    private int claim(int workspaceId, String requestId, byte[] fingerprint) {
        LocalDateTime now = LocalDateTime.of(2029, 12, 31, 23, 0);
        LocalDateTime expiresAt = LocalDateTime.of(2030, 1, 1, 0, 0);
        if (mapper.reserve(
                workspaceId,
                USER_ID,
                requestId,
                1,
                now.plusMinutes(2),
                expiresAt) != 1) {
            return 0;
        }
        return mapper.bindReservation(
                workspaceId,
                USER_ID,
                requestId,
                fingerprint,
                expiresAt,
                now);
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
