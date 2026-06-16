package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.AttachmentMapper;

import java.util.List;

import lombok.RequiredArgsConstructor;

/**
 * Business logic for {@code Attachment} operations.
 */

@Service
@RequiredArgsConstructor
public class AttachmentService {
    private final AttachmentMapper attachmentMapper;

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