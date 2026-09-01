package ooo.klae.connex.backend.ai.assistant;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.dto.AiChatAttachmentDto;
import ooo.klae.connex.backend.dto.AiChatStepFrameDto;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
import ooo.klae.connex.backend.notifications.AiChatRealtimeDispatcher;
import ooo.klae.connex.backend.services.AttachmentWriteOperations;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.storage.ManagedObjectService;
import ooo.klae.connex.backend.storage.ScannedUpload;
import ooo.klae.connex.backend.storage.UploadContentInspector.InspectedUpload;
import ooo.klae.connex.backend.storage.UploadMalwareScanner;
import ooo.klae.connex.backend.storage.UploadSource;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/** Tenant-scoped lifecycle for private files attached to an assistant session. */
@Service
@RequiredArgsConstructor
public class AiChatAttachmentService {
    private static final String ACTIVE = "active";
    private static final String SHARED = "shared";
    private static final Set<String> AUDIT_FIELDS =
            Set.of("fileName", "entityType", "entityId", "url", "contentType", "size");

    private final AiChatMapper chatMapper;
    private final AttachmentMapper attachmentMapper;
    private final AttachmentWriteOperations attachmentWriteOperations;
    private final AiChatAttachmentPolicy attachmentPolicy;
    private final UploadMalwareScanner uploadMalwareScanner;
    private final AiChatAttachmentTransactions attachmentTransactions;
    private final ManagedObjectService managedObjectService;
    private final WorkspaceService workspaceService;
    private final AuthService authService;
    private final AuditService auditService;
    private final AiAssistantSessionReadAudit sessionReadAudit;
    private final AiChatRealtimeDispatcher realtimeDispatcher;

    /** Returns every attachment visible through one currently authorized active session. */
    @Transactional
    @RequirePermission(Permission.AI_USE)
    public List<AiChatAttachmentDto> list(int sessionId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int userId = workspaceService.getCurrentUserId();
        requireAccessibleSession(workspaceId, userId, sessionId);
        return attachmentMapper.getAssistantSessionAttachments(workspaceId, sessionId).stream()
                .map(AiChatAttachmentDto::from)
                .toList();
    }

    /** Stores one validated managed attachment under an authorized active session. */
    @RequirePermission(Permission.AI_USE)
    public AiChatAttachmentDto upload(int sessionId, UploadSource source) {
        UploadSnapshot snapshot = attachmentTransactions.readOnly(
                () -> precheckUpload(sessionId));
        InspectedUpload inspected = attachmentPolicy.prepare(source);
        ScannedUpload scanned = uploadMalwareScanner.scan(inspected);
        return attachmentTransactions.readCommitted(
                () -> persistUpload(snapshot, sessionId, scanned));
    }

    private UploadSnapshot precheckUpload(int sessionId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int userId = workspaceService.getCurrentUserId();
        requireAccessibleSession(workspaceId, userId, sessionId);
        workspaceService.requirePermission(
                workspaceId, userId, Permission.ATTACHMENT_CREATE);
        int attachmentCount = attachmentMapper.countAssistantSessionAttachments(
                workspaceId, sessionId);
        if (attachmentCount >= AiChatAttachmentPolicy.MAX_ATTACHMENTS) {
            throw new ConflictException("Assistant sessions accept at most ten attachments");
        }
        return new UploadSnapshot(workspaceId, userId);
    }

    private AiChatAttachmentDto persistUpload(
            UploadSnapshot snapshot,
            int sessionId,
            ScannedUpload scanned) {
        int workspaceId = snapshot.workspaceId();
        int userId = snapshot.userId();
        requireLockedUploadPermissions(workspaceId, userId);
        lockAndRequireAccessibleSession(workspaceId, userId, sessionId);
        requireNoActiveTurn(workspaceId, sessionId);
        int attachmentCount = attachmentMapper.countAssistantSessionAttachments(
                workspaceId, sessionId);
        if (attachmentCount >= AiChatAttachmentPolicy.MAX_ATTACHMENTS) {
            throw new ConflictException("Assistant sessions accept at most ten attachments");
        }
        Attachment attachment = attachmentWriteOperations.uploadAssistantSession(
                workspaceId,
                sessionId,
                scanned,
                authService.getCurrentUser());
        realtimeDispatcher.sessionAfterCommit(
                workspaceId,
                sessionId,
                new AiChatStepFrameDto(
                        workspaceId,
                        sessionId,
                        0,
                        0,
                        "session",
                        null,
                        "attachments_changed",
                        null));
        return AiChatAttachmentDto.from(attachment);
    }

