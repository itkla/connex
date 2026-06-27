package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.beans.User;

/**
 * Wire shape for Attachment. The uploader is exchanged as a plain id, mirroring
 * {@code NoteDto}, with the display name denormalized into {@code uploadedByName}
 * so the client can render "uploaded by ..." without a second lookup.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AttachmentDto {

    private Integer id;

    @NotBlank
    @Size(max = 32)
    private String entityType;

    @NotNull
    private Integer entityId;

    private String entityLabel;

    @NotBlank
    @Size(max = 255)
    private String fileName;

    @NotBlank
    @Size(max = 2048)
    private String url;

    @Size(max = 255)
    private String contentType;

    private Long size;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Integer uploadedBy;
    private String uploadedByName;

    private String createdAt;
    private String updatedAt;

    private List<TagDto> tags;

    public static AttachmentDto from(Attachment a) {
        if (a == null) return null;
        AttachmentDto dto = new AttachmentDto();
        dto.id = a.getId();
        dto.entityType = a.getEntityType();
        dto.entityId = a.getEntityId();
        dto.entityLabel = a.getEntityLabel();
        dto.fileName = a.getFileName();
        dto.url = a.getUrl();
        dto.contentType = a.getContentType();
        dto.size = a.getSize();
        dto.uploadedBy = a.getUploadedBy() != null ? a.getUploadedBy().getId() : null;
        dto.uploadedByName = a.getUploadedBy() != null ? a.getUploadedBy().getDisplayName() : null;
        dto.createdAt = a.getCreatedAt();
        dto.updatedAt = a.getUpdatedAt();
        dto.tags = a.getTags() == null ? null : a.getTags().stream().map(TagDto::from).toList();
        return dto;
    }

    public Attachment toBean() {
        Attachment a = new Attachment();
        if (id != null) a.setId(id);
        a.setEntityType(entityType);
        if (entityId != null) a.setEntityId(entityId);
        a.setFileName(fileName);
        a.setUrl(url);
        a.setContentType(contentType);
        a.setSize(size);
        if (uploadedBy != null) {
            User u = new User();
            u.setId(uploadedBy);
            a.setUploadedBy(u);
        }
        a.setCreatedAt(createdAt);
        a.setUpdatedAt(updatedAt);
        return a;
    }
}