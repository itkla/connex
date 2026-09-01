package ooo.klae.connex.backend.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.dto.ActiveObjectReference;
import ooo.klae.connex.backend.exceptions.RequestBodyTooLargeException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.exceptions.UnsupportedUploadMediaTypeException;
import ooo.klae.connex.backend.storage.ManagedObjectService.ManagedContent;
import ooo.klae.connex.backend.storage.ManagedObjectService.StoredArtifact;
import ooo.klae.connex.backend.storage.ManagedObjectService.StoredBinary;
import ooo.klae.connex.backend.storage.ObjectStorageProperties.LegacyMigrationMode;
import ooo.klae.connex.backend.storage.UploadContentInspector.InspectedUpload;
import ooo.klae.connex.backend.storage.UploadPolicy.UploadFormat;
import ooo.klae.connex.backend.storage.UploadPolicy.UploadPurpose;
import ooo.klae.connex.backend.storage.UploadPolicy.ValidatedUpload;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class ManagedObjectServiceTest {
    @Mock ObjectStorage objectStorage;
    @Mock ObjectDeletionRetryQueue deletionRetryQueue;
    @Mock WorkspaceObjectStorageQuotaService quotaService;
    @Mock UserImageReplacementAdmissionService userImageAdmissionService;

    private ObjectStorageProperties properties;
    private ManagedObjectService service;
    private BoundedImageValidationExecutor imageValidationExecutor;
    private final MutableNanoTime readinessNanoTime = new MutableNanoTime();
    private final Queue<Runnable> readinessTasks = new ArrayDeque<>();
    private Runnable readinessSnapshotPublicationHook = () -> {};

    @BeforeEach
    void setUp() {
        properties = new ObjectStorageProperties();
        imageValidationExecutor = new BoundedImageValidationExecutor();
        AtomicInteger tombstoneIds = new AtomicInteger(1);
        org.mockito.Mockito.lenient()
            .when(deletionRetryQueue.prepareTenantWrite(anyInt(), anyString()))
            .thenAnswer(invocation -> new ObjectDeletionTombstone(
                tombstoneIds.getAndIncrement(), invocation.getArgument(1)));
        org.mockito.Mockito.lenient()
            .when(deletionRetryQueue.prepareUserWrite(anyString()))
            .thenAnswer(invocation -> new ObjectDeletionTombstone(
                tombstoneIds.getAndIncrement(), invocation.getArgument(0)));
        service = service(properties);
    }

    @AfterEach
    void tearDown() {
        imageValidationExecutor.close();
    }

    private ManagedObjectService service(ObjectStorageProperties configuredProperties) {
        UploadPolicy uploadPolicy = new UploadPolicy(configuredProperties);
        UploadContentInspector uploadContentInspector = passthroughInspector(uploadPolicy);
        return service(configuredProperties, uploadPolicy, uploadContentInspector);
    }

    /** Builds a service around the supplied upload boundary for focused storage tests. */
    private ManagedObjectService service(
            ObjectStorageProperties configuredProperties,
            UploadPolicy uploadPolicy,
            UploadContentInspector uploadContentInspector) {
        ImageUploadValidator imageValidator = new ImageUploadValidator(
            configuredProperties,
            uploadPolicy,
            new ImageDecodeAdmissionService(configuredProperties),
            imageValidationExecutor);
        return new ManagedObjectService(
            objectStorage,
            deletionRetryQueue,
            uploadPolicy,
            uploadContentInspector,
            imageValidator,
            configuredProperties,
            quotaService,
            userImageAdmissionService,
            new ManagedObjectWriteAdmissionService(configuredProperties),
            new ManagedObjectReadAdmissionService(configuredProperties, () -> 9),
            readinessNanoTime,
            task -> readinessTasks.add(task),
            () -> readinessSnapshotPublicationHook.run());
    }

    /** Builds the real upload inspector used by document-artifact boundary tests. */
    private UploadContentInspector realInspector(UploadPolicy uploadPolicy) {
        ImageUploadValidator imageValidator = new ImageUploadValidator(
            properties,
            uploadPolicy,
            new ImageDecodeAdmissionService(properties),
            imageValidationExecutor);
        return new UploadContentInspector(uploadPolicy, imageValidator, new ObjectMapper());
    }

    private static UploadContentInspector passthroughInspector(UploadPolicy uploadPolicy) {
        UploadContentInspector inspector = org.mockito.Mockito.mock(UploadContentInspector.class);
        org.mockito.Mockito.lenient()
            .when(inspector.inspect(any(UploadPurpose.class), any(UploadSource.class)))
            .thenAnswer(invocation -> {
                UploadPurpose purpose = invocation.getArgument(0, UploadPurpose.class);
                UploadSource source = invocation.getArgument(1, UploadSource.class);
                ValidatedUpload metadata = uploadPolicy.validate(purpose, source);
                byte[] content;
                try (InputStream input = source.openStream()) {
                    content = input.readAllBytes();
                } catch (IOException exception) {
                    throw new ServiceUnavailableException("Uploaded file could not be read");
                }
                return new InspectedUpload(
                    metadata.fileName(),
                    metadata.contentType(),
                    metadata.extension(),
                    metadata.format(),
                    content,
                    sha256(content));
            });
        return inspector;
    }

    private static byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static InspectedUpload inspected(byte[] content) {
        return new InspectedUpload(
            "legacy.pdf",
            "application/pdf",
            "pdf",
            UploadFormat.PDF,
            content,
            sha256(content));
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
    void storesTheAuthoritativeInspectedArtifactByteIdentically() throws Exception {
        byte[] bytes = {9, 8, 7, 6};
        InspectedUpload upload = inspected(bytes);
        ArgumentCaptor<UploadSource> storedSource = ArgumentCaptor.forClass(UploadSource.class);
        ArgumentCaptor<byte[]> checksum = ArgumentCaptor.forClass(byte[].class);

        StoredBinary stored = inTransaction(
            () -> service.storeInspectedAttachment(17, upload));

        verify(objectStorage).put(
            anyString(),
            storedSource.capture(),
            org.mockito.ArgumentMatchers.eq("application/pdf"),
            checksum.capture());
        try (InputStream input = storedSource.getValue().openStream()) {
            assertArrayEquals(bytes, input.readAllBytes());
        }
        assertArrayEquals(upload.sha256(), checksum.getValue());
        assertEquals(bytes.length, stored.size());
    }

    @Test
    void rejectsDocumentArtifactDeclaredAsPdfWithoutPdfContent() {
        UploadPolicy uploadPolicy = new UploadPolicy(properties);
        try (UploadContentInspector uploadContentInspector = realInspector(uploadPolicy)) {
            ManagedObjectService inspectedService = service(
                properties, uploadPolicy, uploadContentInspector);

            assertThrows(
                UnsupportedUploadMediaTypeException.class,
                () -> inTransaction(() -> inspectedService.storeDocumentArtifact(
                    17,
                    23,
                    "signed_document",
                    "application/pdf",
                    "not a PDF".getBytes(StandardCharsets.UTF_8))));
        }

        verify(objectStorage, never()).put(
            anyString(), any(UploadSource.class), anyString(), any(byte[].class));
    }

    @Test
    void rejectsDocumentArtifactPdfWithActiveCatalogEntry() {
        UploadPolicy uploadPolicy = new UploadPolicy(properties);
        byte[] activePdf = validPdf("/OpenAction << /S /JavaScript /JS (alert) >>");
        try (UploadContentInspector uploadContentInspector = realInspector(uploadPolicy)) {
            ManagedObjectService inspectedService = service(
                properties, uploadPolicy, uploadContentInspector);

            assertThrows(
                UnsupportedUploadMediaTypeException.class,
                () -> inTransaction(() -> inspectedService.storeDocumentArtifact(
                    17,
                    23,
                    "signed_document",
                    "application/pdf",
                    activePdf)));
        }

        verify(objectStorage, never()).put(
            anyString(), any(UploadSource.class), anyString(), any(byte[].class));
    }

    @Test
    void storesCleanDocumentArtifactPdfAndJson() {
        UploadPolicy uploadPolicy = new UploadPolicy(properties);
        byte[] pdf = validPdf("");
        byte[] json = "{\"safe\":true}".getBytes(StandardCharsets.UTF_8);
        try (UploadContentInspector uploadContentInspector = realInspector(uploadPolicy)) {
            ManagedObjectService inspectedService = service(
                properties, uploadPolicy, uploadContentInspector);

            StoredArtifact pdfArtifact = inTransaction(
                () -> inspectedService.storeDocumentArtifact(
                    17, 23, "signed_document", "application/pdf", pdf));
            StoredArtifact jsonArtifact = inTransaction(
                () -> inspectedService.storeDocumentArtifact(
                    17, 23, "certificate", "application/json", json));

            assertEquals("application/pdf", pdfArtifact.contentType());
            assertEquals(pdf.length, pdfArtifact.byteLength());
            assertEquals("application/json", jsonArtifact.contentType());
            assertEquals(json.length, jsonArtifact.byteLength());
        }
    }

    @Test
    void rejectsAnInspectedArtifactAboveTheConfiguredStorageCeiling() {
        ObjectStorageProperties limited = new ObjectStorageProperties();
        limited.setMaxUploadBytes(3);
        ManagedObjectService limitedService = service(limited);

        assertThrows(
            RequestBodyTooLargeException.class,
            () -> inTransaction(
                () -> limitedService.storeInspectedAttachment(17, inspected(new byte[] {1, 2, 3, 4}))));

        verify(objectStorage, never()).put(
            anyString(), any(UploadSource.class), anyString(), any(byte[].class));
    }

    @Test
    void migrationKeysAreStableAcrossRetriesAndDistinctAcrossRecords() {
        byte[] bytes = "legacy attachment".getBytes(StandardCharsets.UTF_8);
        InspectedUpload upload = inspected(bytes);

        StoredBinary first = inTransaction(() -> service.storeMigratedAttachment(
            17, 23, "/attachments/person/legacy.pdf", upload));
        StoredBinary retry = inTransaction(() -> service.storeMigratedAttachment(
            17, 23, "/attachments/person/legacy.pdf", upload));
        StoredBinary otherRecord = inTransaction(() -> service.storeMigratedAttachment(
            17, 24, "/attachments/person/legacy.pdf", upload));

        assertEquals(first.url(), retry.url());
        assertFalse(first.url().equals(otherRecord.url()));
        assertTrue(first.url().matches(
            "/api/attachments/content/[0-9a-f-]{36}\\.pdf"));
    }

    @Test
    void deterministicMigrationPreparesAndCancelsCleanupAroundANewWrite() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.storeMigratedAttachment(
                17,
                23,
                "/attachments/person/legacy.pdf",
                inspected("legacy attachment".getBytes(StandardCharsets.UTF_8)));

            verify(deletionRetryQueue).prepareTenantWrite(
                org.mockito.ArgumentMatchers.eq(17), anyString());
            verify(deletionRetryQueue).cancelTenantInCurrentTransaction(
                org.mockito.ArgumentMatchers.eq(17), any(ObjectDeletionTombstone.class));
            assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void deterministicMigrationLeavesAPrecommittedCleanupIfMetadataRollsBack() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            StoredBinary stored = service.storeMigratedAttachment(
                17,
                23,
                "/attachments/person/legacy.pdf",
                inspected("legacy attachment".getBytes(StandardCharsets.UTF_8)));
            String token = stored.url().substring(stored.url().lastIndexOf('/') + 1);

            verify(deletionRetryQueue).prepareTenantWrite(
                17, "workspaces/17/attachments/" + token);
            verify(deletionRetryQueue).cancelTenantInCurrentTransaction(
                org.mockito.ArgumentMatchers.eq(17),
                withObjectKey("workspaces/17/attachments/" + token));
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
                inspected(bytes));

            assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
            verify(deletionRetryQueue).cancelTenantInCurrentTransaction(
                org.mockito.ArgumentMatchers.eq(17),
                any(ObjectDeletionTombstone.class));
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
                inspected(bytes));
            String token = stored.url().substring(stored.url().lastIndexOf('/') + 1);
            String key = "workspaces/17/attachments/" + token;

            verify(quotaService).reserve(17, key, bytes.length);
            verify(deletionRetryQueue).cancelTenantInCurrentTransaction(
                org.mockito.ArgumentMatchers.eq(17), withObjectKey(key));
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
    void tenantExportRefusesAnAttachmentOwnerMismatchBeforeProviderAccess() {
        String token = "550e8400-e29b-41d4-a716-446655440000.pdf";
        ActiveObjectReference reference = new ActiveObjectReference(
            "workspaces/9/attachments/" + token,
            "attachment",
            41,
            "/api/attachments/content/" + token,
            3L);

        assertThrows(
            IllegalStateException.class,
            () -> service.openTenantExportObject(9, 7, reference, Duration.ofSeconds(1)));

        verify(objectStorage, never()).get(anyString());
    }

    @Test
    void nullProviderResultReleasesManagedReadAdmissionAtLimitOne() throws Exception {
        ObjectStorageProperties limited = new ObjectStorageProperties();
        limited.setMaxConcurrentReads(1);
        limited.setMaxConcurrentReadsPerUser(1);
        ManagedObjectService limitedService = service(limited);
        String objectName = "550e8400-e29b-41d4-a716-446655440001.pdf";
        Attachment attachment = attachment("/api/attachments/content/" + objectName);
        byte[] bytes = {4};
        when(objectStorage.get("workspaces/9/attachments/" + objectName))
            .thenReturn(null)
            .thenReturn(new StoredObject(new ByteArrayInputStream(bytes), bytes.length));

        assertThrows(
            ResourceNotFoundException.class,
            () -> limitedService.openAttachment(9, attachment));
        try (ManagedContent content = limitedService.openAttachment(9, attachment)) {
            assertEquals(4, content.inputStream().read());
        }
    }

    @Test
    void providerOpenFailureReleasesManagedReadAdmissionAtLimitOne() throws Exception {
        ObjectStorageProperties limited = new ObjectStorageProperties();
        limited.setMaxConcurrentReads(1);
        limited.setMaxConcurrentReadsPerUser(1);
        ManagedObjectService limitedService = service(limited);
        String objectName = "550e8400-e29b-41d4-a716-446655440002.pdf";
        Attachment attachment = attachment("/api/attachments/content/" + objectName);
        byte[] bytes = {5};
        when(objectStorage.get("workspaces/9/attachments/" + objectName))
            .thenThrow(new ObjectStorageException("unavailable"))
            .thenReturn(new StoredObject(new ByteArrayInputStream(bytes), bytes.length));

        assertThrows(
            ServiceUnavailableException.class,
            () -> limitedService.openAttachment(9, attachment));
        try (ManagedContent content = limitedService.openAttachment(9, attachment)) {
            assertEquals(5, content.inputStream().read());
        }
    }

    @Test
    void storePersistsCleanupBeforeProviderWriteAndCancelsItInMetadataTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            StoredBinary stored = service.storeAttachment(
                12, "card.pdf", "application/pdf", new byte[] {1, 2, 3});
            String token = stored.url().substring(stored.url().lastIndexOf('/') + 1);
            String key = "workspaces/12/attachments/" + token;
            InOrder order = inOrder(deletionRetryQueue, quotaService, objectStorage);
            order.verify(deletionRetryQueue).prepareTenantWrite(12, key);
            order.verify(deletionRetryQueue).lockTenantInCurrentTransaction(
                org.mockito.ArgumentMatchers.eq(12), withObjectKey(key));
            order.verify(quotaService).reserve(12, key, 3);
            order.verify(objectStorage).put(
                org.mockito.ArgumentMatchers.eq(key),
                any(UploadSource.class),
                org.mockito.ArgumentMatchers.eq("application/pdf"),
                any(byte[].class));
            order.verify(deletionRetryQueue).cancelTenantInCurrentTransaction(
                org.mockito.ArgumentMatchers.eq(12), withObjectKey(key));
            assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void storeCleanupDoesNotDependOnAnAfterCompletionStatus() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            StoredBinary stored = service.storeAttachment(
                12, "card.pdf", "application/pdf", new byte[] {1, 2, 3});
            String token = stored.url().substring(stored.url().lastIndexOf('/') + 1);
            verify(deletionRetryQueue).prepareTenantWrite(12,
                "workspaces/12/attachments/" + token);
            assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void userStorePrecommitsControlPlaneCleanup() throws Exception {
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.storeUserImage(
                8, UploadSource.from("portrait.png", "image/png", png(10, 20)));
            verify(userImageAdmissionService).requireAllowed(8);
            verify(deletionRetryQueue).prepareUserWrite(anyString());
            verify(deletionRetryQueue).cancelUserInCurrentTransaction(
                any(ObjectDeletionTombstone.class));
            assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void cleanupPreparationFailurePreventsProviderWrite() throws Exception {
        doThrow(new ServiceUnavailableException("cleanup unavailable"))
            .when(deletionRetryQueue).prepareUserWrite(anyString());
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThrows(ServiceUnavailableException.class, () -> service.storeUserImage(
                8, UploadSource.from("portrait.png", "image/png", png(10, 20))));
            verify(objectStorage, never()).put(
                anyString(), any(UploadSource.class), anyString(), any(byte[].class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void replacedTombstonePreventsQuotaReservationAndProviderWrite() {
        doThrow(new ServiceUnavailableException("tombstone changed"))
            .when(deletionRetryQueue).lockTenantInCurrentTransaction(
                org.mockito.ArgumentMatchers.eq(12), any(ObjectDeletionTombstone.class));
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThrows(ServiceUnavailableException.class, () -> service.storeAttachment(
                12, "card.pdf", "application/pdf", new byte[] {1, 2, 3}));

            verify(quotaService, never()).reserve(
                org.mockito.ArgumentMatchers.anyInt(), anyString(),
                org.mockito.ArgumentMatchers.anyLong());
            verify(objectStorage, never()).put(
                anyString(), any(UploadSource.class), anyString(), any(byte[].class));
            verify(deletionRetryQueue, never()).cancelTenantInCurrentTransaction(
                org.mockito.ArgumentMatchers.eq(12), any(ObjectDeletionTombstone.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void providerThatPersistsThenThrowsStillHasKnownKeyRollbackCleanup() {
        java.util.concurrent.atomic.AtomicReference<String> persistedKey =
            new java.util.concurrent.atomic.AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            persistedKey.set(invocation.getArgument(0));
            throw new ObjectStorageException("response lost");
        }).when(objectStorage).put(
            anyString(), any(UploadSource.class), anyString(), any(byte[].class));
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThrows(ServiceUnavailableException.class, () -> service.storeAttachment(
                12, "card.pdf", "application/pdf", new byte[] {1, 2, 3}));

            verify(deletionRetryQueue).prepareTenantWrite(12, persistedKey.get());
            verify(deletionRetryQueue, never()).cancelTenantInCurrentTransaction(
                org.mockito.ArgumentMatchers.eq(12), any(ObjectDeletionTombstone.class));
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
    void readinessProbeNeverBlocksTheCallerAndCachesItsResult() {
        properties.setReadinessCacheTtlMs(1_000);
        when(objectStorage.isReady()).thenReturn(true);

        assertFalse(service.isReady());
        verify(objectStorage, never()).isReady();
        assertEquals(1, readinessTasks.size());
        assertFalse(service.isReady());
        verify(objectStorage, never()).isReady();
        assertEquals(1, readinessTasks.size());

        readinessTasks.remove().run();
        clearInvocations(objectStorage);
        readinessNanoTime.advanceMillis(999);

        assertTrue(service.isReady());
        verify(objectStorage, never()).isReady();
        assertTrue(readinessTasks.isEmpty());
    }

    @Test
    void cachedReadinessNeverStartsAnExpiredStorageProbe() {
        properties.setReadinessCacheTtlMs(1);
        when(objectStorage.isReady()).thenReturn(true);

        service.run(null);
        assertEquals(1, readinessTasks.size());
        readinessTasks.remove().run();
        assertTrue(service.isReadyCached());
        clearInvocations(objectStorage);
        readinessNanoTime.advanceMillis(1);

        assertTrue(service.isReadyCached());
        verify(objectStorage, never()).isReady();
        assertTrue(readinessTasks.isEmpty());
    }

    @Test
    void maintenanceMigrationDoesNotStartStorageReadinessWork() {
        properties.getLegacyMigration().setMode(LegacyMigrationMode.MIGRATE);

        assertFalse(service.isReady());

        verify(objectStorage, never()).isReady();
        assertTrue(readinessTasks.isEmpty());
    }

    @Test
    void storageFailureCannotBeOverwrittenByAnOlderReadinessProbe() {
        properties.setReadinessCacheTtlMs(20);
        when(objectStorage.isReady()).thenReturn(true, true, false);
        doThrow(new ObjectStorageException("unavailable")).when(objectStorage).put(
            anyString(), any(UploadSource.class), anyString(), any(byte[].class));

        assertFalse(service.isReady());
        assertEquals(1, readinessTasks.size());
        readinessTasks.remove().run();
        assertTrue(service.isReady());
        assertTrue(readinessTasks.isEmpty());
        readinessNanoTime.advanceMillis(20);
        assertTrue(service.isReady());
        assertEquals(1, readinessTasks.size());

        CompletableFuture<Void> generationConfirmed = new CompletableFuture<>();
        CompletableFuture<Void> publicationReleased = new CompletableFuture<>();
        readinessSnapshotPublicationHook = () -> {
            generationConfirmed.complete(null);
            publicationReleased.join();
        };
        CompletableFuture<Void> probeCompleted = new CompletableFuture<>();
        startTask(readinessTasks.remove(), probeCompleted);
        generationConfirmed.join();

        CompletableFuture<Void> storageFailureCompleted = new CompletableFuture<>();
        Thread storageFailure = startTask(
            () -> assertThrows(
                ServiceUnavailableException.class,
                () -> inTransaction(() -> service.storeAttachment(
                    12, "card.pdf", "application/pdf", new byte[] {1, 2, 3}))),
            storageFailureCompleted);
        waitUntilReadinessInvalidationIsBlockedOrCompleted(storageFailure);
        publicationReleased.complete(null);
        probeCompleted.join();
        storageFailureCompleted.join();

        assertFalse(service.isReady());
        assertEquals(1, readinessTasks.size());
        readinessTasks.remove().run();
        assertFalse(service.isReady());
        assertTrue(readinessTasks.isEmpty());
    }

    private static final class MutableNanoTime implements LongSupplier {
        private long nanos = Duration.ofHours(1).toNanos();

        @Override
        public long getAsLong() {
            return nanos;
        }

        void advanceMillis(long millis) {
            nanos += TimeUnit.MILLISECONDS.toNanos(millis);
        }
    }

    private static Thread startTask(Runnable task, CompletableFuture<Void> completion) {
        return Thread.ofPlatform().start(() -> {
            try {
                task.run();
                completion.complete(null);
            } catch (Throwable throwable) {
                completion.completeExceptionally(throwable);
            }
        });
    }

    private static void waitUntilReadinessInvalidationIsBlockedOrCompleted(Thread thread) {
        while (thread.isAlive()) {
            if (thread.getState() == Thread.State.BLOCKED
                    && isExecutingReadinessInvalidation(thread)) {
                return;
            }
            Thread.onSpinWait();
        }
    }

    private static boolean isExecutingReadinessInvalidation(Thread thread) {
        for (StackTraceElement frame : thread.getStackTrace()) {
            if (frame.getClassName().equals(ManagedObjectService.class.getName())
                    && frame.getMethodName().equals("markUnavailable")) {
                return true;
            }
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

    private static ObjectDeletionTombstone withObjectKey(String key) {
        return argThat(tombstone -> tombstone != null && key.equals(tombstone.objectKey()));
    }

    private static Attachment attachment(String url) {
        Attachment attachment = new Attachment();
        attachment.setUrl(url);
        attachment.setFileName("report.pdf");
        attachment.setContentType("application/pdf");
        return attachment;
    }

    /** Builds a structurally valid inert PDF with optional synthetic catalog syntax. */
    private static byte[] validPdf(String catalogAddition) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeAscii(output, "%PDF-1.4\n");
        int catalogOffset = output.size();
        writeAscii(output, "1 0 obj\n<< /Type /Catalog /Pages 2 0 R "
            + catalogAddition + " >>\nendobj\n");
        int pagesOffset = output.size();
        writeAscii(output, "2 0 obj\n<< /Type /Pages /Count 1 /Kids [3 0 R] >>\nendobj\n");
        int pageOffset = output.size();
        writeAscii(output,
            "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 72 72] >>\nendobj\n");
        int xrefOffset = output.size();
        writeAscii(output, "xref\n0 4\n0000000000 65535 f \n");
        writeAscii(output, String.format(Locale.ROOT, "%010d 00000 n \n", catalogOffset));
        writeAscii(output, String.format(Locale.ROOT, "%010d 00000 n \n", pagesOffset));
        writeAscii(output, String.format(Locale.ROOT, "%010d 00000 n \n", pageOffset));
        writeAscii(output, "trailer\n<< /Size 4 /Root 1 0 R >>\nstartxref\n");
        writeAscii(output, Integer.toString(xrefOffset));
        writeAscii(output, "\n%%EOF\n");
        return output.toByteArray();
    }

    /** Appends ASCII fixture syntax to an in-memory PDF. */
    private static void writeAscii(ByteArrayOutputStream output, String value) {
        output.writeBytes(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] png(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", output));
        return output.toByteArray();
    }
}
