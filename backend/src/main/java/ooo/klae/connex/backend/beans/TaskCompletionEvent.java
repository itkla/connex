package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Immutable proof of one committed false-to-true task completion transition. */
@Data
@NoArgsConstructor
public class TaskCompletionEvent {
    private long id;
    private int workspaceId;
    private int taskId;
    private LocalDateTime occurredAt;
    private LocalDateTime createdAt;
}
