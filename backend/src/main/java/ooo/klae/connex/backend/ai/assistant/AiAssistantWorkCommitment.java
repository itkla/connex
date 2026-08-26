package ooo.klae.connex.backend.ai.assistant;

/**
 * One open commitment the daily brief read from the task projection My Work already owns.
 *
 * <p>Ask Connex never owns completion state: this row exists so a brief can name what is overdue or
 * due next and link back to the task, not so a second to-do list can be maintained beside it.
 *
 * @param id tenant-local task identifier, never projected to the model
 * @param description task description, bounded and screened before leaving the server
 * @param dueDate ISO-8601 local due date, or null when the task carries none
 * @param overdue whether the due date has already passed relative to the brief's as-of date
 * @param personId linked person, or null
 * @param dealId linked deal, or null
 */
public record AiAssistantWorkCommitment(
        int id,
        String description,
        String dueDate,
        boolean overdue,
        Integer personId,
        Integer dealId) {
}
