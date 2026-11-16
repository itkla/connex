package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.test.context.transaction.BeforeTransaction;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.ObjectDeletionQueueMapper;
import ooo.klae.connex.backend.mappers.UserObjectDeletionQueueMapper;
import ooo.klae.connex.backend.storage.ObjectDeletionTask;
import ooo.klae.connex.backend.storage.ObjectDeletionTombstone;
import ooo.klae.connex.backend.storage.ObjectDeletionTransactionExecutor;
import ooo.klae.connex.backend.storage.ObjectStorage;
import ooo.klae.connex.backend.storage.ObjectStorageProperties;
import ooo.klae.connex.backend.storage.UploadSource;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

class ObjectDeletionFixtureDrainIntegrationTest extends AbstractServiceTest {
    @Autowired private ObjectDeletionQueueMapper deletionQueueMapper;
    @MockitoSpyBean private UserObjectDeletionQueueMapper userDeletionQueueMapper;
    @Autowired private ObjectDeletionTransactionExecutor deletionTransactionExecutor;
    @Autowired private ObjectStorage objectStorage;
    @Autowired private ObjectStorageProperties storageProperties;
    @Autowired private TenantWorkScope tenantWorkScope;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private Clock clock;
    @Autowired private JdbcTemplate jdbcTemplate;

    private int objectWorkspaceId;
    private String objectKey;
    private String userObjectKey;
    private Workspace otherWorkspace;
    private Runnable verifyDrain = () -> {};

