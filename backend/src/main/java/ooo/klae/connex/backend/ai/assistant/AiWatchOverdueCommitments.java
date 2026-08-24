package ooo.klae.connex.backend.ai.assistant;

/**
 * The deterministic overdue-commitment state of one watched record.
 *
 * <p>Both figures come straight from the task projection: the watch adds a threshold and a cooldown
 * on top of them and never recomputes what "overdue" means.
 *
 * @param overdueCount open tasks linked to the record whose due date has passed
 * @param earliestDueDate ISO-8601 due date of the oldest of them, or null when there are none
 */
public record AiWatchOverdueCommitments(int overdueCount, String earliestDueDate) {
}
