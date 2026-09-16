package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicReference;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.ShareMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.storage.ManagedObjectService;
import ooo.klae.connex.backend.storage.ManagedObjectService.StoredBinary;
import ooo.klae.connex.backend.storage.ScannedUpload;
import ooo.klae.connex.backend.storage.malware.MalwareScanReport;
import ooo.klae.connex.backend.storage.malware.MalwareScanVerdict;
import ooo.klae.connex.backend.tenant.Permission;

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
    @Mock private WorkspaceService workspaceService;
    @Mock private WorkspaceMapper workspaceMapper;
    @Mock private ShareMapper shareMapper;

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
            managedObjectService,
            workspaceService,
            workspaceMapper,
            shareMapper);
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
    void createExternalRefusesCaseVariantOfManagedReferenceAsDuplicate() {
        String token = "550e8400-e29b-41d4-a716-446655440000.pdf";
        Attachment alias = attachment("company", 41, "/API/Attachments/Content/" + token);
        when(companyMapper.exists(5, 41)).thenReturn(true);
        when(attachmentMapper.countUrl(5, "/api/attachments/content/" + token)).thenReturn(1);

        assertThrows(ConflictException.class, () -> operations.createExternal(5, alias));

        verify(attachmentMapper, never()).insert(any(Attachment.class));
    }

    @ParameterizedTest
    @CsvSource({
        "HTTPS://EXAMPLE.COM/Case/Token%2fA?Key=Value#Part, https://example.com/Case/Token%2fA?Key=Value#Part",
        "HTTP://User@EXAMPLE.COM:8080/Case.PDF, http://User@example.com:8080/Case.PDF"
    })
    void createExternalCanonicalizesSchemeAndHostPreservingPathBytes(String submitted, String canonical) {
        Attachment attachment = attachment("company", 41, submitted);
        attachment.setId(77);
        when(companyMapper.exists(5, 41)).thenReturn(true);
        when(attachmentMapper.getCreatedById(5, 77)).thenReturn(attachment);

        Attachment created = operations.createExternal(5, attachment);

        assertEquals(canonical, created.getUrl());
        verify(attachmentMapper).countUrlInOtherWorkspaces(5, canonical);
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
        when(workspaceService.getCurrentUserId()).thenReturn(7);
        stubLockedAuthority(Map.of(7, Set.of(Permission.ATTACHMENT_CREATE)));
        ScannedUpload scanned = mock(ScannedUpload.class);
        when(scanned.report()).thenReturn(
            new MalwareScanReport(MalwareScanVerdict.CLEAN, null, null, "test-database", false));
        when(dealMapper.exists(5, 43)).thenReturn(true);
        when(dealMapper.getDealByIdForUpdate(5, 43)).thenReturn(new Deal());
        stubAdmittedStorage(scanned);
        AtomicReference<Attachment> insertedAttachment = new AtomicReference<>();
        doAnswer(invocation -> {
            Attachment inserted = invocation.getArgument(0);
            inserted.setId(77);
            insertedAttachment.set(inserted);
            return 1;
        }).when(attachmentMapper).insert(any(Attachment.class));
        when(attachmentMapper.getCreatedById(5, 77))
            .thenAnswer(invocation -> insertedAttachment.get());

        Attachment attachment = operations.upload(5, "deal", 43, scanned, uploader);

        assertEquals(5, attachment.getWorkspaceId());
        assertEquals("deal", attachment.getEntityType());
        assertEquals(43, attachment.getEntityId());
        assertEquals(uploader, attachment.getUploadedBy());
        assertEquals("clean", attachment.getScanState());
        assertEquals("ClamAV", attachment.getScanEngine());
        assertEquals("test-database", attachment.getScanDatabaseVersion());
        assertNotNull(attachment.getScannedAt());
        assertNotNull(attachment.getScanExpiresAt());
        assertEquals(1, attachment.getScanAttempts());
        verify(attachmentMapper).insert(attachment);
        var order = inOrder(workspaceService, dealMapper, managedObjectService, attachmentMapper);
        order.verify(workspaceService).requirePermission(5, 7, Permission.ATTACHMENT_CREATE);
        order.verify(dealMapper).exists(5, 43);
        order.verify(managedObjectService).storeInspectedAttachment(eq(5), eq(scanned), any(Runnable.class));
        order.verify(workspaceService).lockAndRequirePermissionsSnapshotForShare(
            5, Map.of(7, Set.of(Permission.ATTACHMENT_CREATE)));
        order.verify(dealMapper).getDealByIdForUpdate(5, 43);
        order.verify(attachmentMapper).insert(attachment);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void uploadDeniesRevokedAuthorityBeforeStorage(boolean inlineImage) {
        when(workspaceService.getCurrentUserId()).thenReturn(7);
        doThrow(new ForbiddenException("Upload permission revoked")).when(workspaceService)
            .requirePermission(5, 7, Permission.ATTACHMENT_CREATE);
        ScannedUpload scanned = mock(ScannedUpload.class);

        assertThrows(ForbiddenException.class, () -> {
            if (inlineImage) {
                operations.uploadInlineImage(5, "company", 41, scanned, new User());
            } else {
                operations.upload(5, "company", 41, scanned, new User());
            }
        });

        verify(managedObjectService, never()).storeInspectedAttachment(eq(5), eq(scanned), any(Runnable.class));
        verify(attachmentMapper, never()).insert(any(Attachment.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void uploadLocksNoteAfterStorageAndRejectsLostVisibility(boolean inlineImage) {
        when(workspaceService.getCurrentUserId()).thenReturn(7);
        stubLockedAuthority(Map.of(7, Set.of(Permission.ATTACHMENT_CREATE)));
        when(noteMapper.getVisibleNoteById(5, 43, 7)).thenReturn(new Note());
        when(noteMapper.exists(5, 43)).thenReturn(true);
        ScannedUpload scanned = mock(ScannedUpload.class);
        stubAdmittedStorage(scanned);

        assertThrows(ForbiddenException.class, () -> {
            if (inlineImage) {
                operations.uploadInlineImage(5, "note", 43, scanned, new User());
            } else {
                operations.upload(5, "note", 43, scanned, new User());
            }
        });

        var order = inOrder(workspaceService, noteMapper, managedObjectService);
        order.verify(workspaceService).requirePermission(5, 7, Permission.ATTACHMENT_CREATE);
        order.verify(noteMapper).getVisibleNoteById(5, 43, 7);
        order.verify(managedObjectService).storeInspectedAttachment(eq(5), eq(scanned), any(Runnable.class));
        order.verify(workspaceService).lockAndRequirePermissionsSnapshotForShare(
            5, Map.of(7, Set.of(Permission.ATTACHMENT_CREATE)));
        order.verify(noteMapper).getVisibleNoteByIdForUpdate(5, 43, 7);
        verify(attachmentMapper, never()).insert(any(Attachment.class));
    }

    @ParameterizedTest
    @CsvSource({"false,company", "true,company", "false,person", "true,person", "false,deal", "true,deal"})
    void uploadRejectsRecordRemovedDuringStorage(boolean inlineImage, String type) {
        when(workspaceService.getCurrentUserId()).thenReturn(7);
        stubLockedAuthority(Map.of(7, Set.of(Permission.ATTACHMENT_CREATE)));
        switch (type) {
            case "company" -> when(companyMapper.exists(5, 43)).thenReturn(true);
            case "person" -> when(personMapper.exists(5, 43)).thenReturn(true);
            case "deal" -> when(dealMapper.exists(5, 43)).thenReturn(true);
            default -> throw new IllegalArgumentException(type);
        }
        ScannedUpload scanned = mock(ScannedUpload.class);
        stubAdmittedStorage(scanned);

        assertThrows(ForbiddenException.class, () -> upload(inlineImage, type, scanned));

        verify(attachmentMapper, never()).insert(any(Attachment.class));
    }

    @ParameterizedTest
    @CsvSource({"false,company", "true,company", "false,person", "true,person"})
    void uploadRejectsRevokedShareEvenWhenRecordReadWasVisible(boolean inlineImage, String type) {
        when(workspaceService.getCurrentUserId()).thenReturn(7);
        stubLockedAuthority(Map.of(7, Set.of(Permission.ATTACHMENT_CREATE)));
        if ("company".equals(type)) {
            Company shared = new Company();
            shared.setWorkspaceId(6);
            when(companyMapper.exists(5, 43)).thenReturn(true);
            when(companyMapper.getVisibleCompanyByIdForUpdate(5, 43)).thenReturn(shared);
        } else {
            Person shared = new Person();
            shared.setWorkspaceId(6);
            when(personMapper.exists(5, 43)).thenReturn(true);
            when(personMapper.getVisiblePersonByIdForUpdate(5, 43)).thenReturn(shared);
        }
        ScannedUpload scanned = mock(ScannedUpload.class);
        stubAdmittedStorage(scanned);

        assertThrows(ForbiddenException.class, () -> upload(inlineImage, type, scanned));

        if ("company".equals(type)) {
            var order = inOrder(managedObjectService, companyMapper, shareMapper);
            order.verify(managedObjectService).storeInspectedAttachment(eq(5), eq(scanned), any(Runnable.class));
            order.verify(companyMapper).getVisibleCompanyByIdForUpdate(5, 43);
            order.verify(shareMapper).lockCompanyShareForWorkspace(43, 5);
        } else {
            var order = inOrder(managedObjectService, personMapper, shareMapper);
            order.verify(managedObjectService).storeInspectedAttachment(eq(5), eq(scanned), any(Runnable.class));
            order.verify(personMapper).getVisiblePersonByIdForUpdate(5, 43);
            order.verify(shareMapper).lockPersonShareForWorkspace(43, 5);
        }
        verify(attachmentMapper, never()).insert(any(Attachment.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void uploadRequiresTargetMembershipInAdmittedAuthority(boolean inlineImage) {
        when(workspaceService.getCurrentUserId()).thenReturn(7);
        when(workspaceMapper.isMember(5, 43)).thenReturn(true);
        Map<Integer, Set<Permission>> required = Map.of(
            7, Set.of(Permission.ATTACHMENT_CREATE), 43, Set.of());
        when(workspaceService.lockAndRequirePermissionsSnapshotForShare(5, required))
            .thenThrow(new ForbiddenException("Target membership ended"));
        ScannedUpload scanned = mock(ScannedUpload.class);
        stubAdmittedStorage(scanned);

        assertThrows(ForbiddenException.class, () -> upload(inlineImage, "user", scanned));

        verify(attachmentMapper, never()).insert(any(Attachment.class));
    }

    @Test
    void assistantUploadStoresTheExactInspectedArtifact() {
        User uploader = new User();
        uploader.setId(7);
        byte[] content = {1, 2, 3};
        ScannedUpload scanned = mock(ScannedUpload.class);
        when(scanned.report()).thenReturn(
            new MalwareScanReport(MalwareScanVerdict.CLEAN, null, null, "test-database", false));
        StoredBinary stored = new StoredBinary(
            "/api/attachments/content/token.jpg", "image.jpg", "image/jpeg", content.length);
        when(aiChatMapper.sessionExists(5, 43)).thenReturn(true);
        when(managedObjectService.storeInspectedAttachment(5, scanned)).thenReturn(stored);
        AtomicReference<Attachment> insertedAttachment = new AtomicReference<>();
        doAnswer(invocation -> {
            Attachment inserted = invocation.getArgument(0);
            inserted.setId(77);
            insertedAttachment.set(inserted);
            return 1;
        }).when(attachmentMapper).insert(any(Attachment.class));
        when(attachmentMapper.getCreatedById(5, 77))
            .thenAnswer(invocation -> insertedAttachment.get());

        Attachment attachment = operations.uploadAssistantSession(5, 43, scanned, uploader);

        assertEquals(content.length, attachment.getSize());
        assertEquals("clean", attachment.getScanState());
        assertEquals("ClamAV", attachment.getScanEngine());
        assertEquals("test-database", attachment.getScanDatabaseVersion());
        assertNotNull(attachment.getScannedAt());
        assertNotNull(attachment.getScanExpiresAt());
        assertEquals(1, attachment.getScanAttempts());
        verify(managedObjectService).storeInspectedAttachment(5, scanned);
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
                "upload", int.class, String.class, int.class, ScannedUpload.class, User.class)
            .getAnnotation(Transactional.class));
        assertNotNull(AttachmentWriteOperations.class
            .getMethod(
                "uploadInlineImage", int.class, String.class, int.class, ScannedUpload.class, User.class)
            .getAnnotation(Transactional.class));
        assertNotNull(AttachmentWriteOperations.class
            .getMethod(
                "uploadAssistantSession",
                int.class,
                int.class,
                ScannedUpload.class,
                User.class)
            .getAnnotation(Transactional.class));
    }

    private void stubLockedAuthority(Map<Integer, Set<Permission>> required) {
        when(workspaceService.lockAndRequirePermissionsSnapshotForShare(5, required))
            .thenReturn(new WorkspaceService.LockedPermissionSnapshot(required, required));
    }

    private void stubAdmittedStorage(ScannedUpload scanned) {
        when(managedObjectService.storeInspectedAttachment(eq(5), eq(scanned), any(Runnable.class)))
            .thenAnswer(invocation -> {
                invocation.getArgument(2, Runnable.class).run();
                return new StoredBinary("/api/attachments/content/token.png", "file.png", "image/png", 2);
            });
    }

    private void upload(boolean inlineImage, String type, ScannedUpload scanned) {
        if (inlineImage) {
            operations.uploadInlineImage(5, type, 43, scanned, new User());
        } else {
            operations.upload(5, type, 43, scanned, new User());
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
