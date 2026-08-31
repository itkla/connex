package ooo.klae.connex.backend.work;

import ooo.klae.connex.backend.dto.SnoozeRequest;
import ooo.klae.connex.backend.dto.WorkItemAction;

/** Version-bound command delegated to one authoritative source. */
public record WorkItemActionCommand(
    WorkItemAction action,
    String expectedStateHash,
    SnoozeRequest snooze,
    String decision,
    String comment,
    Integer stepId
) {
}
