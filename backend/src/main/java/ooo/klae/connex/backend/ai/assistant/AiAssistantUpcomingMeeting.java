package ooo.klae.connex.backend.ai.assistant;

/**
 * One forward-dated meeting-shaped activity inside the brief's window.
 *
 * <p>Connex has no meeting entity, attendee model, or preparation state: a "meeting" here is an
 * activity the member logged with a future timestamp and a meeting-shaped type. The brief therefore
 * states that these are scheduled activities rather than claiming a prepared-or-not judgement it has
 * no source of truth for.
 *
 * @param id tenant-local activity identifier, never projected to the model
 * @param type activity type
 * @param subject activity subject, bounded and screened before leaving the server
 * @param timestamp scheduled time as a UTC {@code yyyy-MM-dd HH:mm:ss} string
 * @param personId linked person, or null
 * @param dealId linked deal, or null
 */
public record AiAssistantUpcomingMeeting(
        int id,
        String type,
        String subject,
        String timestamp,
        Integer personId,
        Integer dealId) {
}
