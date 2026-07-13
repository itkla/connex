package ooo.klae.connex.backend.dto;

/**
 * Active workspace member resolved for a report delivery schedule.
 * @param userId member user id
 * @param displayName current display name
 * @param email current delivery address
 */
public record ReportScheduleRecipientDto(int userId, String displayName, String email) {
}
