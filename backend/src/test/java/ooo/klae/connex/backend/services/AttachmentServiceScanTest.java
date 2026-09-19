package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;

import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
import ooo.klae.connex.backend.mappers.AttachmentScanMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.storage.ManagedObjectService;
import ooo.klae.connex.backend.storage.ScannedUpload;
import ooo.klae.connex.backend.storage.UploadContentInspector;
import ooo.klae.connex.backend.storage.UploadContentInspector.InspectedUpload;
import ooo.klae.connex.backend.storage.UploadMalwareScanner;
import ooo.klae.connex.backend.storage.UploadPolicy.UploadPurpose;
import ooo.klae.connex.backend.storage.UploadSource;
import ooo.klae.connex.backend.storage.malware.EicarTestFixture;

/** Pins malware-scan admission for uploads and quarantine authority for deletion. */
class AttachmentServiceScanTest {
    private static final String MANAGED_URL =
            "/api/attachments/content/0f8fad5b-d9cb-469f-a165-70867728950e.txt";

    private AttachmentMapper attachmentMapper;
    private AttachmentWriteOperations writeOperations;
    private AttachmentReadService readService;
    private NoteMapper noteMapper;
    private AuditService auditService;
    private ReferenceService referenceService;
    private ManagedObjectService managedObjectService;
    private UploadContentInspector inspector;
    private UploadMalwareScanner malwareScanner;
    private AttachmentScanMapper scanMapper;
    private AttachmentQuarantineService quarantineService;
    private AttachmentService service;

    @BeforeEach
    void setUp() {
        attachmentMapper = mock(AttachmentMapper.class);
        writeOperations = mock(AttachmentWriteOperations.class);
        readService = mock(AttachmentReadService.class);
        noteMapper = mock(NoteMapper.class);
        auditService = mock(AuditService.class);
        referenceService = mock(ReferenceService.class);
        managedObjectService = mock(ManagedObjectService.class);
        inspector = mock(UploadContentInspector.class);
        malwareScanner = mock(UploadMalwareScanner.class);
        scanMapper = mock(AttachmentScanMapper.class);
        quarantineService = mock(AttachmentQuarantineService.class);
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(workspaceService.getCurrentUserId()).thenReturn(11);
        when(managedObjectService.isManagedAttachmentUrl(MANAGED_URL)).thenReturn(true);
        service = new AttachmentService(
                attachmentMapper,
                readService,
                writeOperations,
                mock(TagMapper.class),
                noteMapper,
                auditService,
                workspaceService,
                referenceService,
                managedObjectService,
                inspector,
                malwareScanner,
                scanMapper,
                quarantineService);
    }

    @Test
    void ordinaryUploadCannotReachPersistenceWhenScannerIsUnavailable() {
        UploadSource source = UploadSource.from(
                "eicar.txt", "text/plain", EicarTestFixture.bytes());
        InspectedUpload inspected = mock(InspectedUpload.class);
        User uploader = new User();
        when(inspector.inspect(UploadPurpose.ATTACHMENT, source)).thenReturn(inspected);
        when(malwareScanner.scan(inspected))
                .thenThrow(new ServiceUnavailableException("scanner unavailable"));

        assertThrows(ServiceUnavailableException.class,
                () -> service.upload("company", 13, source, uploader));

        verify(malwareScanner).scan(inspected);
        verify(writeOperations, never()).upload(
                eq(7), eq("company"), eq(13), any(ScannedUpload.class), eq(uploader));
    }

    @Test
    void cleanOrdinaryUploadPassesOnlyTheScannedProofIntoTheTransaction() {
        UploadSource source = UploadSource.from("clean.txt", "text/plain", new byte[] {1});
        InspectedUpload inspected = mock(InspectedUpload.class);
        ScannedUpload scanned = mock(ScannedUpload.class);
        User uploader = new User();
        Attachment persisted = new Attachment();
        when(inspector.inspect(UploadPurpose.ATTACHMENT, source)).thenReturn(inspected);
        when(malwareScanner.scan(inspected)).thenReturn(scanned);
        when(writeOperations.upload(7, "company", 13, scanned, uploader))
                .thenReturn(persisted);
        when(readService.hydrateKnown(7, persisted, uploader, null)).thenReturn(persisted);

        Attachment result = service.upload("company", 13, source, uploader);

        assertEquals(persisted, result);
        verify(malwareScanner).scan(inspected);
        verify(writeOperations).upload(7, "company", 13, scanned, uploader);
    }

    @Test
    void inlineUploadAlsoRequiresTheScannedProof() {
        UploadSource source = UploadSource.from("image.png", "image/png", new byte[] {1});
        InspectedUpload inspected = mock(InspectedUpload.class);
        ScannedUpload scanned = mock(ScannedUpload.class);
        User uploader = new User();
        Attachment persisted = new Attachment();
        when(inspector.inspect(UploadPurpose.INLINE_IMAGE, source)).thenReturn(inspected);
        when(malwareScanner.scan(inspected)).thenReturn(scanned);
        when(writeOperations.uploadInlineImage(7, "company", 13, scanned, uploader))
                .thenReturn(persisted);
        when(readService.hydrateKnown(7, persisted, uploader, null)).thenReturn(persisted);

        Attachment result = service.uploadInlineImage("company", 13, source, uploader);

        assertEquals(persisted, result);
        verify(malwareScanner).scan(inspected);
        verify(writeOperations).uploadInlineImage(7, "company", 13, scanned, uploader);
    }

