package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A workspace-scoped saved-report delivery schedule. Recipient identities are
 * stored as a JSON array of control-plane user ids and hydrated in the service.
 */
@Data
@NoArgsConstructor
public class ReportSchedule {
    private int id;
    private int workspaceId;
    private int reportDefinitionId;
    private String cadence;
    private String recipientUserIds;
    private String timezone;
    private int hourOfDay;
    private boolean enabled;
    private int runAsUserId;
    private LocalDateTime nextRunAt;
    private LocalDateTime lastRunAt;
    private int createdBy;
    private String createdAt;
    private String updatedAt;
}
