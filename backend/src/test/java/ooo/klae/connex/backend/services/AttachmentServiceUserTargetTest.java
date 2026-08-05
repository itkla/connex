package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.dto.UserDisplayNameDto;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.storage.ManagedObjectService;

/** Pins active-member provenance before a user-target attachment can expose a label. */
@ExtendWith(MockitoExtension.class)
class AttachmentServiceUserTargetTest {
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
    void createRejectsNonmemberUserTargetBeforeTenantWrite() {
        Attachment attachment = userAttachment(41);
        when(attachmentReadService.getActiveWorkspaceMemberLabel(5, 41)).thenReturn(null);

        assertThrows(ResourceNotFoundException.class, () -> service.create(attachment));

        verify(attachmentWriteOperations, never()).createExternal(5, attachment);
    }

    @Test
    void createAllowsActiveUserTargetAndHydratesOnlyAfterWriteReturns() {
        Attachment attachment = userAttachment(41);
        attachment.setId(77);
        UserDisplayNameDto target = new UserDisplayNameDto(41, "Target User");
        when(attachmentReadService.getActiveWorkspaceMemberLabel(5, 41)).thenReturn(target);
        when(attachmentWriteOperations.createExternal(5, attachment)).thenReturn(attachment);
        when(attachmentReadService.hydrateKnown(5, attachment, null, target))
            .thenReturn(attachment);

        Attachment created = service.create(attachment);

        assertSame(attachment, created);
        InOrder operations = inOrder(attachmentReadService, attachmentWriteOperations);
        operations.verify(attachmentReadService).getActiveWorkspaceMemberLabel(5, 41);
        operations.verify(attachmentWriteOperations).createExternal(5, attachment);
        operations.verify(attachmentReadService).hydrateKnown(5, attachment, null, target);
    }

    private static Attachment userAttachment(int userId) {
        Attachment attachment = new Attachment();
        attachment.setEntityType("user");
        attachment.setEntityId(userId);
        attachment.setFileName("user.pdf");
        attachment.setUrl("https://example.com/user.pdf");
        return attachment;
    }
}
