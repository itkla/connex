package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class RecordCreationTemplate {
    private int id;
    private int workspaceId;
    private String recordType;
    private String status;
    private int position;
    private int revision;
    private Long currentVersionId;
    private Integer createdById;
    private Integer updatedById;
    private LocalDateTime archivedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
