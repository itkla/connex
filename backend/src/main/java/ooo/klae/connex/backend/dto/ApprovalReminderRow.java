package ooo.klae.connex.backend.dto;

/**
 * Bounded tenant-plane projection of one active approval step that has crossed a reminder threshold.
 *
 * @param stepId        the frozen step
 * @param remindedRound highest reminder round already emitted for the step
 * @param dueRound      round the step's elapsed fraction of its deadline currently warrants
 */
public record ApprovalReminderRow(
    int stepId,
    int remindedRound,
    int dueRound
) {
}
