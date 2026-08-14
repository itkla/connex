package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.AttachmentFacets;
import ooo.klae.connex.backend.dto.UserDisplayNameDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.storage.ManagedObjectService;
import ooo.klae.connex.backend.storage.ManagedObjectService.ManagedContent;
import ooo.klae.connex.backend.storage.UploadSource;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

import java.util.List;
import java.util.Set;

import lombok.RequiredArgsConstructor;

/**
 * Business logic for {@code Attachment} operations.
 */

@Service
@RequiredArgsConstructor
public class AttachmentService {
    private static final String MANAGED_URL_PREFIX = "/api/attachments/content/";
    private static final String ASSISTANT_SESSION = "ai_chat_session";

    private final AttachmentMapper attachmentMapper;
    private final AttachmentReadService attachmentReadService;
    private final AttachmentWriteOperations attachmentWriteOperations;
    private final TagMapper tagMapper;
    private final NoteMapper noteMapper;
    private final AuditService auditService;
    private final WorkspaceService workspaceService;
    private final ReferenceService referenceService;
    private final ManagedObjectService managedObjectService;

    private static final Set<String> AUDIT_FIELDS =
        Set.of("fileName", "entityType", "entityId", "url", "contentType", "size");

    private String normalizeType(String entityType) {
        if (!StringUtils.hasText(entityType)) {
            throw new ResourceNotFoundException("entityType is required");
        }
        return entityType.trim().toLowerCase();
    }

    public List<Attachment> getByEntity(String entityType, int entityId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        String normalizedType = normalizeType(entityType);
        requireGenericAttachmentType(normalizedType);
        requireVisibleNoteTarget(workspaceId, normalizedType, entityId);
        return attachmentReadService.getByEntity(
            workspaceId, normalizedType, entityId);
    }

    public List<Attachment> getAll() {
        return attachmentReadService.getAll(workspaceService.getCurrentWorkspaceId());
    }

    public List<Attachment> getPage(String query, String sort, List<String> types, List<String> kinds,
            List<Integer> tagIds, Boolean orphaned, int limit, int offset) {
        return attachmentMapper.getPage(workspaceService.getCurrentWorkspaceId(), query, sort, types, kinds, tagIds, orphaned, limit, offset);
    }

    public long countPage(String query, List<String> types, List<String> kinds, List<Integer> tagIds,
            Boolean orphaned) {
        return attachmentMapper.countPage(workspaceService.getCurrentWorkspaceId(), query, types, kinds, tagIds, orphaned);
    }

    public AttachmentFacets facets() {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        return new AttachmentFacets(
            attachmentMapper.countsBySource(workspaceId),
            attachmentMapper.countsByKind(workspaceId),
            attachmentMapper.countsByTag(workspaceId),
            attachmentMapper.countOrphaned(workspaceId),
            attachmentMapper.totalCount(workspaceId),
            attachmentMapper.totalSize(workspaceId)
        );
    }

    public List<Tag> getTags(int attachmentId) {
        getById(attachmentId);
        return tagMapper.getTagsByAttachmentId(workspaceService.getCurrentWorkspaceId(), attachmentId);
    }

    /**
     * Adds a tag to an attachment.
     * @param attachmentId
     * @param tagId
     */
    @RequirePermission(Permission.ATTACHMENT_CREATE)
    public void addTag(int attachmentId, int tagId) {
        Attachment attachment = getById(attachmentId);
        Tag tag = tagMapper.getTagById(workspaceService.getCurrentWorkspaceId(), tagId);
        if (tag == null) {
            auditService.recordFailure("attachment.addTag", "attachment", attachmentId, attachment.getFileName(),
                    "Tag not found", "Tag not found with id: " + tagId);
            throw new ResourceNotFoundException("Tag not found with id: " + tagId);
        }
        attachmentMapper.addTag(workspaceService.getCurrentWorkspaceId(), attachmentId, tagId);
        auditService.record("attachment.addTag", "attachment", attachmentId, attachment.getFileName(),
            "Tagged " + attachment.getFileName() + " with " + tag.getName(),
            auditService.singleChange("tag", null, tag.getName()));
    }

