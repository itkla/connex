package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.storage.ManagedObjectService;
import ooo.klae.connex.backend.storage.UploadSource;

/** Verifies note attachments require visibility to the current workspace member. */
@ExtendWith(MockitoExtension.class)
class AttachmentServiceNoteTargetTest {
    @Mock private AttachmentMapper attachmentMapper;
    @Mock private AttachmentReadService attachmentReadService;
    @Mock private AttachmentWriteOperations attachmentWriteOperations;
    @Mock private TagMapper tagMapper;
    @Mock private NoteMapper noteMapper;
    @Mock private AuditService auditService;
    @Mock private WorkspaceService workspaceService;
    @Mock private ReferenceService referenceService;
    @Mock private ManagedObjectService managedObjectService;

    private AttachmentService service;

    @BeforeEach
    void setUp() {
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(5);
        when(workspaceService.getCurrentUserId()).thenReturn(7);
        service = new AttachmentService(
            attachmentMapper,
            attachmentReadService,
            attachmentWriteOperations,
            tagMapper,
            noteMapper,
            auditService,
            workspaceService,
            referenceService,
            managedObjectService);
    }

    @Test
    void createRejectsInvisibleNoteBeforeTenantWrite() {
        Attachment attachment = noteAttachment(41);
        when(noteMapper.getVisibleNoteById(5, 41, 7)).thenReturn(null);

        assertThrows(ResourceNotFoundException.class, () -> service.create(attachment));

        verify(attachmentWriteOperations, never()).createExternal(5, attachment);
    }

    @Test
    void uploadRequiresVisibleNoteBeforeTenantWrite() {
        UploadSource source = UploadSource.from("note.png", "image/png", new byte[] {1});
        User uploader = new User();
        when(noteMapper.getVisibleNoteById(5, 41, 7)).thenReturn(null);

        assertThrows(ResourceNotFoundException.class,
            () -> service.upload("note", 41, source, uploader));

        verify(attachmentWriteOperations, never()).upload(5, "note", 41, source, uploader);
    }

    @Test
    void getByEntityRequiresVisibleNoteBeforeReadingAttachments() {
        when(noteMapper.getVisibleNoteById(5, 41, 7)).thenReturn(null);

        assertThrows(ResourceNotFoundException.class, () -> service.getByEntity("note", 41));

        verify(attachmentReadService, never()).getByEntity(5, "note", 41);
    }

    @Test
    void getByIdRequiresVisibleNoteForNoteAttachment() {
        Attachment attachment = noteAttachment(41);
        attachment.setId(88);
        when(attachmentReadService.getById(5, 88)).thenReturn(attachment);
        when(noteMapper.getVisibleNoteById(5, 41, 7)).thenReturn(null);

        assertThrows(ResourceNotFoundException.class, () -> service.getById(88));
    }

    @Test
    void getManagedContentRequiresVisibleNoteForNoteAttachment() {
        Attachment attachment = noteAttachment(41);
        attachment.setUrl("/api/attachments/content/token.png");
        when(attachmentMapper.getMetadataByUrl(5, "/api/attachments/content/token.png"))
            .thenReturn(attachment);
        when(noteMapper.getVisibleNoteById(5, 41, 7)).thenReturn(null);

        assertThrows(ResourceNotFoundException.class,
            () -> service.getManagedContent("token.png"));

        verify(managedObjectService, never()).openAttachment(5, attachment);
    }

    @Test
    void createAllowsVisibleNoteBeforeTenantWrite() {
        Attachment attachment = noteAttachment(41);
        Note note = new Note();
        when(noteMapper.getVisibleNoteById(5, 41, 7)).thenReturn(note);
        when(attachmentWriteOperations.createExternal(5, attachment)).thenReturn(attachment);
        when(attachmentReadService.hydrateKnown(5, attachment, null, null)).thenReturn(attachment);

        Attachment created = service.create(attachment);

        assertSame(attachment, created);
        verify(attachmentWriteOperations).createExternal(5, attachment);
    }

    private static Attachment noteAttachment(int noteId) {
        Attachment attachment = new Attachment();
        attachment.setEntityType("note");
        attachment.setEntityId(noteId);
        attachment.setFileName("note.pdf");
        attachment.setUrl("https://example.com/note.pdf");
        return attachment;
    }
}
