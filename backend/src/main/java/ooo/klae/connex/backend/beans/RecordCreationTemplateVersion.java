package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class RecordCreationTemplateVersion {
    private long id;
    private int workspaceId;
    private int templateId;
    private int versionNumber;
    private String nameEn;
    private String nameJa;
    private String descriptionEn;
    private String descriptionJa;
    private String definitionJson;
    private byte[] definitionHash;
    private Integer createdById;
    private LocalDateTime createdAt;
}