    /**
     * Commits the isolated workspace before the test transaction establishes its snapshot, so
     * tenant route resolution can see its organization. An empty schema uses the same default
     * organization as the shared fixture's initial workspace creation.
     */
    @BeforeTransaction
    void createIsolatedWorkspace() {
        Workspace defaultWorkspace = tenantWorkScope.unroutedRead(workspaceMapper::getDefaultWorkspace);
        otherWorkspace = new Workspace();
        if (defaultWorkspace != null) {
            otherWorkspace.setOrgId(defaultWorkspace.getOrgId());
        }
        otherWorkspace.setName("Object deletion drain isolation");
        otherWorkspace.setSlug("drain-" + unique());
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
            workspaceMapper.insert(otherWorkspace));
    }

    @Test
    void rollbackTombstoneDeletesTheFileAndCompletesAcrossTwoDrains()
            throws NoSuchAlgorithmException {
        LocalDateTime cutoff = now().plusDays(2);
        ObjectDeletionTombstone tombstone = seedTombstone(
            otherWorkspace.getId(), now().plusDays(1));
        assertEquals(1, deletionQueueMapper.deleteByIdentity(
            otherWorkspace.getId(), tombstone.id(), tombstone.objectKey()));

        verifyDrain = () -> {
            super.drainLeakedObjectDeletions(otherWorkspace.getId(), cutoff);
            ObjectDeletionTask firstPass = queuedTask();
            assertNotNull(firstPass);
            assertEquals(1, firstPass.deletePassesRemaining());
            assertEquals(2, firstPass.attempts());
            assertFalse(Files.exists(objectPath()));

            super.drainLeakedObjectDeletions(otherWorkspace.getId(), now().minusMinutes(10));
            assertEquals(firstPass, queuedTask());

            super.drainLeakedObjectDeletions(otherWorkspace.getId(), cutoff);
            assertNull(queuedTask());
            assertFalse(Files.exists(objectPath()));
        };
    }

    @Test
    void tombstoneDueInsideTheSafetyMarginIsUntouched() throws NoSuchAlgorithmException {
        seedTombstone(workspace.getId(), now().minusMinutes(5));

        verifyDrain = () -> {
            super.drainLeakedObjectDeletions();
            assertTombstoneUntouched();
        };
    }

    @Test
    void overdueTombstoneInAnotherWorkspaceIsUntouched() throws NoSuchAlgorithmException {
        seedTombstone(otherWorkspace.getId(), now().minusMinutes(11));

        verifyDrain = () -> {
            super.drainLeakedObjectDeletions();
            assertTombstoneUntouched();
        };
    }

    @Test
    void rollbackUserTombstoneDeletesTheFileAndCompletesAcrossTwoDrains()
            throws NoSuchAlgorithmException {
        LocalDateTime cutoff = now().plusDays(2);
        ObjectDeletionTombstone tombstone = seedUserTombstone(now().plusDays(1));
        int cancelled = tenantWorkScope.unrouted(() ->
            userDeletionQueueMapper.deleteByIdentity(tombstone.id(), tombstone.objectKey()));
        assertEquals(1, cancelled);

        verifyDrain = () -> {
            selectUserTask(cutoff, queuedUserTask());
            super.drainLeakedUserObjectDeletions(cutoff);
            ObjectDeletionTask firstPass = queuedUserTask();
            assertNotNull(firstPass);
            assertEquals(1, firstPass.deletePassesRemaining());
            assertEquals(2, firstPass.attempts());
            assertFalse(Files.exists(userObjectPath()));

            super.drainLeakedUserObjectDeletions(now().minusMinutes(10));
            assertEquals(firstPass, queuedUserTask());

            selectUserTask(cutoff, firstPass);
            super.drainLeakedUserObjectDeletions(cutoff);
            assertNull(queuedUserTask());
            assertFalse(Files.exists(userObjectPath()));
        };
    }

    @Test
    void userTombstoneDueInsideTheSafetyMarginIsUntouched() throws NoSuchAlgorithmException {
        seedUserTombstone(now().minusMinutes(5));

        verifyDrain = () -> {
            super.drainLeakedObjectDeletions();
            ObjectDeletionTask task = queuedUserTask();
            assertNotNull(task);
            assertEquals(2, task.deletePassesRemaining());
            assertEquals(1, task.attempts());
            assertTrue(Files.isRegularFile(userObjectPath()));
        };
    }

    @Override
    @AfterTransaction
    protected void drainLeakedObjectDeletions() {
        try {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            verifyDrain.run();
        } finally {
            if (userObjectKey != null) {
                tenantWorkScope.unrouted(() -> {
                    objectStorage.delete(userObjectKey);
                    new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                        userDeletionQueueMapper.deleteByKey(userObjectKey));
                    return null;
                });
            }
            if (objectKey != null) {
                tenantWorkScope.inWorkspace(objectWorkspaceId, () -> {
                    objectStorage.delete(objectKey);
                    new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                        deletionQueueMapper.deleteByKey(objectWorkspaceId, objectKey));
                });
            }
            if (otherWorkspace != null && otherWorkspace.getId() > 0) {
                tenantWorkScope.inWorkspace(otherWorkspace.getId(), () ->
                    new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                        jdbcTemplate.update("DELETE FROM object_storage_quota WHERE workspace_id = ?",
                            otherWorkspace.getId())));
                new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                    jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", otherWorkspace.getId()));
            }
        }
    }

    private ObjectDeletionTombstone seedTombstone(int workspaceId, LocalDateTime nextAttemptAt)
            throws NoSuchAlgorithmException {
        objectWorkspaceId = workspaceId;
        objectKey = "workspaces/" + workspaceId + "/attachments/drain-" + unique() + ".pdf";
        byte[] bytes = "Object deletion fixture".getBytes(StandardCharsets.UTF_8);
        objectStorage.put(objectKey, UploadSource.from("drain.pdf", "application/pdf", bytes),
            "application/pdf", MessageDigest.getInstance("SHA-256").digest(bytes));
        assertTrue(Files.isRegularFile(objectPath()));
        return tenantWorkScope.inWorkspace(workspaceId, () ->
            deletionTransactionExecutor.enqueueTenant(workspaceId, objectKey, 2, nextAttemptAt));
    }

    private ObjectDeletionTombstone seedUserTombstone(LocalDateTime nextAttemptAt)
            throws NoSuchAlgorithmException {
        userObjectKey = "users/" + currentUser.getId() + "/profile-images/drain-" + unique() + ".png";
        byte[] bytes = "User object deletion fixture".getBytes(StandardCharsets.UTF_8);
        objectStorage.put(userObjectKey, UploadSource.from("drain.png", "image/png", bytes),
            "image/png", MessageDigest.getInstance("SHA-256").digest(bytes));
        assertTrue(Files.isRegularFile(userObjectPath()));
        return tenantWorkScope.unrouted(() ->
            deletionTransactionExecutor.enqueueUser(userObjectKey, 2, nextAttemptAt));
    }

    /** Restricts the future cutoff to this row instead of claiming unrelated global user fixtures. */
    private void selectUserTask(LocalDateTime cutoff, ObjectDeletionTask task) {
        assertNotNull(task);
        doReturn(List.of(task)).when(userDeletionQueueMapper).findDue(cutoff, 10);
    }

    private void assertTombstoneUntouched() {
        ObjectDeletionTask task = queuedTask();
        assertNotNull(task);
        assertEquals(2, task.deletePassesRemaining());
        assertEquals(1, task.attempts());
        assertTrue(Files.isRegularFile(objectPath()));
    }

    private ObjectDeletionTask queuedTask() {
        return tenantWorkScope.inWorkspace(objectWorkspaceId, () ->
            new TransactionTemplate(transactionManager).execute(status ->
                deletionQueueMapper.lockByKey(objectWorkspaceId, objectKey)));
    }

    private ObjectDeletionTask queuedUserTask() {
        return tenantWorkScope.unrouted(() ->
            new TransactionTemplate(transactionManager).execute(status ->
                userDeletionQueueMapper.lockByKey(userObjectKey)));
    }

    private Path objectPath() {
        return storageProperties.filesystemRootPath().resolve(objectKey + ".object");
    }

    private Path userObjectPath() {
        return storageProperties.filesystemRootPath().resolve(userObjectKey + ".object");
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
