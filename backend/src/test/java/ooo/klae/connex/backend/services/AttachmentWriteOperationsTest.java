package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicReference;
import java.security.MessageDigest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.storage.ManagedObjectService;
import ooo.klae.connex.backend.storage.ManagedObjectService.StoredBinary;
import ooo.klae.connex.backend.storage.UploadContentInspector.InspectedUpload;
import ooo.klae.connex.backend.storage.UploadPolicy.UploadFormat;
import ooo.klae.connex.backend.storage.UploadPolicy.UploadPurpose;
import ooo.klae.connex.backend.storage.UploadSource;

/** Verifies attachment writes remain tenant-validated and transaction-bounded. */
@ExtendWith(MockitoExtension.class)
class AttachmentWriteOperationsTest {
    @Mock private AttachmentMapper attachmentMapper;
    @Mock private AiChatMapper aiChatMapper;
    @Mock private CompanyMapper companyMapper;
    @Mock private PersonMapper personMapper;
    @Mock private DealMapper dealMapper;
    @Mock private NoteMapper noteMapper;
    @Mock private AuditService auditService;
    @Mock private ManagedObjectService managedObjectService;

    private AttachmentWriteOperations operations;

    @BeforeEach
    void setUp() {
        operations = new AttachmentWriteOperations(
            attachmentMapper,
            aiChatMapper,
            companyMapper,
            personMapper,
            dealMapper,
            noteMapper,
            auditService,
            managedObjectService);
    }

    @Test
    void createExternalPinsWorkspaceAndValidatesTenantTargetBeforeInsert() {
        Attachment attachment = attachment("company", 41, "https://example.com/file.pdf");
        attachment.setWorkspaceId(999);
        attachment.setId(77);
        when(companyMapper.exists(5, 41)).thenReturn(true);
        when(attachmentMapper.getCreatedById(5, 77)).thenReturn(attachment);

        Attachment created = operations.createExternal(5, attachment);

        assertEquals(attachment, created);
        assertEquals(5, attachment.getWorkspaceId());
        verify(attachmentMapper).insert(attachment);
    }

    @Test
    void createExternalRejectsMissingTargetsBeforeInsert() {
        Attachment missing = attachment("person", 42, "https://example.com/missing.pdf");
        when(personMapper.exists(5, 42)).thenReturn(false);

        assertThrows(ResourceNotFoundException.class,
            () -> operations.createExternal(5, missing));

        verify(attachmentMapper, never()).insert(missing);
    }

    @Test
    void createExternalAllowsExistingNoteTarget() {
        Attachment attachment = attachment("note", 43, "https://example.com/note.pdf");
        attachment.setId(77);
        when(noteMapper.exists(5, 43)).thenReturn(true);
        when(attachmentMapper.getCreatedById(5, 77)).thenReturn(attachment);

        Attachment created = operations.createExternal(5, attachment);

        assertEquals(attachment, created);
        verify(noteMapper).exists(5, 43);
        verify(attachmentMapper).insert(attachment);
    }

    @Test
    void uploadStoresOnlyAfterTenantTargetValidation() {
        User uploader = new User();
        uploader.setId(7);
        UploadSource source = UploadSource.from("file.pdf", "application/pdf", new byte[] { 1, 2 });
        when(dealMapper.exists(5, 43)).thenReturn(true);
        when(managedObjectService.storeAttachment(5, UploadPurpose.ATTACHMENT, source)).thenReturn(
            new StoredBinary("/api/attachments/content/token.pdf", "file.pdf", "application/pdf", 2));
        AtomicReference<Attachment> insertedAttachment = new AtomicReference<>();
        doAnswer(invocation -> {
            Attachment inserted = invocation.getArgument(0);
            inserted.setId(77);
            insertedAttachment.set(inserted);
            return 1;
        }).when(attachmentMapper).insert(any(Attachment.class));
        when(attachmentMapper.getCreatedById(5, 77))
            .thenAnswer(invocation -> insertedAttachment.get());

        Attachment attachment = operations.upload(5, "deal", 43, source, uploader);

        assertEquals(5, attachment.getWorkspaceId());
        assertEquals("deal", attachment.getEntityType());
        assertEquals(43, attachment.getEntityId());
        assertEquals(uploader, attachment.getUploadedBy());
        verify(attachmentMapper).insert(attachment);
    }

