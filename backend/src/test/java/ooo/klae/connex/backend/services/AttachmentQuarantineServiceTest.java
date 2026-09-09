package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
import ooo.klae.connex.backend.mappers.AttachmentScanMapper;
import ooo.klae.connex.backend.services.WorkspaceService.LockedPermissionSnapshot;
import ooo.klae.connex.backend.storage.ManagedObjectService;

/** Pins post-lock authorization and non-overridable clean verdicts for quarantine operations. */
class AttachmentQuarantineServiceTest {
    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final AttachmentScanMapper scanMapper = mock(AttachmentScanMapper.class);
    private final AttachmentMapper attachmentMapper = mock(AttachmentMapper.class);
    private final ManagedObjectService objects = mock(ManagedObjectService.class);
    private final ReferenceService references = mock(ReferenceService.class);
    private final AuditService audit = mock(AuditService.class);
    private final LockedPermissionSnapshot authority = mock(LockedPermissionSnapshot.class);
    private final Attachment attachment = new Attachment();
    private AttachmentQuarantineService service;

    @BeforeEach
    void setUp() {
        service = new AttachmentQuarantineService(
            workspaceService, scanMapper, attachmentMapper, objects, references, audit);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(workspaceService.getCurrentUserId()).thenReturn(11);
        when(workspaceService.lockAndRequirePermissionsSnapshot(eq(7), any())).thenReturn(authority);
        attachment.setId(19);
        attachment.setWorkspaceId(7);
        attachment.setUrl("/api/attachments/content/example.txt");
        attachment.setScanState("quarantined");
        when(scanMapper.getById(7, 19)).thenReturn(attachment);
        when(scanMapper.lockById(7, 19)).thenReturn(attachment);
        when(attachmentMapper.lockIdsByUrl(7, attachment.getUrl())).thenReturn(List.of(19));
    }

    @Test
    void quarantineRevalidatesLockedAuthorityBeforeMutationAndAuditsWithoutFilename() {
        service.quarantine(19);

        InOrder order = inOrder(workspaceService, attachmentMapper, scanMapper, authority, audit);
        order.verify(workspaceService).lockAndRequirePermissionsSnapshot(eq(7), any());
        order.verify(attachmentMapper).lockIdsByUrl(7, attachment.getUrl());
        order.verify(scanMapper).lockById(7, 19);
        order.verify(authority).revalidate();
        order.verify(scanMapper).quarantine(7, 19);
        order.verify(audit).recordStrict("malware.quarantine", "attachment", 19,
            null, "Quarantined attachment", null);
        verifyNoInteractions(objects);
    }

    @Test
    void revokedAuthorityAfterTargetLockCannotMutateOrAudit() {
        doThrow(new ForbiddenException("Permission required")).when(authority).revalidate();

        assertThrows(ForbiddenException.class, () -> service.quarantine(19));

        verify(scanMapper).lockById(7, 19);
        verify(scanMapper, never()).quarantine(7, 19);
        verifyNoInteractions(objects, audit);
    }

    @Test
    void releaseAndRescanOnlyEnqueueTheyNeverDecideClean() {
        service.release(19);
        service.rescan(19);

        verify(scanMapper, org.mockito.Mockito.times(2)).enqueue(7, 19);
        verifyNoInteractions(objects);
        verify(audit).recordStrict("malware.release_requested", "attachment", 19,
            null, "Requested attachment quarantine release", null);
        verify(audit).recordStrict("malware.rescan", "attachment", 19,
            null, "Requested attachment security scan", null);
    }

    @Test
    void tenantMismatchFailsBeforeObjectLocksOrMutation() {
        attachment.setWorkspaceId(8);

        assertThrows(ResourceNotFoundException.class, () -> service.release(19));

        verifyNoInteractions(attachmentMapper, objects, audit);
        verify(scanMapper, never()).enqueue(7, 19);
    }

    @Test
    void changedReferenceBetweenDiscoveryAndLockFailsClosed() {
        Attachment moved = new Attachment();
        moved.setId(19);
        moved.setWorkspaceId(7);
        moved.setUrl("/api/attachments/content/different.txt");
        when(scanMapper.lockById(7, 19)).thenReturn(moved);

        assertThrows(ResourceNotFoundException.class, () -> service.quarantine(19));

        verify(scanMapper, never()).quarantine(7, 19);
        verifyNoInteractions(objects, audit);
    }

    @Test
    void deletionQueuesBytesBeforeMetadataRemovalAndAudit() {
        service.delete(19);

        InOrder order = inOrder(objects, attachmentMapper, references, audit);
        order.verify(objects).deleteAttachmentAfterCommit(7, attachment.getUrl());
        order.verify(attachmentMapper).delete(7, 19);
        order.verify(references).deleteReferencesTo(7, ReferenceService.TYPE_FILE, 19);
        order.verify(audit).recordStrict("malware.quarantine_deleted", "attachment", 19,
            null, "Deleted quarantined attachment", null);
    }

    @Test
    void deletingSharedReferencePreservesBytesForRemainingReference() {
        when(attachmentMapper.lockIdsByUrl(7, attachment.getUrl())).thenReturn(List.of(18, 19));

        service.delete(19);

        verifyNoInteractions(objects);
        verify(attachmentMapper).delete(7, 19);
    }

    @Test
    void cleanAttachmentMustBeQuarantinedBeforeDestructiveOperation() {
        attachment.setScanState("clean");

        assertThrows(BadRequestException.class, () -> service.delete(19));

        verifyNoInteractions(objects, references, audit);
        verify(attachmentMapper, never()).delete(7, 19);
    }
}
