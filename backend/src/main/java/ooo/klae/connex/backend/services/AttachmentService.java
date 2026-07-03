package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.dto.AttachmentFacets;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
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
    private final AttachmentMapper attachmentMapper;
    private final TagMapper tagMapper;
    private final AuditService auditService;
    private final WorkspaceService workspaceService;

    private static final Set<String> AUDIT_FIELDS =
        Set.of("fileName", "entityType", "entityId", "url", "contentType", "size");

    private String normalizeType(String entityType) {
        if (!StringUtils.hasText(entityType)) {
            throw new ResourceNotFoundException("entityType is required");
        }
        return entityType.trim().toLowerCase();
    }

    /**
     * Rejects attachment URLs that are neither an app-relative path nor an http(s)
     * URL, blocking script-bearing schemes such as {@code javascript:} from being
     * stored and later rendered into an anchor href.
     * @param url the candidate attachment url
     */
    private void validateUrl(String url) {
        if (url == null || url.chars().anyMatch(c -> c < 0x20 || c == 0x7F)
                || url.startsWith("//") || url.startsWith("/\\")
                || !(url.startsWith("/")
                    || url.regionMatches(true, 0, "http://", 0, 7)
                    || url.regionMatches(true, 0, "https://", 0, 8))) {
            throw new BadRequestException("Attachment url must be an app-relative path or an http(s) URL");
        }
    }

    public List<Attachment> getByEntity(String entityType, int entityId) {
        return attachmentMapper.getByEntity(workspaceService.getCurrentWorkspaceId(), normalizeType(entityType), entityId);
    }

    public List<Attachment> getAll() {
        return attachmentMapper.getAll(workspaceService.getCurrentWorkspaceId());
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
        Attachment attachment = getById(attachmentId);
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
        Attachment attachment = attachmentMapper.getById(workspaceService.getCurrentWorkspaceId(), id);
        if (attachment == null) throw new ResourceNotFoundException("Attachment not found with id: " + id);
        return attachment;
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
        Attachment attachment = attachmentMapper.getByUrl(workspaceService.getCurrentWorkspaceId(), url);
        if (attachment == null) throw new ResourceNotFoundException("Attachment not found for url");
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
        attachment.setWorkspaceId(workspaceId);
        attachment.setEntityType(normalizeType(attachment.getEntityType()));
        validateUrl(attachment.getUrl());
        if (attachmentMapper.countUrlInOtherWorkspaces(workspaceId, attachment.getUrl()) > 0) {
            throw new BadRequestException("That attachment url is already in use");
        }
        attachmentMapper.insert(attachment);
        // Audit from the inserted bean (id populated by the key generator) so a failed
        // re-fetch can never NPE and break the create it is only meant to observe.
        auditService.record("attachment.create", "attachment", attachment.getId(), attachment.getFileName(),
            "Uploaded attachment " + attachment.getFileName(),
            auditService.diff(null, attachment, AUDIT_FIELDS));
        return attachmentMapper.getById(workspaceId, attachment.getId());
    }

    /**
     * Deletes an attachment by ID.
     * @param id
     */
    @RequirePermission(Permission.ATTACHMENT_DELETE)
    public void delete(int id) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Attachment before = attachmentMapper.getById(workspaceId, id);
        if (before == null) throw new ResourceNotFoundException("Attachment not found with id: " + id);
        attachmentMapper.delete(workspaceId, id);
        auditService.record("attachment.delete", "attachment", id, before.getFileName(),
            "Deleted attachment " + before.getFileName(),
            auditService.diff(before, null, AUDIT_FIELDS));
    }
}