package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
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

class AttachmentServiceScanTest {
    private AttachmentWriteOperations writeOperations;
    private AttachmentReadService readService;
    private UploadContentInspector inspector;
    private UploadMalwareScanner malwareScanner;
    private AttachmentService service;

    @BeforeEach
    void setUp() {
        writeOperations = mock(AttachmentWriteOperations.class);
        readService = mock(AttachmentReadService.class);
        inspector = mock(UploadContentInspector.class);
        malwareScanner = mock(UploadMalwareScanner.class);
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        service = new AttachmentService(
                mock(AttachmentMapper.class),
                readService,
                writeOperations,
                mock(TagMapper.class),
                mock(NoteMapper.class),
                mock(AuditService.class),
                workspaceService,
                mock(ReferenceService.class),
                mock(ManagedObjectService.class),
                inspector,
                malwareScanner);
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
}
