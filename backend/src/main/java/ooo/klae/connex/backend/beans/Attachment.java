package ooo.klae.connex.backend.beans;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonIdentityReference;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a file attached to any entity. The owning record is referenced
 * polymorphically via {@code entityType} + {@code entityId} so the same table
 * backs companies, persons, deals, users, and anything added later.
 * The binary is stored on the Next.js filesystem; only the public {@code url}
 * and metadata are persisted here. Mapped via {@code AttachmentMapper} / {@code AttachmentMapper.xml}.
 */

@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id", scope = Attachment.class)
@Data
@NoArgsConstructor
public class Attachment {
    private int id;
    private String entityType;
    private int entityId;
    private String entityLabel;
    private String fileName;
    private String url;
    private String contentType;
    private Long size;
    @JsonIdentityReference(alwaysAsId = true)
    private User uploadedBy;
    private String createdAt;
    private String updatedAt;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public int getEntityId() {
        return entityId;
    }

    public void setEntityId(int entityId) {
        this.entityId = entityId;
    }

    public String getEntityLabel() {
        return entityLabel;
    }

    public void setEntityLabel(String entityLabel) {
        this.entityLabel = entityLabel;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public Long getSize() {
        return size;
    }

    public void setSize(Long size) {
        this.size = size;
    }

    public User getUploadedBy() {
        return uploadedBy;
    }

    public void setUploadedBy(User uploadedBy) {
        this.uploadedBy = uploadedBy;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }
}