    /**
     * Adds a tag to an attachment.
     * @param attachmentId
     * @param tagId
     */
    @RequirePermission(Permission.ATTACHMENT_CREATE)
    public void removeTag(int attachmentId, int tagId) {
        Attachment attachment = getById(attachmentId);
        Tag tag = tagMapper.getTagById(workspaceService.getCurrentWorkspaceId(), tagId);
        if (tag == null) {
            auditService.recordFailure("attachment.removeTag", "attachment", attachmentId, attachment.getFileName(),
                    "Tag not found", "Tag not found with id: " + tagId);
            throw new ResourceNotFoundException("Tag not found with id: " + tagId);
        }
        attachmentMapper.removeTag(workspaceService.getCurrentWorkspaceId(), attachmentId, tagId);
        auditService.record("attachment.removeTag", "attachment", attachmentId, attachment.getFileName(),
            "Removed tag " + tag.getName() + " from " + attachment.getFileName(),
            auditService.singleChange("tag", tag.getName(), null));
    }

    /**
     * Replaces the tags associated with an attachment.
     * @param attachmentId
     * @param tagIds
     * @return
     */
    @Transactional
    @RequirePermission(Permission.ATTACHMENT_CREATE)
    public List<Tag> replaceTags(int attachmentId, List<Integer> tagIds) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Attachment attachment = attachmentMapper.getMetadataById(workspaceId, attachmentId);
        if (attachment == null) {
            throw new ResourceNotFoundException("Attachment not found with id: " + attachmentId);
        }
        requireGenericAttachmentType(attachment.getEntityType());
        requireVisibleNoteTarget(workspaceId, attachment.getEntityType(), attachment.getEntityId());
        List<String> before = tagMapper.getTagsByAttachmentId(workspaceId, attachmentId).stream().map(Tag::getName).toList();
        attachmentMapper.clearTags(workspaceId, attachmentId);
        if (tagIds != null && !tagIds.isEmpty()) attachmentMapper.insertTags(workspaceId, attachmentId, tagIds);
        List<Tag> after = tagMapper.getTagsByAttachmentId(workspaceId, attachmentId);
        auditService.record("attachment.replaceTags", "attachment", attachmentId, attachment.getFileName(),
            "Updated tags on " + attachment.getFileName(),
            auditService.singleChange("tags", before, after.stream().map(Tag::getName).toList()));
        return after;
    }

    /**
     * Retrieves an attachment by ID.
     * @param id
     * @return
     */
    public Attachment getById(int id) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Attachment attachment = attachmentReadService.getById(workspaceId, id);
        if (attachment == null) throw new ResourceNotFoundException("Attachment not found with id: " + id);
        requireGenericAttachmentType(attachment.getEntityType());
        requireVisibleNoteTarget(workspaceId, attachment.getEntityType(), attachment.getEntityId());
        return attachment;
    }

    public ManagedContent getManagedContent(String token) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        String url = "/api/attachments/content/" + token;
        Attachment attachment = attachmentMapper.getMetadataByUrl(workspaceId, url);
        if (attachment == null) {
            throw new ResourceNotFoundException("Attachment not found for managed content");
        }
        requireGenericAttachmentType(attachment.getEntityType());
        requireVisibleNoteTarget(workspaceId, attachment.getEntityType(), attachment.getEntityId());
        return managedObjectService.openAttachment(workspaceId, attachment);
    }

    /**
     * Resolves the attachment record for a blob URL within the caller's workspace.
     * Backs the upload route's delete-authorization check so a session alone cannot
     * unlink another tenant's blob. Requires {@code ATTACHMENT_DELETE} so it authorizes
     * exactly what the blob unlink it precedes will do — a member who may not delete
     * attachments cannot use it to destroy the file out from under the record.
     * @param url the stored attachment url
     * @return the attachment owned by the current workspace
     */
    @RequirePermission(Permission.ATTACHMENT_DELETE)
    public Attachment getByUrl(String url) {
        Attachment attachment = attachmentReadService.getByUrl(
            workspaceService.getCurrentWorkspaceId(), url);
        if (attachment == null) throw new ResourceNotFoundException("Attachment not found for url");
        requireGenericAttachmentType(attachment.getEntityType());
        return attachment;
    }

    /**
     * Creates a new attachment.
     * @param attachment
     * @return
     */
    @RequirePermission(Permission.ATTACHMENT_CREATE)
    public Attachment create(Attachment attachment) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        if (attachment.getUrl() != null && attachment.getUrl().startsWith(MANAGED_URL_PREFIX)) {
            throw new BadRequestException("Managed attachment references cannot be submitted directly");
        }
        UserDisplayNameDto targetLabel = prepareTarget(workspaceId, attachment);
        Attachment created = attachmentWriteOperations.createExternal(workspaceId, attachment);
        return attachmentReadService.hydrateKnown(
            workspaceId, created, attachment.getUploadedBy(), targetLabel);
    }

    /**
     * Persists an internally generated managed attachment reference.
     *
     * @param attachment trusted managed attachment metadata
     * @return persisted workspace-scoped attachment
     */
    @RequirePermission(Permission.ATTACHMENT_CREATE)
    public Attachment createManaged(Attachment attachment) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        if (attachment.getUrl() == null || !attachment.getUrl().startsWith(MANAGED_URL_PREFIX)) {
            throw new BadRequestException("Managed attachment reference is invalid");
        }
        UserDisplayNameDto targetLabel = prepareTarget(workspaceId, attachment);
        Attachment created = attachmentWriteOperations.createManaged(workspaceId, attachment);
        return attachmentReadService.hydrateKnown(
            workspaceId, created, attachment.getUploadedBy(), targetLabel);
    }

    @RequirePermission(Permission.ATTACHMENT_CREATE)
    public Attachment upload(String entityType, int entityId, UploadSource source, User uploader) {
        return upload(entityType, entityId, source, uploader, false);
    }

    /** Stores a strictly image-only managed attachment for inline record or note rendering. */
    @RequirePermission(Permission.ATTACHMENT_CREATE)
    public Attachment uploadInlineImage(
            String entityType,
            int entityId,
            UploadSource source,
            User uploader) {
        return upload(entityType, entityId, source, uploader, true);
    }

    private Attachment upload(
            String entityType,
            int entityId,
            UploadSource source,
            User uploader,
            boolean inlineImage) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        String normalizedType = normalizeType(entityType);
        requireGenericAttachmentType(normalizedType);
        UserDisplayNameDto targetLabel = requireVisibleUserTarget(
            workspaceId, normalizedType, entityId);
        Attachment attachment = inlineImage
            ? attachmentWriteOperations.uploadInlineImage(
                workspaceId, normalizedType, entityId, source, uploader)
            : attachmentWriteOperations.upload(
                workspaceId, normalizedType, entityId, source, uploader);
        return attachmentReadService.hydrateKnown(
            workspaceId, attachment, uploader, targetLabel);
    }

    private UserDisplayNameDto prepareTarget(int workspaceId, Attachment attachment) {
        attachment.setWorkspaceId(workspaceId);
        attachment.setEntityType(normalizeType(attachment.getEntityType()));
        requireGenericAttachmentType(attachment.getEntityType());
        return requireVisibleUserTarget(
            workspaceId, attachment.getEntityType(), attachment.getEntityId());
    }

    private UserDisplayNameDto requireVisibleUserTarget(
            int workspaceId, String entityType, int entityId) {
        requireVisibleNoteTarget(workspaceId, entityType, entityId);
        if (!"user".equals(entityType)) {
            return null;
        }
        UserDisplayNameDto target = attachmentReadService.getActiveWorkspaceMemberLabel(
            workspaceId, entityId);
        if (target == null) {
            throw new ResourceNotFoundException("Attachment target was not found");
        }
        return target;
    }

    private void requireVisibleNoteTarget(int workspaceId, String entityType, int entityId) {
        if ("note".equals(entityType)
                && noteMapper.getVisibleNoteById(
                    workspaceId, entityId, workspaceService.getCurrentUserId()) == null) {
            throw new ResourceNotFoundException("Attachment target was not found");
        }
    }

    /**
     * Deletes an attachment by ID.
     * @param id
     */
    @RequirePermission(Permission.ATTACHMENT_DELETE)
    @Transactional
    public void delete(int id) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Attachment before = attachmentMapper.getMetadataById(workspaceId, id);
        if (before == null) throw new ResourceNotFoundException("Attachment not found with id: " + id);
        requireGenericAttachmentType(before.getEntityType());
        requireVisibleNoteTarget(workspaceId, before.getEntityType(), before.getEntityId());
        List<Integer> referenceIds = attachmentMapper.lockIdsByUrl(workspaceId, before.getUrl());
        if (!referenceIds.contains(id)) {
            throw new ResourceNotFoundException("Attachment not found with id: " + id);
        }
        if (referenceIds.size() == 1) {
            managedObjectService.deleteAttachmentAfterCommit(workspaceId, before.getUrl());
        }
        attachmentMapper.delete(workspaceId, id);
        referenceService.deleteReferencesTo(workspaceId, ReferenceService.TYPE_FILE, id);
        auditService.record("attachment.delete", "attachment", id, before.getFileName(),
            "Deleted attachment " + before.getFileName(),
            auditService.diff(before, null, AUDIT_FIELDS));
    }

    private static void requireGenericAttachmentType(String entityType) {
        if (ASSISTANT_SESSION.equals(entityType)) {
            throw new ResourceNotFoundException("Attachment not found");
        }
    }
}
