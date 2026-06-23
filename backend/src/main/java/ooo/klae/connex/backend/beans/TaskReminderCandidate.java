package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Workspace-scoped task reminder projection.
 */
@Data
@NoArgsConstructor
public class TaskReminderCandidate {
    private int workspaceId;
    private int taskId;
    private String taskLabel;
    private String dueDate;
    private int recipientId;
    private String recipientTimezone;
    private Integer dealId;
    private String dealLabel;
    private Integer personId;
    private String personLabel;
}