    @Test
    void assistantUploadStoresTheExactInspectedArtifact() {
        User uploader = new User();
        uploader.setId(7);
        byte[] content = {1, 2, 3};
        InspectedUpload upload = inspected(content);
        StoredBinary stored = new StoredBinary(
            "/api/attachments/content/token.jpg", "image.jpg", "image/jpeg", content.length);
        when(aiChatMapper.sessionExists(5, 43)).thenReturn(true);
        when(managedObjectService.storeInspectedAttachment(5, upload)).thenReturn(stored);
        AtomicReference<Attachment> insertedAttachment = new AtomicReference<>();
        doAnswer(invocation -> {
            Attachment inserted = invocation.getArgument(0);
            inserted.setId(77);
            insertedAttachment.set(inserted);
            return 1;
        }).when(attachmentMapper).insert(any(Attachment.class));
        when(attachmentMapper.getCreatedById(5, 77))
            .thenAnswer(invocation -> insertedAttachment.get());

        Attachment attachment = operations.uploadAssistantSession(5, 43, upload, uploader);

        assertEquals(content.length, attachment.getSize());
        verify(managedObjectService).storeInspectedAttachment(5, upload);
        verify(managedObjectService, never()).storeAttachment(
            org.mockito.ArgumentMatchers.anyInt(),
            any(UploadPurpose.class),
            any(UploadSource.class));
    }

    @Test
    void createRejectsMissingAuthoritativeReload() {
        Attachment attachment = attachment("company", 41, "https://example.com/file.pdf");
        attachment.setId(77);
        when(companyMapper.exists(5, 41)).thenReturn(true);
        when(attachmentMapper.getCreatedById(5, 77)).thenReturn(null);

        assertThrows(IllegalStateException.class,
            () -> operations.createExternal(5, attachment));

        verify(attachmentMapper).insert(attachment);
    }

    @Test
    void publicWriteMethodsDeclareTransactions() throws NoSuchMethodException {
        assertNotNull(AttachmentWriteOperations.class
            .getMethod("createExternal", int.class, Attachment.class)
            .getAnnotation(Transactional.class));
        assertNotNull(AttachmentWriteOperations.class
            .getMethod("createManaged", int.class, Attachment.class)
            .getAnnotation(Transactional.class));
        assertNotNull(AttachmentWriteOperations.class
            .getMethod(
                "upload", int.class, String.class, int.class, UploadSource.class, User.class)
            .getAnnotation(Transactional.class));
        assertNotNull(AttachmentWriteOperations.class
            .getMethod(
                "uploadInlineImage", int.class, String.class, int.class, UploadSource.class, User.class)
            .getAnnotation(Transactional.class));
        assertNotNull(AttachmentWriteOperations.class
            .getMethod(
                "uploadAssistantSession",
                int.class,
                int.class,
                InspectedUpload.class,
                User.class)
            .getAnnotation(Transactional.class));
    }

    private static InspectedUpload inspected(byte[] content) {
        try {
            return new InspectedUpload(
                "image.jpg",
                "image/jpeg",
                "jpg",
                UploadFormat.JPEG,
                content,
                MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Attachment attachment(String entityType, int entityId, String url) {
        Attachment attachment = new Attachment();
        attachment.setEntityType(entityType);
        attachment.setEntityId(entityId);
        attachment.setFileName("file.pdf");
        attachment.setUrl(url);
        attachment.setContentType("application/pdf");
        attachment.setSize(2L);
        return attachment;
    }
}
