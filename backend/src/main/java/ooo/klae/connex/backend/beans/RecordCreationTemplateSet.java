package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class RecordCreationTemplateSet {
    private int workspaceId;
    private String recordType;
    private int revision;
    private Integer defaultTemplateId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
