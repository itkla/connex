package ooo.klae.connex.backend.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.storage.ManagedObjectService.ManagedContent;
import ooo.klae.connex.backend.storage.ManagedObjectService.StoredBinary;
import ooo.klae.connex.backend.storage.ObjectStorageProperties.LegacyMigrationMode;

@ExtendWith(MockitoExtension.class)
class ManagedObjectServiceTest {
    @Mock ObjectStorage objectStorage;
    @Mock ObjectDeletionRetryQueue deletionRetryQueue;
    @Mock WorkspaceObjectStorageQuotaService quotaService;
    @Mock UserImageReplacementAdmissionService userImageAdmissionService;

    private ObjectStorageProperties properties;
    private ManagedObjectService service;

    @BeforeEach
    void setUp() {
        properties = new ObjectStorageProperties();
        UploadPolicy uploadPolicy = new UploadPolicy(properties);
        ImageUploadValidator imageValidator = new ImageUploadValidator(
            properties, uploadPolicy, new ImageDecodeAdmissionService(properties));
        service = new ManagedObjectService(
            objectStorage,
            deletionRetryQueue,
            uploadPolicy,
            imageValidator,
            properties,
            quotaService,
            userImageAdmissionService,
            new ManagedObjectWriteAdmissionService(properties));
    }