    @ParameterizedTest
    @ValueSource(strings = {"quarantined", "infected", "unscannable", "suspicious"})
    void deniedManagedAttachmentDelegatesToQuarantineAuthorityBeforeAnyAttachmentLock(String state) {
        when(attachmentMapper.getMetadataById(7, 19)).thenReturn(attachment(MANAGED_URL, state));

        service.delete(19);

        verify(quarantineService).delete(19);
        verify(attachmentMapper, never()).lockIdsByUrl(anyInt(), any());
        verify(scanMapper, never()).lockById(anyInt(), anyInt());
        verify(attachmentMapper, never()).delete(anyInt(), anyInt());
        verify(managedObjectService, never()).deleteAttachmentAfterCommit(anyInt(), any());
        verify(referenceService, never()).deleteReferencesTo(anyInt(), any(), anyInt());
        verify(auditService, never()).record(any(), any(), any(), any(), any(), any());
    }

    @ParameterizedTest
    @CsvSource({
        "pending, true",
        "scanning, true",
        "error, true",
        "clean, true",
        "pending, false",
        "unscannable, false",
        "suspicious, false"
    })
    void preVerdictManagedAndUnmanagedReferencesKeepOrdinaryDeletion(String state, boolean managed) {
        String url = managed ? MANAGED_URL : "https://external.example/file.txt";
        Attachment row = attachment(url, state);
        when(attachmentMapper.getMetadataById(7, 19)).thenReturn(row);
        when(attachmentMapper.lockIdsByUrl(7, url)).thenReturn(List.of(19));
        when(scanMapper.lockById(7, 19)).thenReturn(attachment(url, state));

        service.delete(19);

        verify(quarantineService, never()).delete(anyInt());
        InOrder order = inOrder(attachmentMapper, scanMapper, managedObjectService, auditService);
        order.verify(attachmentMapper).lockIdsByUrl(7, url);
        order.verify(scanMapper).lockById(7, 19);
        order.verify(managedObjectService).deleteAttachmentAfterCommit(7, url);
        order.verify(attachmentMapper).delete(7, 19);
        order.verify(auditService).record(eq("attachment.delete"), eq("attachment"), eq(19),
                eq("file.txt"), eq("Deleted attachment file.txt"), any());
        verify(auditService, never()).recordStrict(any(), any(), any(), any(), any(), any());
    }

    @Test
    void attachmentDiscoveredCleanButLockedInDeniedStateConflictsWithoutMutation() {
        when(attachmentMapper.getMetadataById(7, 19)).thenReturn(attachment(MANAGED_URL, "clean"));
        when(attachmentMapper.lockIdsByUrl(7, MANAGED_URL)).thenReturn(List.of(19));
        when(scanMapper.lockById(7, 19)).thenReturn(attachment(MANAGED_URL, "quarantined"));

        ConflictException error = assertThrows(ConflictException.class, () -> service.delete(19));

        assertEquals("Attachment security state changed; retry", error.getMessage());
        verify(quarantineService, never()).delete(anyInt());
        verify(attachmentMapper, never()).delete(anyInt(), anyInt());
        verify(managedObjectService, never()).deleteAttachmentAfterCommit(anyInt(), any());
        verify(referenceService, never()).deleteReferencesTo(anyInt(), any(), anyInt());
        verify(auditService, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void lockedRowThatChangedReferenceIsNotFoundWithoutMutation() {
        when(attachmentMapper.getMetadataById(7, 19)).thenReturn(attachment(MANAGED_URL, "clean"));
        when(attachmentMapper.lockIdsByUrl(7, MANAGED_URL)).thenReturn(List.of(19));
        when(scanMapper.lockById(7, 19)).thenReturn(
                attachment("/api/attachments/content/different.txt", "clean"));

        assertThrows(ResourceNotFoundException.class, () -> service.delete(19));

        verify(attachmentMapper, never()).delete(anyInt(), anyInt());
        verify(managedObjectService, never()).deleteAttachmentAfterCommit(anyInt(), any());
    }

    @Test
    void assistantAndInvisibleNoteAttachmentsAreRefusedBeforeQuarantineDelegation() {
        Attachment assistant = attachment(MANAGED_URL, "quarantined");
        assistant.setEntityType("ai_chat_session");
        when(attachmentMapper.getMetadataById(7, 19)).thenReturn(assistant);
        assertThrows(ResourceNotFoundException.class, () -> service.delete(19));

        Attachment note = attachment(MANAGED_URL, "quarantined");
        note.setEntityType("note");
        note.setEntityId(41);
        when(attachmentMapper.getMetadataById(7, 19)).thenReturn(note);
        when(noteMapper.getVisibleNoteById(7, 41, 11)).thenReturn(null);
        assertThrows(ResourceNotFoundException.class, () -> service.delete(19));

        verify(quarantineService, never()).delete(anyInt());
        verify(attachmentMapper, never()).delete(anyInt(), anyInt());
    }

    private static Attachment attachment(String url, String state) {
        Attachment attachment = new Attachment();
        attachment.setId(19);
        attachment.setWorkspaceId(7);
        attachment.setEntityType("company");
        attachment.setEntityId(13);
        attachment.setFileName("file.txt");
        attachment.setUrl(url);
        attachment.setScanState(state);
        return attachment;
    }
}
