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

import lombok.RequiredArgsConstructor;

/**
 * Business logic for {@code Attachment} operations.
 */

@Service
@RequiredArgsConstructor
public class AttachmentService {
    private final AttachmentMapper attachmentMapper;
    private final TagMapper tagMapper;

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

    public void addTag(int attachmentId, int tagId) {
        getById(attachmentId);
        if (tagMapper.getTagById(tagId) == null) throw new ResourceNotFoundException("Tag not found with id: " + tagId);
        attachmentMapper.addTag(attachmentId, tagId);
    }

    public void removeTag(int attachmentId, int tagId) {
        getById(attachmentId);
        attachmentMapper.removeTag(attachmentId, tagId);
    }

    @Transactional
    public List<Tag> replaceTags(int attachmentId, List<Integer> tagIds) {
        getById(attachmentId);
        attachmentMapper.clearTags(attachmentId);
        if (tagIds != null && !tagIds.isEmpty()) attachmentMapper.insertTags(attachmentId, tagIds);
        return tagMapper.getTagsByAttachmentId(attachmentId);
    }

    public Attachment getById(int id) {
        Attachment attachment = attachmentMapper.getById(id);
        if (attachment == null) throw new ResourceNotFoundException("Attachment not found with id: " + id);
        return attachment;
    }

    public Attachment create(Attachment attachment) {
        attachment.setEntityType(normalizeType(attachment.getEntityType()));
        attachmentMapper.insert(attachment);
        return attachmentMapper.getById(attachment.getId());
    }

    public void delete(int id) {
        if (attachmentMapper.getById(id) == null) throw new ResourceNotFoundException("Attachment not found with id: " + id);
        attachmentMapper.delete(id);
    }
}