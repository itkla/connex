package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;
import java.util.List;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Workspace-local discussion thread attached to one visible CRM record. */
@Data
@NoArgsConstructor
public class RecordCommentThread {
    private long id;
    private int workspaceId;
    private String targetType;
    private int targetId;
    private int createdByUserId;
    private String state;
    private Integer resolvedByUserId;
    private LocalDateTime resolvedAt;
    private int version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<RecordComment> comments = List.of();
}
