package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/** One persisted entry in a workspace's disqualification vocabulary (#559). */
@Data
@NoArgsConstructor
public class DisqualificationReason {
    private int id;
    private int workspaceId;
    private String code;
    private String label;
    private boolean requiresNote;
    private int position;
    private boolean builtIn;
    private LocalDateTime archivedAt;
    private String createdAt;
    private String updatedAt;
}
