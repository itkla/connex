package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Workspace-scoped scheduled report delivery with service-hydrated member labels.
 * @param id schedule id
 * @param reportDefinitionId scheduled report id
 * @param cadence delivery cadence
 * @param recipientUserIds stored recipient user ids
 * @param recipients currently active resolved recipients
 * @param timezone IANA delivery timezone
 * @param hourOfDay local delivery hour
 * @param enabled whether delivery is active
 * @param runAsUserId member identity used to generate snapshots
 * @param runAsLabel current run-as member label, when still active
 * @param nextRunAt next delivery time in UTC
 * @param lastRunAt last claimed delivery time in UTC
 * @param createdBy creator user id
 * @param createdAt creation timestamp
 * @param updatedAt update timestamp
 */
public record ReportScheduleDto(
        int id,
        int reportDefinitionId,
        String cadence,
        List<Integer> recipientUserIds,
        List<ReportScheduleRecipientDto> recipients,
        String timezone,
        int hourOfDay,
        boolean enabled,
        int runAsUserId,
        String runAsLabel,
        LocalDateTime nextRunAt,
        LocalDateTime lastRunAt,
        int createdBy,
        String createdAt,
        String updatedAt) {
}