    @Test
    void storesOpaqueAttachmentReferenceAndTenantDerivedPrivateKey() throws Exception {
        byte[] bytes = "business card".getBytes(StandardCharsets.UTF_8);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> checksum = ArgumentCaptor.forClass(byte[].class);

        StoredBinary stored = inTransaction(() -> service.storeAttachment(
            17,
            "card.pdf",
            "application/pdf",
            bytes));

        verify(objectStorage).put(key.capture(), any(UploadSource.class),
            org.mockito.ArgumentMatchers.eq("application/pdf"), checksum.capture());
        assertTrue(key.getValue().matches(
            "workspaces/17/attachments/[0-9a-f-]{36}\\.pdf"));
        assertTrue(stored.url().matches(
            "/api/attachments/content/[0-9a-f-]{36}\\.pdf"));
        assertFalse(stored.url().contains("workspaces/17"));
        assertEquals("card.pdf", stored.fileName());
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(bytes), checksum.getValue());
        verify(quotaService).reserve(17, key.getValue(), bytes.length);
    }

    @Test
    void migrationKeysAreStableAcrossRetriesAndDistinctAcrossRecords() {
        byte[] bytes = "legacy attachment".getBytes(StandardCharsets.UTF_8);
        UploadSource source = UploadSource.from(
            "legacy.pdf", "application/pdf", bytes);

        StoredBinary first = inTransaction(() -> service.storeMigratedAttachment(
            17, 23, "/attachments/person/legacy.pdf", source));
        StoredBinary retry = inTransaction(() -> service.storeMigratedAttachment(
            17, 23, "/attachments/person/legacy.pdf", source));
        StoredBinary otherRecord = inTransaction(() -> service.storeMigratedAttachment(
            17, 24, "/attachments/person/legacy.pdf", source));

        assertEquals(first.url(), retry.url());
        assertFalse(first.url().equals(otherRecord.url()));
        assertTrue(first.url().matches(
            "/api/attachments/content/[0-9a-f-]{36}\\.pdf"));
    }

    @Test
    void deterministicMigrationTargetRegistersCleanupOnlyForANewWrite() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.storeMigratedAttachment(
                17,
                23,
                "/attachments/person/legacy.pdf",
                UploadSource.from(
                    "legacy.pdf",
                    "application/pdf",
                    "legacy attachment".getBytes(StandardCharsets.UTF_8)));

            assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());
            TransactionSynchronizationManager.getSynchronizations().getFirst()
                .afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
            verify(deletionRetryQueue, never()).enqueueRollbackTombstoneTenant(
                org.mockito.ArgumentMatchers.anyInt(), anyString());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void deterministicMigrationRollbackQueuesReplaySafeCleanup() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            StoredBinary stored = service.storeMigratedAttachment(
                17,
                23,
                "/attachments/person/legacy.pdf",
                UploadSource.from(
                    "legacy.pdf",
                    "application/pdf",
                    "legacy attachment".getBytes(StandardCharsets.UTF_8)));
            String token = stored.url().substring(stored.url().lastIndexOf('/') + 1);

            TransactionSynchronizationManager.getSynchronizations().getFirst()
                .afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

            verify(deletionRetryQueue).enqueueRollbackTombstoneTenant(
                17, "workspaces/17/attachments/" + token);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void deterministicMigrationReusesMatchingStoredObjectWithoutCleanup() throws Exception {
        byte[] bytes = "legacy attachment".getBytes(StandardCharsets.UTF_8);
        when(objectStorage.get(anyString()))
            .thenReturn(new StoredObject(new ByteArrayInputStream(bytes), bytes.length));
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.storeMigratedAttachment(
                17,
                23,
                "/attachments/person/legacy.pdf",
                UploadSource.from("legacy.pdf", "application/pdf", bytes));

            assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
            verify(deletionRetryQueue).cancelTenantInCurrentTransaction(
                org.mockito.ArgumentMatchers.eq(17),
                org.mockito.ArgumentMatchers.anyString());
            verify(objectStorage, never()).put(
                anyString(), any(UploadSource.class), anyString(), any(byte[].class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void deterministicMigrationCancelsStaleRollbackTombstoneBeforeAdoption() throws Exception {
        byte[] bytes = "legacy attachment".getBytes(StandardCharsets.UTF_8);
        when(objectStorage.get(anyString()))
            .thenReturn(new StoredObject(new ByteArrayInputStream(bytes), bytes.length));
        TransactionSynchronizationManager.initSynchronization();
        try {
            StoredBinary stored = service.storeMigratedAttachment(
                17,
                23,
                "/attachments/person/legacy.pdf",
                UploadSource.from("legacy.pdf", "application/pdf", bytes));
            String token = stored.url().substring(stored.url().lastIndexOf('/') + 1);
            String key = "workspaces/17/attachments/" + token;

            verify(quotaService).reserve(17, key, bytes.length);
            verify(deletionRetryQueue).cancelTenantInCurrentTransaction(17, key);
            verify(deletionRetryQueue).requireTenantWriteAllowed(17);
            verify(objectStorage, never()).put(
                anyString(), any(UploadSource.class), anyString(), any(byte[].class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void rejectsObjectWritesWithoutMetadataTransactionSynchronization() {
        assertThrows(IllegalStateException.class, () -> service.storeAttachment(
            17, "card.pdf", "application/pdf", new byte[] {1, 2, 3}));

        verify(objectStorage, never()).put(
            anyString(), any(UploadSource.class), anyString(), any(byte[].class));
    }

    @Test
    void storesTheValidatedImageBytesWhenTheOriginalSourceChanges() throws Exception {
        byte[] valid = png(10, 20);
        byte[] changed = "not an image".getBytes(StandardCharsets.UTF_8);
        AtomicInteger opens = new AtomicInteger();
        UploadSource source = new UploadSource(
            "contact.png",
            "image/png",
            valid.length,
            () -> new ByteArrayInputStream(opens.getAndIncrement() == 0 ? valid : changed));
        ArgumentCaptor<UploadSource> storedSource = ArgumentCaptor.forClass(UploadSource.class);

        inTransaction(() -> service.storePersonImage(17, 23, source));

        verify(objectStorage).put(anyString(), storedSource.capture(),
            org.mockito.ArgumentMatchers.eq("image/png"), any(byte[].class));
        try (java.io.InputStream input = storedSource.getValue().openStream()) {
            byte[] stored = input.readAllBytes();
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(stored));
            assertEquals(10, decoded.getWidth());
            assertEquals(20, decoded.getHeight());
        }
        assertEquals(1, opens.get());
    }

    @Test
    void opensOnlyExactPersistedManagedReference() throws Exception {
        String objectName = "550e8400-e29b-41d4-a716-446655440000.pdf";
        Attachment attachment = new Attachment();
        attachment.setUrl("/api/attachments/content/" + objectName);
        attachment.setFileName("report.pdf");
        attachment.setContentType("application/pdf");
        byte[] bytes = { 1, 2, 3 };
        when(objectStorage.get("workspaces/9/attachments/" + objectName))
            .thenReturn(new StoredObject(new ByteArrayInputStream(bytes), bytes.length));

        try (ManagedContent content = service.openAttachment(9, attachment)) {
            assertArrayEquals(bytes, content.inputStream().readAllBytes());
            assertEquals("application/pdf", content.contentType());
        }

        attachment.setUrl("/api/attachments/content/" + objectName + "/extra");
        assertThrows(ResourceNotFoundException.class, () -> service.openAttachment(9, attachment));
    }

    @Test
    void storeRegistersCleanupBeforeTransactionRollback() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            StoredBinary stored = service.storeAttachment(
                12, "card.pdf", "application/pdf", new byte[] {1, 2, 3});
            String token = stored.url().substring(stored.url().lastIndexOf('/') + 1);
            verify(objectStorage, never()).delete(anyString());
            List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
            assertEquals(1, synchronizations.size());
            synchronizations.getFirst().afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            verify(deletionRetryQueue).enqueueRollbackTombstoneTenant(
                12, "workspaces/12/attachments/" + token);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void storeCleanupPreservesIndeterminateTransactionOutcome() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            StoredBinary stored = service.storeAttachment(
                12, "card.pdf", "application/pdf", new byte[] {1, 2, 3});
            String token = stored.url().substring(stored.url().lastIndexOf('/') + 1);
            List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();

            synchronizations.getFirst().afterCompletion(TransactionSynchronization.STATUS_UNKNOWN);

            verify(deletionRetryQueue, never()).enqueueRollbackTombstoneTenant(12,
                "workspaces/12/attachments/" + token);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void userStoreUsesControlPlaneCleanupAndPreservesIndeterminateOutcome() throws Exception {
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.storeUserImage(
                8, UploadSource.from("portrait.png", "image/png", png(10, 20)));
            List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();

            synchronizations.getFirst().afterCompletion(TransactionSynchronization.STATUS_UNKNOWN);

            verify(userImageAdmissionService).requireAllowed(8);
            verify(deletionRetryQueue, never()).enqueueRollbackTombstoneUser(anyString());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void rollbackCleanupRunsAfterTransactionThreadState() throws Exception {
        Thread transactionThread = Thread.currentThread();
        org.mockito.Mockito.doAnswer(invocation -> {
            assertNotEquals(transactionThread, Thread.currentThread());
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(deletionRetryQueue).enqueueRollbackTombstoneUser(anyString());
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            service.storeUserImage(
                8, UploadSource.from("portrait.png", "image/png", png(10, 20)));

            TransactionSynchronizationManager.getSynchronizations().getFirst()
                .afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

            verify(deletionRetryQueue).enqueueRollbackTombstoneUser(anyString());
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void providerThatPersistsThenThrowsStillHasKnownKeyRollbackCleanup() {
        Set<String> persistedKeys = new HashSet<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            persistedKeys.add(invocation.getArgument(0));
            throw new ObjectStorageException("response lost");
        }).when(objectStorage).put(
            anyString(), any(UploadSource.class), anyString(), any(byte[].class));
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThrows(ServiceUnavailableException.class, () -> service.storeAttachment(
                12, "card.pdf", "application/pdf", new byte[] {1, 2, 3}));

            String key = persistedKeys.iterator().next();
            List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
            assertEquals(1, synchronizations.size());
            synchronizations.getFirst().afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            verify(deletionRetryQueue).enqueueRollbackTombstoneTenant(12, key);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void ignoresLegacyUrlsDuringManagedCleanup() {
        service.deleteAttachment(7, "/attachments/company/7/legacy.pdf");
        verify(deletionRetryQueue, never()).enqueueRollbackTombstoneTenant(
            org.mockito.ArgumentMatchers.anyInt(), anyString());
    }

    @Test
    void oldObjectDeletionIsDurablyQueuedWithoutBlockingTheCommitCallback() {
        String objectName = "550e8400-e29b-41d4-a716-446655440000.pdf";
        String url = "/api/attachments/content/" + objectName;
        String key = "workspaces/12/attachments/" + objectName;
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.deleteAttachmentAfterCommit(12, url);

            verify(deletionRetryQueue).enqueueTenantInCurrentTransaction(12, key);
            verify(deletionRetryQueue, never()).processTenant(12, key);
            assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void oldObjectDeletionDoesNotProcessWhenMetadataTransactionRollsBack() {
        String objectName = "550e8400-e29b-41d4-a716-446655440000.pdf";
        String url = "/api/attachments/content/" + objectName;
        String key = "workspaces/12/attachments/" + objectName;
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.deleteAttachmentAfterCommit(12, url);

            verify(deletionRetryQueue).enqueueTenantInCurrentTransaction(12, key);
            verify(deletionRetryQueue, never()).processTenant(12, key);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void userObjectDeletionIsDurablyQueuedInTheControlTransaction() {
        String objectName = "550e8400-e29b-41d4-a716-446655440000.jpg";
        String url = "/api/users/8/profile-picture/" + objectName;
        String key = "users/8/profile-images/" + objectName;
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.deleteUserImageAfterCommit(8, url);

            verify(deletionRetryQueue).enqueueUserInCurrentTransaction(key);
            verify(deletionRetryQueue, never()).processUser(key);
            assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void readinessProbeNeverBlocksTheCallerAndCachesItsResult() throws Exception {
        CountDownLatch probeStarted = new CountDownLatch(1);
        CountDownLatch releaseProbe = new CountDownLatch(1);
        when(objectStorage.isReady()).thenAnswer(invocation -> {
            probeStarted.countDown();
            assertTrue(releaseProbe.await(5, TimeUnit.SECONDS));
            return true;
        });

        assertFalse(service.isReady());
        assertTrue(probeStarted.await(5, TimeUnit.SECONDS));
        assertFalse(service.isReady());
        releaseProbe.countDown();
        assertTrue(awaitReady());

        assertTrue(service.isReady());
        verify(objectStorage, times(1)).isReady();
    }

    @Test
    void maintenanceMigrationDoesNotStartStorageReadinessWork() {
        properties.getLegacyMigration().setMode(LegacyMigrationMode.MIGRATE);

        assertFalse(service.isReady());

        verify(objectStorage, never()).isReady();
    }

    @Test
    void storageFailureCannotBeOverwrittenByAnOlderReadinessProbe() throws Exception {
        properties.setReadinessCacheTtlMs(20);
        CountDownLatch staleProbeStarted = new CountDownLatch(1);
        CountDownLatch releaseStaleProbe = new CountDownLatch(1);
        CountDownLatch staleProbeCompleted = new CountDownLatch(1);
        AtomicInteger probes = new AtomicInteger();
        when(objectStorage.isReady()).thenAnswer(invocation -> {
            int probe = probes.incrementAndGet();
            if (probe == 1) return true;
            if (probe == 2) {
                staleProbeStarted.countDown();
                assertTrue(releaseStaleProbe.await(5, TimeUnit.SECONDS));
                staleProbeCompleted.countDown();
                return true;
            }
            return false;
        });
        doThrow(new ObjectStorageException("unavailable")).when(objectStorage).put(
            anyString(), any(UploadSource.class), anyString(), any(byte[].class));

        assertFalse(service.isReady());
        assertTrue(awaitReady());
        Thread.sleep(30);
        assertTrue(service.isReady());
        assertTrue(staleProbeStarted.await(5, TimeUnit.SECONDS));

        assertThrows(ServiceUnavailableException.class, () -> inTransaction(
            () -> service.storeAttachment(
                12, "card.pdf", "application/pdf", new byte[] {1, 2, 3})));
        releaseStaleProbe.countDown();
        assertTrue(staleProbeCompleted.await(5, TimeUnit.SECONDS));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (probes.get() < 3 && System.nanoTime() < deadline) {
            assertFalse(service.isReady());
            Thread.sleep(5);
        }

        assertTrue(probes.get() >= 3);
        assertFalse(service.isReady());
    }

    private boolean awaitReady() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (service.isReady()) {
                return true;
            }
            Thread.sleep(5);
        }
        return false;
    }

    private static <T> T inTransaction(Supplier<T> work) {
        TransactionSynchronizationManager.initSynchronization();
        try {
            T result = work.get();
            completeSynchronizations(TransactionSynchronization.STATUS_COMMITTED);
            return result;
        } catch (RuntimeException exception) {
            completeSynchronizations(TransactionSynchronization.STATUS_ROLLED_BACK);
            throw exception;
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static void completeSynchronizations(int status) {
        for (TransactionSynchronization synchronization
                : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(status);
        }
    }

    private static byte[] png(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", output));
        return output.toByteArray();
    }
}
