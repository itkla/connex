package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.publicapi.ApiCredentialLifecycleService;
import ooo.klae.connex.backend.storage.ManagedObjectService;

/** Pins final deletion's reservation fence across account-lock contention, snapshots, and caches. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UserDeletionReservationConcurrencyIntegrationTest {
    private static final String OLD_OWNER = "old-deletion-owner";
    private static final String SUCCESSOR_OWNER = "successor-deletion-owner";

    @Autowired private UserMapper userMapper;
    @Autowired private UserDeletionTransaction userDeletionTransaction;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoBean private AuditService auditService;
    @MockitoBean private ManagedObjectService managedObjectService;
    @MockitoBean private ApiCredentialLifecycleService apiCredentialLifecycleService;

    private User user;

    @BeforeEach
    void createCommittedAccount() {
        clearInvocations(auditService, managedObjectService, apiCredentialLifecycleService);
        String unique = UUID.randomUUID().toString().substring(0, 8);
        user = new User();
        user.setUsername("reservation-fence-" + unique);
        user.setDisplayName("Reservation fence " + unique);
        user.setEmail("reservation-fence-" + unique + "@example.test");
        user.setPasswordHash("hash-" + unique);
        user.setTimezone("UTC");
        userMapper.insert(user);
        assertEquals(1, userMapper.reserveAccountDeletion(user.getId(), OLD_OWNER));
    }

    @AfterEach
    void cleanUpCommittedAccount() {
        if (user != null && user.getId() != 0) {
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", user.getId());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void replacedReservationRefusesWaitingDeleterEvenWithAnEarlierSnapshot(boolean earlierSnapshot)
            throws Exception {
        int userId = user.getId();
        AtomicLong deleterConnection = new AtomicLong();
        AtomicReference<Future<?>> deletion = new AtomicReference<>();
        AtomicReference<Map<String, Object>> successor = new AtomicReference<>();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            repeatableReadTransaction().executeWithoutResult(status -> {
                assertEquals(userId, userMapper.lockById(userId));
                long holderConnection = jdbcTemplate.queryForObject(
                    "SELECT CONNECTION_ID()", Long.class);
                deletion.set(executor.submit(() ->
                    repeatableReadTransaction().executeWithoutResult(deletionStatus -> {
                        deleterConnection.set(jdbcTemplate.queryForObject(
                            "SELECT CONNECTION_ID()", Long.class));
                        if (earlierSnapshot) {
                            assertEquals(OLD_OWNER, jdbcTemplate.queryForObject(
                                "SELECT account_deletion_reservation_owner FROM app_user WHERE id = ?",
                                String.class, userId));
                        }
                        userDeletionTransaction.delete(userId, OLD_OWNER);
                    })));
                awaitAccountLockWait(holderConnection, deleterConnection, deletion.get(), userId);

                assertEquals(1, jdbcTemplate.update(
                    "UPDATE app_user SET account_deletion_reservation_until = "
                        + "DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 SECOND) WHERE id = ?", userId));
                assertEquals(1, userMapper.reserveAccountDeletion(userId, SUCCESSOR_OWNER));
                successor.set(reservationState(userId));
            });

            ExecutionException failure = assertThrows(
                ExecutionException.class, () -> deletion.get().get(20, TimeUnit.SECONDS));
            ConflictException conflict = assertInstanceOf(ConflictException.class, failure.getCause());
            assertEquals("Account deletion was not reserved", conflict.getMessage());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertNotNull(userMapper.getUserById(userId));
        assertEquals(SUCCESSOR_OWNER, successor.get().get("account_deletion_reservation_owner"));
        assertEquals(successor.get(), reservationState(userId));
        assertTrue(jdbcTemplate.queryForObject(
            "SELECT account_deletion_reservation_until > UTC_TIMESTAMP(6) FROM app_user WHERE id = ?",
            Boolean.class, userId));
        verifyNoInteractions(auditService, managedObjectService, apiCredentialLifecycleService);
    }

    @Test
    void ownershipReadRechecksDatabaseClockExpiryInsteadOfItsSessionCache() {
        int userId = user.getId();
        repeatableReadTransaction().executeWithoutResult(status -> {
            assertEquals(userId, userMapper.lockById(userId));
            assertTrue(userMapper.isAccountDeletionReservationOwner(userId, OLD_OWNER));

            assertEquals(1, jdbcTemplate.update(
                "UPDATE app_user SET account_deletion_reservation_until = "
                    + "DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 SECOND) WHERE id = ?", userId));

            assertFalse(userMapper.isAccountDeletionReservationOwner(userId, OLD_OWNER));
        });
        verifyNoInteractions(auditService, managedObjectService, apiCredentialLifecycleService);
    }

    private TransactionTemplate repeatableReadTransaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return transaction;
    }

    private Map<String, Object> reservationState(int userId) {
        return jdbcTemplate.queryForMap(
            "SELECT account_deletion_reserved_at, account_deletion_reservation_owner, "
                + "account_deletion_reservation_until FROM app_user WHERE id = ?", userId);
    }

    /** Waits for this deleter's exclusive request on the exact account row held by this connection. */
    private void awaitAccountLockWait(
            long holderConnection, AtomicLong deleterConnection, Future<?> deletion, int userId) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (deletion.isDone()) {
                throw new AssertionError("The deleter finished before waiting for the account lock");
            }
            Integer waits = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM performance_schema.data_lock_waits waits
                JOIN performance_schema.data_locks requested
                  ON requested.ENGINE = waits.ENGINE
                 AND requested.ENGINE_LOCK_ID = waits.REQUESTING_ENGINE_LOCK_ID
                JOIN performance_schema.data_locks blocking
                  ON blocking.ENGINE = waits.ENGINE
                 AND blocking.ENGINE_LOCK_ID = waits.BLOCKING_ENGINE_LOCK_ID
                JOIN performance_schema.threads requesting_thread
                  ON requesting_thread.THREAD_ID = waits.REQUESTING_THREAD_ID
                JOIN performance_schema.threads blocking_thread
                  ON blocking_thread.THREAD_ID = waits.BLOCKING_THREAD_ID
                WHERE requesting_thread.PROCESSLIST_ID = ?
                  AND blocking_thread.PROCESSLIST_ID = ?
                  AND requested.OBJECT_SCHEMA = DATABASE()
                  AND requested.OBJECT_NAME = 'app_user'
                  AND requested.INDEX_NAME = 'PRIMARY'
                  AND requested.LOCK_TYPE = 'RECORD'
                  AND requested.LOCK_MODE LIKE 'X%'
                  AND requested.LOCK_STATUS = 'WAITING'
                  AND requested.LOCK_DATA = CAST(? AS CHAR)
                  AND blocking.OBJECT_SCHEMA = requested.OBJECT_SCHEMA
                  AND blocking.OBJECT_NAME = requested.OBJECT_NAME
                  AND blocking.INDEX_NAME = requested.INDEX_NAME
                  AND blocking.LOCK_TYPE = requested.LOCK_TYPE
                  AND blocking.LOCK_DATA = requested.LOCK_DATA
                  AND blocking.LOCK_STATUS = 'GRANTED'
                """, Integer.class, deleterConnection.get(), holderConnection, userId);
            if (waits != null && waits > 0) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for the account lock", interrupted);
            }
        }
        throw new AssertionError("The deleter never waited for app_user PRIMARY key " + userId);
    }
}
