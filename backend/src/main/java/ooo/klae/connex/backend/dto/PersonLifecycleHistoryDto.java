package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;

import ooo.klae.connex.backend.beans.PersonLifecycleHistory;
import ooo.klae.connex.backend.beans.PersonLifecycleStage;

/**
 * One entry of a contact's lead-lifecycle timeline. A {@code null} stage on either side records
 * entering or withdrawing from the lifecycle.
 */
public record PersonLifecycleHistoryDto(
    long id,
    int personId,
    PersonLifecycleStage fromStage,
    PersonLifecycleStage toStage,
    String reason,
    String reasonLabel,
    String note,
    Integer changedById,
    LocalDateTime changedAt
) {
    /**
     * Projects one persisted transition.
     *
     * @param history persisted transition row
     * @return timeline entry
     */
    public static PersonLifecycleHistoryDto from(
            PersonLifecycleHistory history, String reasonLabel) {
        return new PersonLifecycleHistoryDto(
            history.getId(),
            history.getPersonId(),
            history.getFromStage(),
            history.getToStage(),
            history.getReason(),
            reasonLabel,
            history.getNote(),
            history.getChangedById(),
            history.getChangedAt());
    }
}
