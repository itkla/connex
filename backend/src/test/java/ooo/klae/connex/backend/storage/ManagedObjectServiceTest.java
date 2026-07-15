package ooo.klae.connex.backend.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

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
import ooo.klae.connex.backend.storage.ManagedObjectService.ManagedContent;
import ooo.klae.connex.backend.storage.ManagedObjectService.StoredBinary;

@ExtendWith(MockitoExtension.class)
class ManagedObjectServiceTest {
    @Mock ObjectStorage objectStorage;
    @Mock ObjectDeletionRetryQueue deletionRetryQueue;

    private ManagedObjectService service;

    @BeforeEach
    void setUp() {
        ObjectStorageProperties properties = new ObjectStorageProperties();
        UploadPolicy uploadPolicy = new UploadPolicy(properties);
        ImageUploadValidator imageValidator = new ImageUploadValidator(properties, uploadPolicy);
        service = new ManagedObjectService(
            objectStorage,
            deletionRetryQueue,
            uploadPolicy,
            imageValidator,
            properties);
    }

    @Test
    void storesOpaqueAttachmentReferenceAndTenantDerivedPrivateKey() throws Exception {
        byte[] bytes = "business card".getBytes(StandardCharsets.UTF_8);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> checksum = ArgumentCaptor.forClass(byte[].class);

        StoredBinary stored = service.storeAttachment(
            17,
            "card.pdf",
            "application/pdf",
            bytes);

        verify(objectStorage).put(key.capture(), any(UploadSource.class),
            org.mockito.ArgumentMatchers.eq("application/pdf"), checksum.capture());
        assertTrue(key.getValue().matches(
            "workspaces/17/attachments/[0-9a-f-]{36}\\.pdf"));
        assertTrue(stored.url().matches(
            "/api/attachments/content/[0-9a-f-]{36}\\.pdf"));
        assertFalse(stored.url().contains("workspaces/17"));
        assertEquals("card.pdf", stored.fileName());
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(bytes), checksum.getValue());
    }

    @Test
    void opensOnlyExactPersistedManagedReference() throws Exception {
        String token = "550e8400-e29b-41d4-a716-446655440000.pdf";
        Attachment attachment = new Attachment();
        attachment.setUrl("/api/attachments/content/" + token);
        attachment.setFileName("report.pdf");
        attachment.setContentType("application/pdf");
        byte[] bytes = { 1, 2, 3 };
        when(objectStorage.get("workspaces/9/attachments/" + token))
            .thenReturn(new StoredObject(new ByteArrayInputStream(bytes), bytes.length));

        try (ManagedContent content = service.openAttachment(9, attachment)) {
            assertArrayEquals(bytes, content.inputStream().readAllBytes());
            assertEquals("application/pdf", content.contentType());
        }

        attachment.setUrl("/api/attachments/content/" + token + "/extra");
        assertThrows(ResourceNotFoundException.class, () -> service.openAttachment(9, attachment));
    }

    @Test
    void rollbackCompensationDeletesOnlyAfterRollback() {
        String token = "550e8400-e29b-41d4-a716-446655440000.pdf";
        String url = "/api/attachments/content/" + token;
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.compensateAttachmentOnRollback(12, url);
            verify(objectStorage, never()).delete(anyString());
            List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
            assertEquals(1, synchronizations.size());
            synchronizations.getFirst().afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            verify(deletionRetryQueue).enqueueAndProcessTenant(
                12, "workspaces/12/attachments/" + token);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void ignoresLegacyUrlsDuringManagedCleanup() {
        service.deleteAttachment(7, "/attachments/company/7/legacy.pdf");
        verify(deletionRetryQueue, never()).enqueueAndProcessTenant(
            org.mockito.ArgumentMatchers.anyInt(), anyString());
    }

    @Test
    void oldObjectDeletionIsDurablyQueuedBeforeCommitAndProcessedOnlyAfterCommit() {
        String token = "550e8400-e29b-41d4-a716-446655440000.pdf";
        String url = "/api/attachments/content/" + token;
        String key = "workspaces/12/attachments/" + token;
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.deleteAttachmentAfterCommit(12, url);

            verify(deletionRetryQueue).enqueueTenantInCurrentTransaction(12, key);
            verify(deletionRetryQueue, never()).processTenant(12, key);
            List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
            synchronizations.getFirst().afterCommit();
            verify(deletionRetryQueue).processTenant(12, key);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void oldObjectDeletionDoesNotProcessWhenMetadataTransactionRollsBack() {
        String token = "550e8400-e29b-41d4-a716-446655440000.pdf";
        String url = "/api/attachments/content/" + token;
        String key = "workspaces/12/attachments/" + token;
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.deleteAttachmentAfterCommit(12, url);
            List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
            synchronizations.getFirst().afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

            verify(deletionRetryQueue).enqueueTenantInCurrentTransaction(12, key);
            verify(deletionRetryQueue, never()).processTenant(12, key);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void cachesFailClosedReadinessProbe() {
        when(objectStorage.isReady()).thenReturn(true);

        assertTrue(service.isReady());
        assertTrue(service.isReady());

        verify(objectStorage, times(1)).isReady();
    }
}
