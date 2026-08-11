package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Immutable comment content and redaction metadata within a record comment thread. */
@Data
@NoArgsConstructor
public class RecordComment {
    private long id;
    private int workspaceId;
    private long threadId;
    private int authorUserId;
    private String content;
    private String clientToken;
    private LocalDateTime createdAt;
    private LocalDateTime deletedAt;
    private Integer deletedByUserId;
    private String authorDisplayName;
    private String authorProfilePictureUrl;
}
