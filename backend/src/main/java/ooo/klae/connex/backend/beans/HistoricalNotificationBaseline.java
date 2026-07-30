package ooo.klae.connex.backend.beans;

import lombok.Data;

/**
 * Expected notification state suppressed because it was introduced by historical data.
 */
@Data
public class HistoricalNotificationBaseline {
    private int workspaceId;
    private int recipientId;
    private String dedupeKey;
    private String notificationType;
    private String baselineSeverity;
    private String sourceStateHash;
    private String importRunId;
    private String createdAt;
}