    /** Deletes one managed attachment after exact session and tenant authorization. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.AI_USE)
    public void delete(int sessionId, int attachmentId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int userId = workspaceService.getCurrentUserId();
        lockAndRequireAccessibleSession(workspaceId, userId, sessionId);
        workspaceService.requirePermission(
                workspaceId, userId, Permission.ATTACHMENT_DELETE);
        requireNoActiveTurn(workspaceId, sessionId);
        Attachment attachment = attachmentMapper.getAssistantSessionAttachment(
                workspaceId, sessionId, attachmentId);
        if (attachment == null) {
            throw inaccessible();
        }
        List<Integer> referenceIds = attachmentMapper.lockIdsByUrl(
                workspaceId, attachment.getUrl());
        if (!referenceIds.contains(attachmentId)) {
            throw inaccessible();
        }
        if (referenceIds.size() == 1) {
            managedObjectService.deleteAttachmentAfterCommit(
                    workspaceId, attachment.getUrl());
        }
        attachmentMapper.delete(workspaceId, attachmentId);
        auditService.record(
                "attachment.delete",
                "attachment",
                attachmentId,
                attachment.getFileName(),
                "Deleted assistant attachment " + attachment.getFileName(),
                auditService.diff(attachment, null, AUDIT_FIELDS));
    }

    private void lockAndRequireAccessibleSession(
            int workspaceId,
            int userId,
            int sessionId) {
        workspaceService.lockAndRequireMember(workspaceId, userId);
        workspaceService.requirePermission(workspaceId, userId, Permission.AI_USE);
        AiChatSession session = chatMapper.getSessionByIdForUpdate(
                workspaceId, userId, sessionId);
        if (session == null
                || (!Objects.equals(session.getCreatedByUserId(), userId)
                    && (!SHARED.equals(session.getVisibility())
                        || !chatMapper.isParticipant(workspaceId, sessionId, userId)))) {
            throw inaccessible();
        }
        requireActiveSessionAndAuthor(workspaceId, userId, session);
    }

    private AiChatSession requireAccessibleSession(
            int workspaceId,
            int userId,
            int sessionId) {
        AiChatSession session = chatMapper.getAccessibleSessionById(
                workspaceId, userId, sessionId);
        if (session == null) {
            throw inaccessible();
        }
        requireActiveSessionAndAuthor(workspaceId, userId, session);
        sessionReadAudit.recordAccessible(workspaceId, userId, session);
        return session;
    }

    private void requireActiveSessionAndAuthor(
            int workspaceId,
            int userId,
            AiChatSession session) {
        List<Integer> activeMemberIds = workspaceService.getMembers(workspaceId).stream()
                .map(user -> user.getId())
                .toList();
        if (!activeMemberIds.contains(userId)
                || session.getCreatedByUserId() == null
                || !activeMemberIds.contains(session.getCreatedByUserId())) {
            throw inaccessible();
        }
        if (!ACTIVE.equals(session.getStatus())) {
            throw new ConflictException("Archived sessions cannot change attachments");
        }
    }

    private void requireNoActiveTurn(int workspaceId, int sessionId) {
        if (chatMapper.countActiveTurns(workspaceId, sessionId) != 0) {
            throw new ConflictException(
                    "Assistant attachments cannot change while a turn is active");
        }
    }

    /**
     * Re-asserts upload authority from exclusively locked authorization rows.
     *
     * <p>The pre-scan snapshot is only a snapshot, and the malware scan runs outside any
     * transaction, so a concurrent role change or permission revocation can land in between.
     * Reading from locked rows serializes against that; it is acquired before the session record
     * lock to preserve the repository's membership-before-record lock order.
     */
    private void requireLockedUploadPermissions(int workspaceId, int userId) {
        Set<Permission> permissions = workspaceService.lockedPermissionsFor(workspaceId, userId);
        if (!permissions.contains(Permission.AI_USE)
                || !permissions.contains(Permission.ATTACHMENT_CREATE)) {
            throw new ForbiddenException(
                    "Requires the AI_USE and ATTACHMENT_CREATE permissions in this workspace");
        }
    }

    private static ResourceNotFoundException inaccessible() {
        return new ResourceNotFoundException("Assistant session attachment is not accessible");
    }

    private record UploadSnapshot(int workspaceId, int userId) {
    }
}
