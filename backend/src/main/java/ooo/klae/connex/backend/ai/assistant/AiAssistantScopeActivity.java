package ooo.klae.connex.backend.ai.assistant;

/**
 * One bounded activity row returned by a scoped bulk read, already attributed to the cohort record
 * it belongs to so the caller never has to fan out per record to discover the association.
 *
 * @param id tenant-local activity identifier, never projected to the model
 * @param scopeRecordId cohort record the activity was attributed to
 * @param type activity type
 * @param subject activity subject
 * @param notes activity notes, bounded and screened before leaving the server
 * @param timestamp activity time as a UTC {@code yyyy-MM-dd HH:mm:ss} string
 * @param personId linked person, or null
 * @param dealId linked deal, or null
 */
public record AiAssistantScopeActivity(
        int id,
        int scopeRecordId,
        String type,
        String subject,
        String notes,
        String timestamp,
        Integer personId,
        Integer dealId) {
}
