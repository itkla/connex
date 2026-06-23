package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.dto.AttachmentFacets;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
import ooo.klae.connex.backend.mappers.TagMapper;

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

    public List<Attachment> getByEntity(String entityType, int entityId) {
        return attachmentMapper.getByEntity(normalizeType(entityType), entityId);
    }

    public List<Attachment> getAll() {
        return attachmentMapper.getAll();
    }

    public List<Attachment> getPage(String query, String sort, List<String> types, List<String> kinds,
            List<Integer> tagIds, Boolean orphaned, int limit, int offset) {
        return attachmentMapper.getPage(query, sort, types, kinds, tagIds, orphaned, limit, offset);
    }

    public long countPage(String query, List<String> types, List<String> kinds, List<Integer> tagIds,
            Boolean orphaned) {
        return attachmentMapper.countPage(query, types, kinds, tagIds, orphaned);
    }

    public AttachmentFacets facets() {
        return new AttachmentFacets(
            attachmentMapper.countsBySource(),
            attachmentMapper.countsByKind(),
            attachmentMapper.countsByTag(),
            attachmentMapper.countOrphaned(),
            attachmentMapper.totalCount(),
            attachmentMapper.totalSize()
        );
    }

    public List<Tag> getTags(int attachmentId) {
        getById(attachmentId);
        return tagMapper.getTagsByAttachmentId(attachmentId);
    }

    /**
     * Adds a tag to an attachment.
     * @param attachmentId
     * @param tagId
     */
    public void addTag(int attachmentId, int tagId) {
        Attachment attachment = getById(attachmentId);
        Tag tag = tagMapper.getTagById(workspaceService.getCurrentWorkspaceId(), tagId);
        if (tag == null) {
            auditService.recordFailure("attachment.addTag", "attachment", attachmentId, attachment.getFileName(),
                    "Tag not found", "Tag not found with id: " + tagId);
            throw new ResourceNotFoundException("Tag not found with id: " + tagId);
        }
        attachmentMapper.addTag(attachmentId, tagId);
        auditService.record("attachment.addTag", "attachment", attachmentId, attachment.getFileName(),
            "Tagged " + attachment.getFileName() + " with " + tag.getName(),
            auditService.singleChange("tag", null, tag.getName()));
    }

    /**
     * Adds a tag to an attachment.
     * @param attachmentId
     * @param tagId
     */
    public void removeTag(int attachmentId, int tagId) {
        Attachment attachment = getById(attachmentId);
        Tag tag = tagMapper.getTagById(workspaceService.getCurrentWorkspaceId(), tagId);
        if (tag == null) {
            auditService.recordFailure("attachment.removeTag", "attachment", attachmentId, attachment.getFileName(),
                    "Tag not found", "Tag not found with id: " + tagId);
            throw new ResourceNotFoundException("Tag not found with id: " + tagId);
        }
        attachmentMapper.removeTag(attachmentId, tagId);
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
    public List<Tag> replaceTags(int attachmentId, List<Integer> tagIds) {
        Attachment attachment = getById(attachmentId);
        List<String> before = tagMapper.getTagsByAttachmentId(attachmentId).stream().map(Tag::getName).toList();
        attachmentMapper.clearTags(attachmentId);
        if (tagIds != null && !tagIds.isEmpty()) attachmentMapper.insertTags(attachmentId, tagIds);
        List<Tag> after = tagMapper.getTagsByAttachmentId(attachmentId);
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
        Attachment attachment = attachmentMapper.getById(id);
        if (attachment == null) throw new ResourceNotFoundException("Attachment not found with id: " + id);
        return attachment;
    }

    /**
     * Creates a new attachment.
     * @param attachment
     * @return
     */
    public Attachment create(Attachment attachment) {
        attachment.setEntityType(normalizeType(attachment.getEntityType()));
        attachmentMapper.insert(attachment);
        // Audit from the inserted bean (id populated by the key generator) so a failed
        // re-fetch can never NPE and break the create it is only meant to observe.
        auditService.record("attachment.create", "attachment", attachment.getId(), attachment.getFileName(),
            "Uploaded attachment " + attachment.getFileName(),
            auditService.diff(null, attachment, AUDIT_FIELDS));
        return attachmentMapper.getById(attachment.getId());
    }

    /**
     * Deletes an attachment by ID.
     * @param id
     */
    public void delete(int id) {
        Attachment before = attachmentMapper.getById(id);
        if (before == null) throw new ResourceNotFoundException("Attachment not found with id: " + id);
        attachmentMapper.delete(id);
        auditService.record("attachment.delete", "attachment", id, before.getFileName(),
            "Deleted attachment " + before.getFileName(),
            auditService.diff(before, null, AUDIT_FIELDS));
    }
}