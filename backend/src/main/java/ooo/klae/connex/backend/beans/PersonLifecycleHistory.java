package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One accepted lead-lifecycle transition for a contact. The table is append-only: a stage that is
 * re-entered adds another row rather than amending the previous one, and a {@code null} stage on
 * either side records entering or withdrawing from the lifecycle. Mapped via
 * {@code PersonLifecycleHistoryMapper}.
 */
@Data
@NoArgsConstructor
public class PersonLifecycleHistory {
    private long id;
    private int workspaceId;
    private int personId;
    private PersonLifecycleStage fromStage;
    private PersonLifecycleStage toStage;
    private PersonDisqualificationReason reason;
    private String note;
    private Integer changedById;
    private LocalDateTime changedAt;
}
