package ooo.klae.connex.backend.services;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
import ooo.klae.connex.backend.mappers.AttachmentScanMapper;
import ooo.klae.connex.backend.services.WorkspaceService.LockedPermissionSnapshot;
import ooo.klae.connex.backend.storage.ManagedObjectService;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/** Applies workspace-wide quarantine administration without exposing attachment content. */
@Service
@RequiredArgsConstructor
public class AttachmentQuarantineService {
    private static final Set<String> DENIED_SCAN_STATES = Set.of("quarantined", "infected", "unscannable");

    private final WorkspaceService workspaceService;
    private final AttachmentScanMapper scanMapper;
    private final AttachmentMapper attachmentMapper;
    private final ManagedObjectService managedObjectService;
    private final ReferenceService referenceService;
    private final AuditService auditService;

    /** Retains stored bytes in a denied state until an administrator requests a fresh scan. */
    @Transactional
    @RequirePermission(Permission.ATTACHMENT_QUARANTINE_MANAGE)
    public void quarantine(int id) {
        LockedAttachment locked = lock(id);
        scanMapper.quarantine(locked.workspaceId(), id);
        audit("malware.quarantine", locked, "Quarantined attachment");
    }

    /** Invalidates the prior verdict and queues another scan using current definitions. */
    @Transactional
    @RequirePermission(Permission.ATTACHMENT_QUARANTINE_MANAGE)
    public void rescan(int id) {
        LockedAttachment locked = lock(id);
        scanMapper.enqueue(locked.workspaceId(), id);
        audit("malware.rescan", locked, "Requested attachment security scan");
    }

    /** Requests a fresh scan before release; no administrator can assert a clean verdict. */
    @Transactional
    @RequirePermission(Permission.ATTACHMENT_QUARANTINE_MANAGE)
    public void release(int id) {
        LockedAttachment locked = lock(id);
        scanMapper.enqueue(locked.workspaceId(), id);
        audit("malware.release_requested", locked, "Requested attachment quarantine release");
    }

    /** Deletes a quarantined reference and atomically queues bytes once no other reference remains. */
    @Transactional
    @RequirePermission(Permission.ATTACHMENT_QUARANTINE_MANAGE)
    public void delete(int id) {
        LockedAttachment locked = lock(id);
        if ("clean".equals(locked.attachment().getScanState())) {
            throw new BadRequestException("Attachment must be quarantined before deletion");
        }
        if (locked.referenceCount() == 1) {
            managedObjectService.deleteAttachmentAfterCommit(
                locked.workspaceId(), locked.attachment().getUrl());
        }
        attachmentMapper.delete(locked.workspaceId(), id);
        referenceService.deleteReferencesTo(locked.workspaceId(), ReferenceService.TYPE_FILE, id);
        audit("malware.quarantine_deleted", locked, "Deleted quarantined attachment");
    }

    /**
     * Reports whether removing this reference is quarantine administration rather than ordinary
     * deletion, and therefore needs {@link Permission#ATTACHMENT_QUARANTINE_MANAGE} and a strict audit.
     *
     * <p>Only managed objects carrying a denied verdict or lifecycle state qualify: {@code quarantined},
     * {@code infected} and {@code unscannable}. Pre-verdict states ({@code pending}, {@code scanning},
     * {@code error}) stay ordinary because {@code pending} is the default for every legacy and
     * server-generated reference and is permanent while scanning is disabled. Unmanaged references
     * are outside the quarantine lifecycle and are never scanned.
     *
     * @param attachment attachment row whose persisted scan state and URL decide the route
     * @param managedObjectService classifier for the managed attachment URL namespace
     * @return {@code true} when only quarantine authority may delete the reference
     */
    public static boolean requiresQuarantineAuthority(
            Attachment attachment, ManagedObjectService managedObjectService) {
        String scanState = attachment.getScanState();
        return scanState != null && DENIED_SCAN_STATES.contains(scanState)
            && managedObjectService.isManagedAttachmentUrl(attachment.getUrl());
    }

    private LockedAttachment lock(int id) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = workspaceService.getCurrentUserId();
        LockedPermissionSnapshot authority = workspaceService.lockAndRequirePermissionsSnapshot(
            workspaceId, Map.of(actorId, Set.of(Permission.ATTACHMENT_QUARANTINE_MANAGE)));
        Attachment discovered = scanMapper.getById(workspaceId, id);
        if (discovered == null || discovered.getWorkspaceId() != workspaceId) {
            throw new ResourceNotFoundException("Attachment not found");
        }
        List<Integer> references = attachmentMapper.lockIdsByUrl(workspaceId, discovered.getUrl());
        Attachment attachment = scanMapper.lockById(workspaceId, id);
        authority.revalidate();
        if (attachment == null || attachment.getWorkspaceId() != workspaceId
                || attachment.getId() != id || !references.contains(id)
                || !Objects.equals(attachment.getUrl(), discovered.getUrl())) {
            throw new ResourceNotFoundException("Attachment not found");
        }
        if (!managedObjectService.isManagedAttachmentUrl(attachment.getUrl())) {
            throw new BadRequestException(
                "Quarantine lifecycle is not applicable to unmanaged attachment references");
        }
        return new LockedAttachment(workspaceId, attachment, references.size());
    }

    private void audit(String action, LockedAttachment locked, String summary) {
        auditService.recordStrict(action, "attachment", locked.attachment().getId(), null, summary, null);
    }

    private record LockedAttachment(int workspaceId, Attachment attachment, int referenceCount) {
    }
}
